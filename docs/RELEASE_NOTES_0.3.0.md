# LivingUnlock 0.3.0

Windows 与 Android 同步更新至 0.3.0。

## 更新内容

- 修复电脑和手机存在时钟偏差时，解锁请求不到一秒就超时的问题。两端独立使用单调时钟计时，Windows 保留原有 30 秒挑战截止校验。
- 解锁前增加配对密钥认证的内部时间握手，Android 优先取时，Windows 备用，任一端成功后共享校准结果。启动取时最多三次；解锁准备阶段每项最多重试一次，失败后自动继续独立计时。
- 并行请求国家授时中心、阿里云和腾讯云；无有效结果时回退 Windows 时间服务器。缓存有效样本，避免重复取时；不修改系统时间或其他软件的代理配置。
- Windows 主页横幅右侧增加应用内部时间与校准按钮；Android 扫码按钮上方增加时间栏，数字逐位上下滚动。Windows 登录组件只显示准备阶段提示，不显示具体时间。
- Windows 和 Android 增加手动检查 GitHub Releases 更新的入口，支持预发布筛选和对应平台的下载链接。

## 下载与升级

- Windows：`LivingUnlock-Setup-0.3.0-x64.exe`
- Android：`LivingUnlock-Android-0.3.0-compact.apk`，正式包名保持 `com.windowslockpin.companion`，沿用现有发行签名。
- 校验文件：`SHA256SUMS-0.3.0.txt`

保留现有 Windows 账户凭据、启用状态、手机配对和原生 PIN 登录入口。建议两端一起更新。
Android Debug 包与正式包不共享配对数据；正式 APK 不会覆盖 `.debug` 包。

## 验证范围

已完成针对时间握手、时钟偏差、离线降级、重试上限、迟到和半包响应、缓存互通及版本选择的定向测试，以及 Windows 发行构建和 Android 正式签名构建。
本机覆盖安装确认配对记录和其他 Windows 登录提供程序注册未改变。完整断网、重启后登录和不同硬件的实际锁屏行为仍需分别验收；本次按既有实验版渠道发布。
