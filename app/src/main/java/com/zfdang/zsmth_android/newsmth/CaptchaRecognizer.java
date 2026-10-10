package com.zfdang.zsmth_android.newsmth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** ddddocr 默认模型；输入与 PC 原图识别保持一致。 */
final class CaptchaRecognizer {
  private static CaptchaRecognizer instance;
  private final OrtEnvironment environment;
  private final OrtSession session;
  private final String[] charset;

  static synchronized CaptchaRecognizer getInstance(Context context) throws Exception {
    if (instance == null) {
      instance = new CaptchaRecognizer(context.getApplicationContext());
    }
    return instance;
  }

  private CaptchaRecognizer(Context context) throws Exception {
    JSONArray chars = new JSONArray(new String(readAsset(context, "captcha/charset.json"), "UTF-8"));
    charset = new String[chars.length()];
    for (int i = 0; i < charset.length; i++) {
      charset[i] = chars.getString(i);
    }
    environment = OrtEnvironment.getEnvironment();
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(2);
      session = environment.createSession(readAsset(context, "captcha/common_old.onnx"), options);
    }
  }

  String recognize(byte[] image) throws Exception {
    Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
    if (bitmap == null) {
      throw new IOException("验证码图片解码失败");
    }
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    if (width > 512 || height > 256 || height == 0) {
      bitmap.recycle();
      throw new IOException("验证码图片尺寸异常");
    }
    int[] pixels = new int[width * height];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    bitmap.recycle();
    int targetWidth = Math.max(1, width * 64 / height);
    pixels = resize(pixels, width, height, targetWidth, 64);
    float[] input = new float[pixels.length];
    for (int i = 0; i < pixels.length; i++) {
      int pixel = pixels[i];
      int gray = (((pixel >> 16 & 255) * 19595 + (pixel >> 8 & 255) * 38470
          + (pixel & 255) * 7471 + 32768) >> 16);
      input[i] = (gray / 255f - 0.5f) / 0.5f;
    }
    try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
        new long[] {1, 1, 64, targetWidth});
        OrtSession.Result output = session.run(Collections.singletonMap("input1", tensor))) {
      float[][][] logits = (float[][][]) output.get(0).getValue();
      StringBuilder result = new StringBuilder();
      int previous = 0;
      for (float[][] step : logits) {
        float[] scores = step[0];
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
          if (scores[i] > scores[best]) {
            best = i;
          }
        }
        if (best != previous && best != 0) {
          result.append(charset[best]);
        }
        previous = best;
      }
      return result.toString();
    }
  }

  private static byte[] readAsset(Context context, String name) throws IOException {
    try (InputStream stream = context.getAssets().open(name);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int count;
      while ((count = stream.read(buffer)) != -1) {
        bytes.write(buffer, 0, count);
      }
      return bytes.toByteArray();
    }
  }

  // Pillow 使用两次 RGB Lanczos 重采样，且每次将中间像素舍入为八位。
  private static int[] resize(int[] pixels, int width, int height, int targetWidth, int targetHeight) {
    pixels = resizeAxis(pixels, width, height, targetWidth, true);
    return resizeAxis(pixels, targetWidth, height, targetHeight, false);
  }

  private static int[] resizeAxis(int[] pixels, int width, int height, int target, boolean horizontal) {
    int size = horizontal ? width : height;
    if (size == target) {
      return pixels;
    }
    int lines = horizontal ? height : width;
    int[] output = new int[target * lines];
    double scale = (double) size / target;
    double filterScale = Math.max(1, scale);
    double support = 3 * filterScale;
    for (int position = 0; position < target; position++) {
      double center = (position + 0.5) * scale;
      int start = Math.max(0, (int) (center - support + 0.5));
      int end = Math.min(size, (int) (center + support + 0.5));
      double[] weights = new double[end - start];
      double sum = 0;
      for (int i = 0; i < weights.length; i++) {
        double x = (start + i - center + 0.5) / filterScale;
        weights[i] = x == 0 ? 1 : Math.abs(x) < 3
            ? Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 3) / (Math.PI * Math.PI * x * x / 3) : 0;
        sum += weights[i];
      }
      int[] coefficients = new int[weights.length];
      for (int i = 0; i < weights.length; i++) {
        double coefficient = weights[i] / sum * (1 << 22);
        coefficients[i] = (int) (coefficient + (coefficient < 0 ? -0.5 : 0.5));
      }
      for (int line = 0; line < lines; line++) {
        long red = 1 << 21;
        long green = 1 << 21;
        long blue = 1 << 21;
        for (int i = 0; i < coefficients.length; i++) {
          int pixel = pixels[horizontal ? line * width + start + i : (start + i) * width + line];
          red += (long) (pixel >> 16 & 255) * coefficients[i];
          green += (long) (pixel >> 8 & 255) * coefficients[i];
          blue += (long) (pixel & 255) * coefficients[i];
        }
        int pixel = 0xff000000 | clamp(red) << 16 | clamp(green) << 8 | clamp(blue);
        output[horizontal ? line * target + position : position * width + line] = pixel;
      }
    }
    return output;
  }

  private static int clamp(long value) {
    return (int) Math.max(0, Math.min(255, value >> 22));
  }
}
