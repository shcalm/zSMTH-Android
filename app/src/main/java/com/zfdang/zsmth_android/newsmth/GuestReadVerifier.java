package com.zfdang.zsmth_android.newsmth;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.zfdang.SMTHApplication;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InterruptedIOException;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 同一时间只允许一个帖子验证流程，图片、答案与 Cookie 使用同一会话。 */
final class GuestReadVerifier {
  private static final String TAG = "GuestReadVerifier";
  private long lastFinished;
  private long lastStarted;

  Observable<String> loadPage(OkHttpClient client, HttpUrl url, Runnable onVerification) {
    return Observable.create(emitter -> {
      try {
        synchronized (this) {
          String page = new String(fetch(client, pageRequest(url), emitter, false), "GBK");
          Document document = Jsoup.parse(page, url.toString());
          if (document.selectFirst(".guest-read-challenge") != null) {
            Log.i(TAG, "challenge detected path=" + url.encodedPath());
            onVerification.run();
            if (Build.VERSION.SDK_INT < 24) {
              throw new IOException("自动验证需要 Android 7.0 或更新版本");
            }
            page = verify(client, url, page, emitter);
          }
          checkCancelled(emitter);
          emitter.onNext(page);
          emitter.onComplete();
        }
      } catch (Exception error) {
        if (emitter.isDisposed()) {
          Log.i(TAG, "request cancelled path=" + url.encodedPath());
        } else {
          Log.e(TAG, "post request failed", error);
          emitter.tryOnError(error);
        }
      }
    });
  }

  private String verify(OkHttpClient client, HttpUrl url, String page, ObservableEmitter<String> emitter)
      throws Exception {
    long start = SystemClock.elapsedRealtime();
    int submissions = 0;
    int failures = 0;
    int skipped = 0;
    for (int imageNumber = 1; imageNumber <= 100; imageNumber++) {
      checkCancelled(emitter);
      if (SystemClock.elapsedRealtime() - start >= 180000) {
        throw new IOException("验证等待超时，请刷新重试");
      }
      Document document = Jsoup.parse(page, url.toString());
      Element form = document.selectFirst(".guest-read-challenge form:has(input[name=auth])");
      Element image = document.selectFirst(".guest-read-challenge img[src]");
      if (form == null || image == null || !"post".equalsIgnoreCase(form.attr("method"))) {
        throw new IOException("验证码页面格式异常，请刷新重试");
      }
      HttpUrl action = sameHost(url, form.absUrl("action"));
      HttpUrl imageUrl = sameHost(url, image.absUrl("src")).newBuilder()
          .setQueryParameter("ts", Long.toString(System.currentTimeMillis())).build();
      Request imageRequest = new Request.Builder().url(imageUrl).header("Referer", url.toString())
          .header("Cache-Control", "no-cache, no-store").build();
      byte[] bytes = fetch(client, imageRequest, emitter, true);
      String candidate = CaptchaRecognizer.getInstance(SMTHApplication.getAppContext()).recognize(bytes);
      checkCancelled(emitter);
      if (!candidate.matches("[A-Za-z0-9]{6}")) {
        skipped++;
        Log.i(TAG, "image=" + imageNumber + " candidateLength=" + candidate.length() + " action=refresh");
        continue;
      }
      FormBody.Builder fields = new FormBody.Builder();
      for (Element hidden : form.select("input[type=hidden][name]")) {
        if (!"auth".equals(hidden.attr("name"))) {
          fields.add(hidden.attr("name"), hidden.attr("value"));
        }
      }
      fields.add("auth", candidate);
      Log.i(TAG, "image=" + imageNumber + " candidate=" + candidate + " action=submit");
      Request post = new Request.Builder().url(action).post(fields.build())
          .header("Referer", url.toString()).header("X-Requested-With", "XMLHttpRequest").build();
      submissions++;
      String submitted = new String(fetch(client, post, emitter, true), "GBK");
      String error = Jsoup.parse(submitted).select(".error").text();
      page = new String(fetch(client, pageRequest(url), emitter, true), "GBK");
      document = Jsoup.parse(page, url.toString());
      int articles = document.select("table.article").size();
      if (document.selectFirst(".guest-read-challenge") == null && articles > 0) {
        Log.i(TAG, "verified images=" + imageNumber + " submissions=" + submissions + " failures=" + failures
            + " skipped=" + skipped + " articles=" + articles + " elapsedMs=" + (SystemClock.elapsedRealtime() - start));
        return page;
      }
      if (document.selectFirst(".guest-read-challenge") == null) {
        throw new IOException("验证提交后未能加载帖子，请刷新重试");
      }
      failures++;
      Log.i(TAG, "verification rejected image=" + imageNumber + " message=" + error);
    }
    throw new IOException("验证暂未通过，请刷新重试");
  }

  private byte[] fetch(OkHttpClient client, Request request, ObservableEmitter<String> emitter, boolean paced)
      throws IOException, InterruptedException {
    checkCancelled(emitter);
    if (paced) {
      long wait = lastFinished + 1000 - SystemClock.elapsedRealtime();
      if (wait > 0) {
        Thread.sleep(wait);
      }
    }
    checkCancelled(emitter);
    long start = SystemClock.elapsedRealtime();
    long interval = lastStarted == 0 ? 0 : start - lastStarted;
    lastStarted = start;
    Call call = client.newCall(request);
    emitter.setCancellable(call::cancel);
    try (Response response = call.execute()) {
      Log.i(TAG, "request method=" + request.method() + " path=" + request.url().encodedPath()
          + " status=" + response.code() + " intervalMs=" + interval);
      if (response.code() == 429) {
        throw new IOException("验证请求受到限流，请稍后刷新重试");
      }
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException("请求失败，状态码 " + response.code());
      }
      return response.body().bytes();
    } finally {
      lastFinished = SystemClock.elapsedRealtime();
    }
  }

  private static Request pageRequest(HttpUrl url) {
    return new Request.Builder().url(url).header("X-Requested-With", "XMLHttpRequest")
        .header("Cache-Control", "no-cache, no-store").build();
  }

  private static HttpUrl sameHost(HttpUrl page, String address) throws IOException {
    HttpUrl result = HttpUrl.parse(address);
    if (result == null || !"https".equals(result.scheme()) || !page.host().equals(result.host())
        || page.port() != result.port()) {
      throw new IOException("验证码请求地址异常");
    }
    return result;
  }

  private static void checkCancelled(ObservableEmitter<String> emitter) throws InterruptedIOException {
    if (emitter.isDisposed() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("请求已取消");
    }
  }
}
