# LivingUnlock

LivingUnlock 是一套实验性的 Windows 11 本地解锁方案，由 Windows Credential Provider、WinUI 3 管理客户端和 Android 伴侣应用组成。

它在 Windows 原生登录选项旁提供两种额外入口：

- 使用 Google Authenticator 或其他兼容 TOTP 应用生成的 6 位动态码。
- 通过本地蓝牙向已配对的 Android 手机发出挑战，用户在手机上确认并完成生物识别后登录。

原生 Windows Hello PIN 和密码入口仍由 Windows 管理。LivingUnlock 不注册 Credential Provider Filter，也不会关闭或替换原生登录方式。

> [!WARNING]
> 当前版本是未签名的开发原型。Credential Provider 会加载到 Windows 登录界面进程；安装前必须确认账户密码和原生 PIN 可用，并准备安全模式或恢复环境。请先在测试机或虚拟机验收。

## 当前功能

### Windows

- `ICredentialProviderCredential2` 登录磁贴，支持登录和工作站解锁场景。
- Microsoft 账户和本地账户凭据验证。
- 自动识别当前 Windows 登录账户：本地账户填入用户名，Microsoft 账户填入关联邮箱；检测失败时保留已保存身份并允许手动选择。
- TOTP 扫码或手动密钥绑定，带时间窗口、失败冷却和已用时间步防重放。
- 蓝牙 RFCOMM 挑战/响应及生物识别签名验证；电脑主动连接已配对手机，仅旧配对缺少手机地址时启用最多 30 秒的 BLE 广播兼容回退。
- 选中 LivingUnlock 后等待蓝牙设备可用通知；设备可用后开启 60 秒连接窗口，失败按 5、10、20 秒退避，最多尝试 4 次，单次最多 8 秒且受剩余窗口限制。电脑显示重连等待秒数，窗口结束后停止连接及广播，等待手动重发。
- Windows 独立执行每次挑战的 30 秒单调时钟截止校验；Android 从收到请求开始用本机单调时钟计时，不比较两端的系统时间。重发会使旧请求失效。
- 解锁前进行配对密钥认证的内部时间握手。两端显示准备状态；Android 优先取时，Windows 备用，任一端成功后共享内部时间。时间流程失败自动使用独立计时继续解锁。
- WinUI 3 管理客户端，可查看启用状态、绑定方式、禁用、重新启用和卸载单项功能。
- 侧栏底部可手动检查 GitHub Release 更新，查看说明并通过浏览器下载安装包。
- 可选的“登录时记录本次开机位置”功能，默认关闭。

### Android

- Jetpack Compose 界面，扫描 Windows 配对二维码。
- Android Keystore P-256 设备密钥；每次解锁签名均需要系统生物识别。
- 后台蓝牙监听、HyperOS/Android 浮动通知及应用内解锁申请页。
- 设置页提供解锁监听总开关与默认关闭的 BLE 兼容扫描。手机默认被动监听电脑连接，不持续重连；处理请求时才短暂持有唤醒锁。
- 点击已绑定设备查看浮动详情窗口，可设置昵称；昵称在界面中以 `昵称*` 显示。
- 设置页的“应用更新”可检查 GitHub Release，查看说明并打开正式签名 Compact APK 下载链接。
- 查看最近同步的系统、CPU、GPU、物理内存、蓝牙 MAC、连接方式、启动时间和可选登录位置快照。

### 检查更新

两端按需读取 `DDZmumo/LivingUnlock` 的公开 GitHub Releases，不需要 GitHub 账户或 Token。默认包含预发布版本，可关闭该选项只检查正式版；Windows 和 Android 各自选择含对应安装包的最高版本，跳过草稿和未完成上传的附件，版本相同或低于当前版本时不会提示升级。发布时继续使用 `v版本号` 标签、`LivingUnlock-Setup-版本号-x64.exe` 和 `LivingUnlock-Android-版本号-compact.apk` 文件名。

