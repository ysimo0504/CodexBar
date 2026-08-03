# CodexBar Ink

> CodexBar 的 Android 与 BOOX 墨水屏伴侣仪表盘。

[English](README.md) | [简体中文](README.zh-CN.md)

[![Fork](https://img.shields.io/badge/fork-steipete%2FCodexBar-0a0a0c?style=flat-square)](https://github.com/steipete/CodexBar)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](Android/CodexBarInk)
[![BOOX](https://img.shields.io/badge/BOOX-e--ink-171717?style=flat-square)](Android/CodexBarInk)
[![License: MIT](https://img.shields.io/badge/license-MIT-6e5aff?style=flat-square)](LICENSE)

CodexBar Ink 是 [CodexBar](https://github.com/steipete/CodexBar) 的社区 Fork。它保留上游的 macOS 菜单栏应用和
CLI，并增加面向 Android 手机与 BOOX 墨水屏阅读器的常亮信息仪表盘。

阅读器通过私有局域网从 Mac 上的 CodexBar 获取面向展示的用量快照。Provider 凭据始终保留在 Mac 上；
CodexBar Ink 不会在 Android 端保存 Codex、Claude 或其他 Provider 的密码、Cookie 或 API Token。

> CodexBar Ink 仍在开发中。目前没有签名 Release APK，请从源码构建。

## CodexBar Ink 功能

- 展示 Provider 用量窗口，包括 Codex Weekly 与 Spark Weekly 限额。
- 原生绘制每日用量和成本图表，不使用截图快照。
- 支持手机与阅读器比例、竖屏与横屏布局，以及 BOOX 专用刷新行为。
- 面向墨水屏使用高对比度排版、局部刷新和低频更新。
- 内置专注计时器，运行时可以保持屏幕常亮。
- 天气来自 Open-Meteo：优先使用设备 GPS，不可用时回退到网络定位，无需 API Key。
- 跟随 Android 系统语言，目前包含英文和简体中文。

## 快速开始

### 环境要求

- 运行 CodexBar 的 Mac（macOS 14 或更高版本）。
- Android 11 或更高版本的设备。
- JDK 17、Android SDK 和 `adb`。
- Mac 与 Android 设备处于同一个私有局域网。

### 1. 启动 Mac Usage Host

在 CodexBar 中打开 **Settings > General**，启用 **BOOX Usage Host**。Mac 与阅读器应处于同一个可信的私有网络。
Usage Host 只用于局域网，不要进行端口转发，也不要暴露到公网。

### 2. 构建 Android APK

```bash
git clone https://github.com/ysimo0504/CodexBar.git
cd CodexBar/Android/CodexBarInk

JAVA_HOME=/path/to/jdk17 \
ANDROID_HOME=/path/to/android-sdk \
CODEXBAR_INK_DEFAULT_HOST=http://MAC_LAN_IP:43121 \
./gradlew :app:assembleSecureBooxDebug
```

普通 Android 手机不需要 BOOX 显示 API 时，使用：

```bash
./gradlew :app:assembleSecureGenericDebug
```

`CODEXBAR_INK_DEFAULT_HOST` 只是本地构建输入，不会写回仓库；也可以在应用内修改 Host。BOOX APK 输出路径为：

```text
app/build/outputs/apk/secureBoox/debug/app-secure-boox-debug.apk
```

### 3. 安装

```bash
adb install -r app/build/outputs/apk/secureBoox/debug/app-secure-boox-debug.apk
```

如果 BOOX 固件会冻结新安装的第三方应用，请先在 BOOX 应用管理中解除 CodexBar Ink 的冻结。构建变体、测试、
fixture server 和设备安装说明见 [Android/CodexBarInk/README.md](Android/CodexBarInk/README.md)。

### Android 项目结构

```text
Android/CodexBarInk/       Android 与 BOOX 阅读器
Sources/CodexBar/          macOS 菜单栏应用与 Usage Host
Sources/CodexBarCLI/       CLI 与仪表盘快照生成
Sources/CodexBarCore/Ink/  局域网传输与快照边界
```

阅读器使用版本化的 `GET /dashboard/v1/snapshot` 展示协议。快照结构以及 CLI `serve` 的传输与认证模型见
[docs/dashboard-api.md](docs/dashboard-api.md)。

## macOS 版 CodexBar

> 在菜单栏查看每个 AI 编程服务的用量限额。

[![Latest release](https://img.shields.io/github/v/release/steipete/CodexBar?style=flat-square&color=0a0a0c)](https://github.com/steipete/CodexBar/releases/latest)
[![macOS 14+](https://img.shields.io/badge/macOS-14%2B-0a0a0c?style=flat-square)](https://github.com/steipete/CodexBar/releases/latest)
[![Homebrew](https://img.shields.io/badge/brew-steipete%2Ftap%2Fcodexbar-orange?style=flat-square)](https://github.com/steipete/homebrew-tap)
[![AUR](https://img.shields.io/aur/version/codexbar-cli?style=flat-square&color=1793d1)](https://aur.archlinux.org/packages/codexbar-cli)
[![Site](https://img.shields.io/badge/site-codexbar.app-16d3b4?style=flat-square)](https://codexbar.app)

<a href="https://codexbar.app"><img src="docs/social.png" alt="CodexBar - 在菜单栏查看 AI 编程用量" width="100%" /></a>

CodexBar 是一个轻量的 macOS 14+ 菜单栏应用，用于显示 AI 编程 Provider 的用量限额和各窗口的重置时间。它不显示
Dock 图标，使用简洁的弹出面板、动态用量指示器，并支持每个 Provider 一个状态项，或使用带 Provider 切换器的
Merge Icons 单状态项模式。

<img src="docs/codexbar.png" alt="CodexBar 菜单弹出面板，显示 Provider 卡片、用量条和重置倒计时" width="520" />

### 为什么使用 CodexBar

- **围绕重置时间安排工作。** 跟踪 Session、Weekly 和 Monthly 窗口，并显示距离下次重置的倒计时。
- **查看积分与支出。** 在 Provider 提供数据时显示积分余额、账单摘要、Admin API 支出面板和本地成本扫描结果。
- **及时发现服务异常。** 可选的状态轮询会在菜单中显示事件标记，并在菜单栏图标上显示状态覆盖层。
- **凭据留在本机。** CodexBar 复用支持的 OAuth/设备登录会话、API Key、浏览器 Cookie、本地文件和 Provider CLI，
  不保存密码。

### 安装 CodexBar

#### GitHub Releases

从 <https://github.com/steipete/CodexBar/releases> 下载 macOS 应用或 CLI 资源。

#### Homebrew

```bash
brew install --cask codexbar
```

#### macOS 和 Linux CLI

Homebrew：

```bash
brew install steipete/tap/codexbar
```

Arch Linux：

```bash
yay -S codexbar-cli
```

GitHub Releases 还提供以下架构的 tarball：

- macOS：`arm64` 和 `x86_64`
- Linux glibc：`aarch64` 和 `x86_64`
- Linux static musl：`aarch64` 和 `x86_64`

#### 首次运行

1. 打开 **Settings > Providers**，启用需要的 Provider。
2. 登录这些 Provider 所需的数据源：CLI、浏览器会话、OAuth/设备登录、API Key、本地应用文件或 Provider 应用。
3. 如需额外的网页仪表盘数据，可配置 **Settings > Providers > Codex > OpenAI cookies**。

#### 使用 CLI 配置 Provider

Provider 开关和 API Key 保存在 CodexBar 解析后的配置文件中。新安装使用
`~/.config/codexbar/config.json`；已有的 `~/.codexbar/config.json` 安装仍会读取旧路径。

```bash
codexbar config providers
codexbar config enable --provider grok
codexbar config disable --provider cursor
```

对于 API-Key Provider，可以不打开 Settings 直接写入 Key：

```bash
printf '%s' "$ELEVENLABS_API_KEY" | codexbar config set-api-key --provider elevenlabs --stdin
```

`set-api-key` 会清理管道输入两端的空白，使用受限的配置文件权限，并默认启用该 Provider。只保存但不启用时使用
`--no-enable`。完整流程见 [CLI configuration](docs/cli-configuration.md)。

### Provider 覆盖范围

CodexBar 支持持续增长的 Provider 集成，包括：

- 编程助手与订阅服务：Codex、OpenAI、Azure OpenAI、Claude、ClinePass、Cursor、OpenCode、OpenCode Go、Gemini、
  Antigravity、Copilot、Devin、Kiro、Augment、JetBrains AI、Kimi、Kilo、MiniMax、Manus、Qoder、StepFun、Grok、
  LongCat、ai& 和 Factory/Droid。
- API、积分与账单：z.ai、Alibaba Coding Plan、Alibaba Token Plan、Vertex AI、Amp、T3 Chat、Ollama、Synthetic、
  Warp、OpenRouter、ElevenLabs、Windsurf、Zed、Perplexity、Xiaomi MiMo、Doubao、Sakana AI、Abacus AI、Mistral、
  DeepSeek、DeepInfra、Moonshot / Kimi API、Codebuff、Crof、Venice、Command Code、Poe、Chutes、Neuralwatt 和
  GroqCloud。
- 网关与基础设施：AWS Bedrock、LLM Proxy、LiteLLM、ClawRouter、sub2api、Wayfinder、ZenMux 和 Deepgram。

每个 Provider 的认证方式和数据来源不同：有些使用 OAuth 或 CLI，有些使用 API Key，也有些使用浏览器 Cookie 或
本地应用数据。请先阅读 [Provider 概览](docs/providers.md)，再查看 `docs/` 下对应的 Provider 文档。新增集成可参考
[Provider authoring guide](docs/provider.md)。

### 功能

- 每个 Provider 的用量条和重置倒计时。
- 可选的 Codex 网页仪表盘数据，包括代码审查剩余额度、用量明细和积分历史。
- 为支持的 API Provider 提供内置支出与用量图表。
- 本地 7/30 天 Usage & Spend 估算，按原生货币分组。
- Merge Icons 模式、Provider 切换器、可配置的标签/图标/用量条，以及最高用量自动选择。
- Adaptive 刷新，以及手动、1 分钟、2 分钟、5 分钟、15 分钟和 30 分钟固定间隔。
- 内置 `codexbar` CLI，适用于脚本和 CI，并提供 macOS 与 Linux 构建。
- WidgetKit 小组件、多语言应用和网站、可选 Session 配额通知，以及每周重置彩蛋。
- 默认在本机解析数据。浏览器 Cookie 导入是可选的，并会复用已有 Cookie；不会保存密码。

### 隐私与 macOS 权限

CodexBar 不会扫描整个文件系统。启用的功能需要本地数据时，只读取有限的已知位置，例如浏览器 Cookie/本地存储、
Provider 配置文件或本地 JSONL 日志。普通 Adaptive 刷新不会检查本地 Agent 活动。单独的 Agent-aware Adaptive 选项
会在检查运行中进程列表和有限的会话元数据前询问用户；拒绝后会回到普通 Adaptive。

- **完全磁盘访问权限是可选的**，只在基于网页的 Provider 需要读取 Safari Cookie/本地存储时使用。
- **Keychain 访问**可能用于浏览器 Safe Storage、缓存的 Cookie Header，以及 OAuth/设备登录凭据。
- **文件与文件夹权限提示**可能来自 Provider CLI 或在项目目录/外置卷中运行的本地探针，这不是后台磁盘扫描。
- CodexBar 不会在后台请求屏幕录制或辅助功能权限。用户主动触发的辅助操作可能请求 Automation 权限来打开终端。

安全排查和支持报告说明见 [Keychain prompt troubleshooting](docs/keychain-prompts.md)。

### 文档

- [Provider 概览](docs/providers.md)
- [CLI 参考](docs/cli.md) 与 [CLI 配置](docs/cli-configuration.md)
- [配置说明](docs/configuration.md)
- [Dashboard Snapshot API](docs/dashboard-api.md)
- [Widget](docs/widgets.md)
- [架构](docs/architecture.md)
- [刷新循环](docs/refresh-loop.md) 与 [状态轮询](docs/status.md)
- [开发指南](docs/DEVELOPMENT.md)
- [打包](docs/packaging.md) 与 [发布清单](docs/RELEASING.md)
- [变更日志](CHANGELOG.md)

### 从源码构建

Swift Package 需要 macOS 14+ 和 Swift 6.2+。

```bash
# 构建并使用 ad-hoc 签名打包 CodexBar.app
./Scripts/package_app.sh
open CodexBar.app

# 开发循环：构建、打包、重启并确认应用运行
./Scripts/compile_and_run.sh

# 打包和重启前运行分片测试套件
./Scripts/compile_and_run.sh --test

# 运行格式化与 lint 检查
make check
```

仅构建 CLI 时使用 `swift build`。完整流程见 [开发指南](docs/DEVELOPMENT.md)。

## 相关项目

- [Win-CodexBar](https://github.com/Finesssee/Win-CodexBar) - Windows 伴侣项目。
- [codexbar-waybar](https://github.com/Marouan-chak/codexbar-waybar) - Waybar 集成。
- [Codexbar GNOME](https://extensions.gnome.org/extension/9841/codexbar/) - GNOME Shell 扩展。
- [codexbar-cinnamon-applet](https://github.com/jacobcalvert/codexbar-cinnamon-applet) - Cinnamon 面板小程序。
- [noctalia-codex-usage](https://github.com/rayoplateado/noctalia-codex-usage) - Noctalia/Quickshell 插件。
- [KodexBar](https://github.com/tylxr59/KodexBar) - KDE Plasma 小组件。
- [codexbar-plasmoid](https://github.com/psimaker/codexbar-plasmoid) - KDE Plasma 6 小组件。
- [showy-quota](https://github.com/enieuwy/showy-quota) - SketchyBar、tmux 和 Zellij 配额条。

## 致谢与许可证

上游项目由 Peter Steinberger（[steipete](https://twitter.com/steipete)）维护，使用 [MIT License](LICENSE)。本 Fork
保留上游版权声明，同样使用 MIT License。

CodexBar 的成本追踪功能受到 [ccusage](https://github.com/ryoppippi/ccusage)（MIT）的启发。
