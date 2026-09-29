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
- TOTP 扫码或手动密钥绑定，带时间窗口、失败冷却和已用时间步防重放。
- 蓝牙 RFCOMM 挑战/响应、生物识别签名验证、BLE 锁屏唤醒广播。
- 每次挑战使用 30 秒绝对截止时间；重发会使旧请求失效。
- WinUI 3 管理客户端，可查看启用状态、绑定方式、禁用、重新启用和卸载单项功能。
- 可选的“登录时记录本次开机位置”功能，默认关闭。

### Android

- Jetpack Compose 界面，扫描 Windows 配对二维码。
- Android Keystore P-256 设备密钥；每次解锁签名均需要系统生物识别。
- 后台蓝牙监听、HyperOS/Android 浮动通知及应用内解锁申请页。
- 点击已绑定设备查看浮动详情窗口，可设置昵称；昵称在界面中以 `昵称*` 显示。
- 查看最近同步的系统、CPU、GPU、物理内存、蓝牙 MAC、连接方式、启动时间和可选登录位置快照。

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

## 项目状态

- 支持平台：Windows 11 x64；Android 9（API 28）及以上。
- 当前 Android 和 Windows 安装器版本：`0.2.1`。
- Android Compact 使用独立发行密钥签名；Windows 安装包尚无 Authenticode 签名。
- 安装包通过 GitHub Releases 发布，不在源码仓库中提交 APK、EXE、DLL。
- 当前仓库未声明开源许可证；第三方组件许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