点击“下载安装包”会交给浏览器下载，安装由用户确认。检查更新只依据已发布的 Release，不依据 `main` 分支的新提交；调试版 Android 的包名带 `.debug`，Release APK 是独立的正式应用，不能覆盖调试版或自动迁移其配对。

## 安全模型

- Windows 密码、TOTP 密钥和状态保存在本机，使用机器范围 DPAPI 加密；生产保险库仅允许 `SYSTEM` 和 Administrators 访问。
- Android 不接收或保存 Windows 密码、PIN、DPAPI 密钥或 TOTP 密钥。手机只对绑定电脑发出的短期挑战签名。
- 手机配对记录使用 Android Keystore 加密；私钥配置为每次使用均要求生物识别。
- 配对二维码包含随机令牌和 180 秒有效期；协议字段有长度上限并采用严格解析。
- 设备详情使用配对密钥派生出的独立 AES-GCM 密钥加密，不参与登录认证。
- 管理员和 `SYSTEM` 位于 Windows 本机信任边界内。本项目不提供与 Windows Hello 硬件密钥相同的保护等级。

协议说明见 [protocol/spec-v1.md](protocol/spec-v1.md)。

## 目录结构

```text
android/             Android 应用与纯 Kotlin 协议核心
src/                 Windows Credential Provider、TOTP 与凭据保险库
windows/common/      Windows/Android 共享协议的 C++ 实现
windows/broker/      RFCOMM 服务与 BLE 广播
windows/desktop/     WinUI 3 管理客户端
windows/tools/       配对、解锁和诊断工具源码
tests/               Windows 端单元与集成测试
installer/           Inno Setup 安装脚本
protocol/            协议规范与测试向量
docs/                操作、验证和功能说明
third_party/         已保留许可证的第三方源码
```

内部仍有部分 `WindowsLockPin` 文件名、安装路径和 Android 包名。这些名称为现有安装、GUID、保险库和配对兼容性保留；用户可见产品名称为 LivingUnlock。

## 构建

### Windows Credential Provider

要求：Windows 11 x64、Visual Studio 2022 C++ Build Tools、Windows SDK `10.0.26100.0`。

```bat
tools\build.cmd
tools\test.cmd
tools\test-phone-windows.cmd
tools\test-device-info.cmd
tools\build-phone-pairing-demo.cmd
tools\build-phone-unlock-demo.cmd
```

