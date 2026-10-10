# 帖子自动验证

帖子页返回 `.guest-read-challenge` 时，显示“正在验证，请稍候…”，后台使用 ddddocr 1.5.6 的默认模型 `common_old.onnx` 识别原图。输入按 Python 流程进行 Lanczos 缩放、灰度化、归一化和 CTC 解码。

六位字母数字才提交，每图最多提交一个候选。长度不符或验证被拒绝时刷新图片，验证请求完成后至少等待一秒。同一时间只执行一个帖子加载流程，图片、提交和复查共用现有 Cookie 存储。

成功提交后保存并持久化 `GUEST_READ_VERIFIED`；同一会话复查得到真实 `table.article` 后显示正文。取消等待、离开页面或重新加载会取消旧请求。请求异常、限流、三分钟或一百张图片上限会结束等待，提示刷新重试。

Cookie 有效期由服务器决定，本次实测约十二小时。每次打开、翻页或刷新帖子都先请求正文；Cookie 到期或被服务器撤销后，返回验证码页面即重新触发自动验证，成功后保存新的 Cookie。

自动识别支持 Android 7.0 / API24 及以上。更早系统保留应用原有功能，遇到验证码时显示版本提示。模型与推理库的来源、校验和及许可证位于 `app/src/main/assets/captcha/`，推理库直接从 `app/libs/` 引入。

## 本机离线构建

```bash
ANDROID_HOME=/home/airdrop/Android/Sdk \
  /home/airdrop/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle \
  --offline --init-script /tmp/zsmth-local-sdk.init.gradle \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

## 真机端到端验证

安装主 APK 与测试 APK 后运行：

```bash
bash tools/guest-read-e2e.sh 9d75a0b7 /work/hua2/projects/zsmth-android-verification-repeat
```

测试依次覆盖：

1. `cancel`：将放行 Cookie 设为过期，打开真实帖子，显示验证等待后取消，确认等待结束且旧请求停止。
2. `verify`：重新触发挑战，等待自动验证，确认正文至少十条、等待关闭。
3. `reuse`：停止应用后重新启动，确认放行 Cookie 存在、正文加载时直接复用。

测试输出逐项 JSON、等待及正文截图、设备日志。测试只调整访客放行 Cookie。设备日志记录图片序号、提交次数、拒绝提示、HTTP 状态、请求间隔和 Cookie 有效期；Cookie 值保留在进程与现有 Cookie 存储中。

Instrumentation 的截图使用 `FLAG_DONT_USE_ACCESSIBILITY`，可与设备已有的无障碍自动化服务共存。
