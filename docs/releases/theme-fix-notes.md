# Windows 控制台部分文字变黑 — 根因与修复

**报告日期**：2026-09-30
**影响版本**：v0.2.2 及此前全部版本（`App.xaml` 自首个提交 `01e69b1` 起从未改动）
**报告人设备**：Windows 11，系统「默认应用模式」= 浅色（「选择模式」= 自定义）

## 一、现象

控制台主页中**部分**文字显示为黑色，压在深色卡面/深蓝 Hero 上几乎不可读。具体为：

- Hero 区：「本机安全通道已就绪」徽标、「LivingUnlock 控制台」大标题、`GLCOGE` / `21119` 徽标
- 卡片小标题：「账户绑定准备」「Authenticator 动态码」「LivingUnlock Android」
- 侧边栏：「LivingUnlock」品牌名、「本机保护服务」及三个导航项文字

**副标题、Caption 类文字显示正常** —— 这个「只坏一部分」的特征是定位问题的关键线索。

## 二、根因

### 2.1 应用从未声明主题

`windows/desktop/App.xaml` 的 `<Application>` 节点**没有 `RequestedTheme` 属性**，全项目搜索 `RequestedTheme` / `ElementTheme` / `ApplicationTheme` 零命中。

微软官方文档（[Windows 应用中的主题](https://learn.microsoft.com/windows/apps/develop/ui/theming)）明确说明：

> 移除 `RequestedTheme` 属性意味着应用程序将使用用户的系统设置。

因此控制台实际**跟随系统的「默认应用模式」**，而不是它设计上假定的深色。

### 2.2 浅色模式下，默认前景色解析为黑色

WinUI 3 的主题 token 在两套主题下取值不同：

| Token | 深色主题 | 浅色主题 |
|---|---|---|
| `text_primary`（TextFillColorPrimary） | `#ffffff` | `#e3000000` ← 近黑 |
| `accent_fill_default` | `#4cc2ff` | `#0078d4` |

**未显式设置 `Foreground` 的 `TextBlock`** 会使用 `text_primary`。深色模式下它是白色（恰好与深色卡面匹配，缺陷被掩盖）；浅色模式下变成近黑色，压在深色卡面上即成为黑色文字。

### 2.3 「只坏一部分」的精确解释

`App.xaml` 中定义了样式并**显式绑定**前景色：

- `CaptionStyle` → `MutedTextBrush` = `#9EADBF`
- `SectionTitleStyle` / `PageTitleStyle` / `BodyTextBlockStyle` → `TextBrush` = `#F3F8FF`

这些**不随主题变化**，所以正常。而下列文字是裸 `TextBlock`，既无 `Style` 也无 `Foreground`，于是跟随主题变黑：

| 文件 | 行号 | 文字 |
|---|---|---|
| `MainWindow.xaml` | 32 / 60 / 95 | 品牌名、导航项文字、「本机保护服务」 |
| `HomePage.xaml` | 41 / 44 / 49 / 52 | Hero 徽标、大标题、电脑名/用户名徽标 |
| `HomePage.xaml` | 107 / 109 / 135 / 149 / 187 | 卡片与小标题 |
| `AuthenticatorPage.xaml` | 38 / 54 / 86 / 87 | 标题与提示文字 |
| `LivingUnlockPairPage.xaml` | 31 / 71 / 100 | 标题与提示文字 |

> `HomePage.xaml:109`（`AccountStateText`）和 `:187` 实际带 `Style="{StaticResource CaptionStyle}"`，不受影响；上表按「无 Foreground」机械筛出，此处列全以便复核。

### 2.4 为什么此前无人复现

不是设备差异，也不是版本差异。**差别只在系统主题设置**：

- 主开发者与其他用户的机器为深色应用模式 → 默认前景是白色 → 恰好正常
- 报告人机器为浅色应用模式 → 默认前景是黑色 → 缺陷暴露

这类缺陷的隐蔽性正在于此：**它在开发环境天然不可见**。

## 三、修复

### 3.1 采用方案：应用级强制深色

```xml
<Application
    x:Class="LivingUnlock.Windows.App"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
    RequestedTheme="Dark">   <!-- 新增 -->
```

**理由**：App.xaml 中所有颜色资源均为固定深色值（`LivingShell` `#172330`、`LivingPanel` `#292D32` 等），说明该界面**设计意图就是固定深色**，只是遗漏了主题约束。该改动：

- 一行覆盖全部 30+ 处漏色文字，无需逐一补 `Foreground`
- 同时修正 `ToggleSwitch`（`HomePage.xaml:195`）等**依赖控件模板默认色**的元素
- `ContentDialog`（删除凭证确认）挂在 `XamlRoot` 上，应用级设置可确保它一并覆盖

**副作用**：控制台固定深色，不再跟随系统浅色。对这款全深色设计的应用而言，这反而更一致。

### 3.2 备选方案

| 方案 | 做法 | 评价 |
|---|---|---|
| 页面根节点强制 | `WindowRoot` + 各 Page 设 `RequestedTheme="Dark"` | 需改 4 处；`ContentDialog` 可能漏 |
| 逐处补 `Foreground` | 给约 20 处 `TextBlock` 显式指定颜色 | 啰嗦易漏，且无法覆盖 `ToggleSwitch` 等控件模板 |
| 新增样式 | 补 `StrongTitleStyle` 等绑定 `TextBrush` | 治本但改动面大，可作后续重构 |

### 3.3 后续建议

1. **补全样式体系**：那批卡片小标题本应复用样式（绑定 `TextBrush`），而非裸 `TextBlock`。本次问题的本质是**样式覆盖不全**。
2. **构建期防护**：可加静态检查，拒绝在 `App.Resources` 之外出现无 `Foreground`/`Style` 的 `TextBlock`。
3. **验收补充**：在浅色与深色两种应用模式下各跑一遍 UI 走查。

## 四、验证状态

| 项目 | 状态 |
|---|---|
| 静态分析定位根因 | ✅ 完成（全项目 grep + 官方文档交叉验证） |
| XML 合法性校验 | ✅ 通过（5 个 XAML 文件均可解析） |
| 本机编译验证 | ⚠️ **未完成** —— 本机无独立 .NET SDK，VS 2022 仅含 runtime；MSBuild.exe 被沙箱安全策略拦截 |
| 实机 UI 验证 | ⏸️ 交由用户在目标设备完成 |

> 编译验证需在具备 .NET 8 SDK 的环境执行：
> `dotnet publish windows/desktop/LivingUnlock.Windows.csproj -c Release -p:Platform=x64 -r win-x64 --self-contained true`
> 或直接运行 `tools/Build-WindowsRelease.ps1`。
