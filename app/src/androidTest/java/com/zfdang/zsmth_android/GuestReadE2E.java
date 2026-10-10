package com.zfdang.zsmth_android;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.util.Log;
import android.widget.TextView;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.models.PostListContent;
import com.zfdang.zsmth_android.models.Topic;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 真机完整验证入口：验证等待、正文加载、取消、跨进程 Cookie 复用。 */
public class GuestReadE2E extends Instrumentation {
  private String mode;
  private File output;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    mode = arguments.getString("mode", "verify");
    output = new File(getTargetContext().getExternalFilesDir(null), "guest-read-e2e/" + mode);
    output.mkdirs();
    start();
  }

  @Override
  public void onStart() {
    JSONObject result = new JSONObject();
    int code = Activity.RESULT_CANCELED;
    try {
      result.put("模式", mode);
      if (!"reuse".equals(mode)) {
        runOnMainSync(() -> {
          CookieManager manager = CookieManager.getInstance();
          manager.setCookie("https://www.newsmth.net/",
              "GUEST_READ_VERIFIED=; Max-Age=0; Path=/; Domain=.newsmth.net; Secure; HttpOnly");
          manager.flush();
        });
      } else {
        String cookies = CookieManager.getInstance().getCookie("https://www.newsmth.net/");
        check(cookies != null && cookies.contains("GUEST_READ_VERIFIED="), "重启后的放行 Cookie 存在");
        result.put("重启后放行Cookie存在", true);
      }

      Topic topic = new Topic();
      topic.setBoardEngName("FamilyLife");
      topic.setBoardChsName("家庭生活");
      topic.setTopicID("1767742045");
      topic.setTitle("我叔，90 年代出国，现在不敢回来参加同学会");
      Intent intent = new Intent(getTargetContext(), PostListActivity.class);
      intent.putExtra(SMTHApplication.TOPIC_OBJECT, topic);
      intent.putExtra(SMTHApplication.FROM_BOARD, SMTHApplication.FROM_BOARD_HOT);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      PostListActivity activity = (PostListActivity) startActivitySync(intent);
      long start = SystemClock.elapsedRealtime();
      boolean sawVerification = false;
      int[] posts = {0};
      boolean[] waiting = {false};
      while (SystemClock.elapsedRealtime() - start < 190000) {
        runOnMainSync(() -> {
          posts[0] = activity.mRecyclerView.getAdapter().getItemCount();
          TextView message = activity.pDialog == null ? null : activity.pDialog.findViewById(android.R.id.message);
          waiting[0] = activity.pDialog != null && activity.pDialog.isShowing() && message != null
              && message.getText().toString().contains("正在验证");
        });
        if (waiting[0] && !sawVerification) {
          sawVerification = true;
          screenshot("waiting.png");
          if ("cancel".equals(mode)) {
            runOnMainSync(() -> activity.pDialog.cancel());
            SystemClock.sleep(1600);
            runOnMainSync(() -> {
              posts[0] = activity.mRecyclerView.getAdapter().getItemCount();
              waiting[0] = activity.pDialog.isShowing();
            });
            check(!waiting[0] && posts[0] == 0, "取消后等待结束且没有加载过期结果");
            screenshot("cancelled.png");
            result.put("取消后等待结束", true);
            break;
          }
        }
        if (posts[0] >= 10) {
          check("reuse".equals(mode) ? !sawVerification : sawVerification, "验证码等待状态符合预期");
          runOnMainSync(() -> check(!activity.pDialog.isShowing(), "正文加载后等待结束"));
          check(!"错误信息".equals(PostListContent.POSTS.get(0).getAuthor()), "加载真实帖子");
          SystemClock.sleep(300);
          screenshot("posts.png");
          result.put("正文帖子数", posts[0]);
          break;
        }
        SystemClock.sleep(30);
      }
      check("cancel".equals(mode) ? sawVerification && !waiting[0] : posts[0] >= 10, "本轮测试完成");
      result.put("曾显示验证码等待", sawVerification);
      result.put("耗时毫秒", SystemClock.elapsedRealtime() - start);
      result.put("结果", "通过");
      runOnMainSync(activity::finish);
      code = Activity.RESULT_OK;
    } catch (Throwable error) {
      Log.e("GuestReadE2E", "真机验证失败", error);
      try {
        result.put("结果", "失败");
        result.put("错误", error.toString());
        screenshot("failure.png");
      } catch (Exception ignored) {
      }
    }
    Bundle bundle = new Bundle();
    try (FileOutputStream stream = new FileOutputStream(new File(output, "result.json"))) {
      stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
      bundle.putString("stream", result.toString(2));
    } catch (Exception error) {
      bundle.putString("stream", error.toString());
      code = Activity.RESULT_CANCELED;
    }
    finish(code, bundle);
  }

  private void screenshot(String name) throws Exception {
    Bitmap image = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
    check(image != null, "取得屏幕截图");
    try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, stream);
    }
    image.recycle();
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