主要输出位于本地 `build\` 目录。构建和测试脚本不会注册 Credential Provider，也不会读取真实账户密码。

### WinUI 3 管理客户端

要求：.NET 8 SDK。先生成配对工具并复制到桌面应用资源目录，再构建：

```powershell
.\tools\build-phone-pairing-demo.cmd
Copy-Item .\build\phone_pairing_demo.exe .\windows\desktop\Assets\phone_pairing_demo.exe -Force
dotnet restore .\windows\desktop\LivingUnlock.Windows.csproj
dotnet build .\windows\desktop\LivingUnlock.Windows.csproj -c Debug -p:Platform=x64
```

Windows 完整安装包可使用 `tools/Build-WindowsRelease.ps1` 构建，需要 .NET 8 SDK、C++ Build Tools 和 Inno Setup 6。
脚本全量重编译登录组件、配对工具和 WinUI 客户端，并将自包含运行时一起打包到 `dist/`，不会安装或注册登录组件。

### Android

要求：JDK 17 或更高版本、Android SDK Platform 34。将 SDK 路径写入本机的 `android\local.properties`，该文件不会提交。

```powershell
Set-Location .\android
.\gradlew.bat :core:test :app:assembleDebug
```

调试 APK 生成在 `android\app\build\outputs\apk\debug\app-debug.apk`。

发行构建使用 `:app:assembleCompact`，启用代码和资源裁剪。先在本机创建
`android/release-signing.properties`，填写 `storeFile`（相对 android 目录）、
`storePassword`、`keyAlias`、`keyPassword`，并备份对应签名密钥。配置和私钥均不提交到 Git。
发行 APK 位于 `android/app/build/outputs/apk/compact/app-compact.apk`。

正式包名为 `com.windowslockpin.companion`，Debug 包名为 `com.windowslockpin.companion.debug`。
两者可以共存，但不共享配对数据；从 Debug 转正式版需要重新配对，并关闭旧版监听，避免重复响应。

## 安装与使用

### 从旧版升级到 0.3.0

Windows 与 Android 当前版本统一为 **0.3.0**（Android `versionCode=5`）。此版本增加内部时间握手、独立计时及两端检查 GitHub 更新入口；升级不改变包名、登录 Provider GUID 或既有配对数据。以下蓝牙地址迁移规则同样适用于此前的旧版本。

同时更新 Windows 和 Android。旧配对若尚未记录手机连接地址，在 Android 的“设置 → 蓝牙连接”临时开启 BLE 兼容扫描，保持手机亮屏并完成一次指纹解锁，电脑会在验证成功后记录地址；之后可关闭兼容扫描，无需重新绑定。新版重新配对会直接记录该地址。

BLE 兼容扫描和被动监听是独立的接收方式；关闭兼容扫描不会关闭电脑主动连接。部分手机系统会暂停熄屏扫描，兼容扫描不保证熄屏响应。后台运行和通知仍受手机系统设置影响。

已保存手机地址的配对只使用电脑主动连接，失败后按上述窗口重试，不自动开启 BLE 广播。没有地址的旧配对仍需临时开启手机 BLE 兼容扫描；电脑会显示回连剩余秒数，超时后可点“再次发送”。等待蓝牙设备可用和手动重发时使用事件等待；每秒界面刷新仅发生在重连或兼容回连倒计时期间。适配器在当前请求中被移除时会结束该请求，恢复蓝牙后可手动重发。生物识别挑战的 30 秒期限独立于连接窗口，拒绝、过期或验签失败不会自动重发挑战。

配对记录增加了 v2 地址字段，旧版 Windows 不识别升级后的记录。如需回退，应恢复升级前的配对备份或重新绑定；不要直接降级后覆盖现有绑定。

蓝牙解锁不要求电脑与手机的系统时间一致，手机不会替电脑校时。Windows 仍会拒绝超过本机 30 秒期限的响应；网络传输时间不延长这个期限，连接关闭时手机同步结束当前请求。Authenticator 动态码及首次配对二维码的有效期仍依赖准确的系统时间，建议在 Windows“时间和语言 → 日期和时间”中启用自动设置时间。

### 应用内部时间

Windows 登录组件和 Android 应用启动时，各自从系统时间建立内部基准，随后按本机单调时钟推进。Windows 在后台等待可用网络，Android 在启动后尝试取时；两端启动取时各最多三次，失败保留原有基准，不改变系统时间。

解锁时先交换内部时间，计入往返延迟和误差；偏差超过 2 秒阈值时优先由 Android 取时，失败后由 Windows 接管。有效校准结果缓存 10 分钟，转发不会重置样本年龄。取时并行请求国家授时中心、阿里云、腾讯云；优先选择一致的有效授时中心样本，前三者无有效结果时回退 Windows 时间服务器。每个解锁准备阶段及取时最多重试一次，耗尽后继续独立计时；挑战在准备结束后才生成，其 30 秒期限不会因此缩短或延长。

程序只对授时流量尝试底层网络直连，不修改 VPN、TUN、DNS 或其他软件配置。如果 TUN 或系统限制绕过，则使用系统网络，以通过校验的服务器响应判断取时成功；不可用时自动降级。Android 准备界面只有取消操作，真正收到解锁挑战后才出现解锁及指纹验证入口。

Windows 桌面主页横幅右侧显示精确到秒的应用内部时间及“校准时间”按钮；Android 在扫码绑定按钮上方显示同宽时间栏和小型“校准”按钮，数字逐位上下滚动，并遵循系统关闭动画的设置。手动校准失败时保留现有内部基准，页面不可见时停止秒级刷新。Windows 桌面与登录组件通过当前用户、当前开机编号隔离的辅助缓存共享有效校准结果，样本年龄不会因共享而刷新；此缓存不用于 TOTP 验证或登录授权。Windows 登录组件仅显示时间握手、偏差校准及取时阶段提示，不显示具体时间，准备结束后进入正常解锁流程。

构建完成后，在 64 位管理员 PowerShell 中安装 Credential Provider：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Install.ps1
```

卸载使用 `tools\Uninstall.ps1`。安装或卸载前请保存工作，并确认原生 PIN 和账户密码可用；首次部署应在可恢复环境中完成。

典型流程：

1. 构建并安装 Windows Credential Provider。
2. 在 WinUI 客户端选择本地账户或 Microsoft 账户，输入账户密码并保存。
3. 选择 Authenticator 绑定，或显示 LivingUnlock Android 配对二维码。
4. 安装 Android 应用并扫描配对二维码，开启通知和附近设备权限。
5. 锁屏后选择 LivingUnlock 磁贴；可在手机上确认指纹，也可使用已绑定的 6 位动态码。
6. 任何时候都可以从 Windows“登录选项”切换回原生 PIN。

位置记录默认关闭。启用后，Windows 会请求位置权限并为当前用户创建登录启动项；每次登录只采集一次，失败时显示“未记录”，不会持续跟踪。

如果旧安装在手机指纹通过后提示找不到已保存凭据，可在管理员 PowerShell 中运行安装目录
`tools/Repair-VaultAcl.ps1` 修复系统保险库权限，再通过控制台重新保存凭据或配对。
修复工具仅处理系统保险库内符合 SID 命名规则的记录，拒绝目录联接、符号链接和硬链接，不修改记录内容。
客户端暂存成功不等于锁屏启用成功；必须完成管理员确认的发布步骤。

## 验证范围

当前仓库包含协议编码、TOTP、限流、防重放、DPAPI 保险库、RFCOMM、P-256 签名和设备信息封装测试。历史实机验证覆盖过目标 Windows 11 设备上的 TOTP 登录、Android 蓝牙挑战、生物识别和原生 PIN 回退。

实际锁屏登录、重启后登录、不同 Windows 构建、多账户、不同蓝牙适配器和设备厂商仍需分别人工验收。编译或单元测试通过不等于能够安全登录。

### GitHub CI

[CI 工作流](.github/workflows/ci.yml) 在每个 PR、推送到 `main` 和手动触发时运行：

- Windows Server 2022 / Visual Studio 2022：使用 MSVC 和 Windows SDK 构建登录 DLL、原生配置工具、配对/解锁工具与 WinUI 客户端；运行原生测试、协议/密码学/配对存储测试、设备信息/接收期限测试，以及隔离的保险库 ACL 测试。
- Ubuntu 24.04 / JDK 17：运行 Kotlin 核心和 Android 会话单元测试，构建 Debug APK。测试报告和 Debug APK 作为 Actions artifacts 保留 7 天。

托管 runner 没有蓝牙适配器，协议测试显式使用 `--no-bluetooth`，仅跳过真实适配器生命周期测试。本地 `tools/test-phone-windows.cmd` 不加参数时仍执行该测试。CI 不安装或注册登录组件，也不使用发行签名密钥；锁屏登录、蓝牙/生物识别实机行为和耗电仍需人工验证。本地 Android 构建通过 `JAVA_HOME` 选择 JDK，不在仓库中固定机器路径。

## 项目状态

- 支持平台：Windows 11 x64；Android 9（API 28）及以上。
- 当前 Android 版本：`0.2.1`；Windows 安装器及 WinUI 客户端版本：`0.2.3`。
- Android Compact 使用独立发行密钥签名；Windows 安装包尚无 Authenticode 签名。
- 安装包通过 GitHub Releases 发布，不在源码仓库中提交 APK、EXE、DLL。
- 当前仓库未声明开源许可证；第三方组件许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
