# CodexBar Ink

> An Android and BOOX e-ink companion dashboard for CodexBar.

[English](README.md) | [简体中文](README.zh-CN.md)

[![Fork](https://img.shields.io/badge/fork-steipete%2FCodexBar-0a0a0c?style=flat-square)](https://github.com/steipete/CodexBar)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](Android/CodexBarInk)
[![BOOX](https://img.shields.io/badge/BOOX-e--ink-171717?style=flat-square)](Android/CodexBarInk)
[![License: MIT](https://img.shields.io/badge/license-MIT-6e5aff?style=flat-square)](LICENSE)

CodexBar Ink is a community fork of [CodexBar](https://github.com/steipete/CodexBar). It keeps the upstream macOS
menu bar app and CLI, and adds an always-on Android dashboard for phones and BOOX e-ink readers.

The reader fetches a display-oriented usage snapshot from CodexBar over the private LAN. Provider credentials stay on
the Mac; CodexBar Ink does not store Codex, Claude, or other provider passwords, cookies, or API tokens on Android.

> CodexBar Ink is still under development. There is currently no signed release APK; build it from source.

## CodexBar Ink

- Shows provider usage windows, including Codex weekly and Spark weekly limits.
- Draws daily usage and cost charts natively instead of using screenshot snapshots.
- Supports phone and reader aspect ratios, portrait and landscape layouts, and BOOX-specific refresh behavior.
- Uses high-contrast typography, partial refresh, and low-frequency updates for e-ink displays.
- Includes a focus timer that can keep the display awake while it is running.
- Shows weather from Open-Meteo, using device GPS when available and network location as a fallback. No API key is
  required.
- Follows the Android system language, with English and Simplified Chinese currently included.

## Quick start

### Requirements

- A Mac running CodexBar (macOS 14 or later).
- An Android 11 or later device.
- JDK 17, Android SDK, and `adb`.
- The Mac and Android device on the same private LAN.

### 1. Start the Mac Usage Host

In CodexBar, open **Settings > General** and enable **BOOX Usage Host**. Keep the Mac and reader on the same trusted
private network. The host is intended for local network use only; do not port-forward it or expose it to the public
Internet.

### 2. Build the Android APK

```bash
git clone https://github.com/ysimo0504/CodexBar.git
cd CodexBar/Android/CodexBarInk

JAVA_HOME=/path/to/jdk17 \
ANDROID_HOME=/path/to/android-sdk \
CODEXBAR_INK_DEFAULT_HOST=http://MAC_LAN_IP:43121 \
./gradlew :app:assembleSecureBooxDebug
```

For a standard Android phone without BOOX display APIs, use:

```bash
./gradlew :app:assembleSecureGenericDebug
```

`CODEXBAR_INK_DEFAULT_HOST` is a local build input and is not written back to the repository. You can also change the
host inside the app. The BOOX APK is written to:

```text
app/build/outputs/apk/secureBoox/debug/app-secure-boox-debug.apk
```

### 3. Install

```bash
adb install -r app/build/outputs/apk/secureBoox/debug/app-secure-boox-debug.apk
```

If BOOX firmware freezes newly installed third-party apps, unfreeze CodexBar Ink in BOOX App Management before
launching it. See [Android/CodexBarInk/README.md](Android/CodexBarInk/README.md) for build variants, tests, fixture
servers, and device-specific install notes.

### Android project layout

```text
Android/CodexBarInk/       Android and BOOX reader
Sources/CodexBar/          macOS menu bar app and Usage Host
Sources/CodexBarCLI/       CLI and dashboard snapshot generation
Sources/CodexBarCore/Ink/  private-LAN transport and snapshot boundary
```

The reader uses the versioned `GET /dashboard/v1/snapshot` display contract. See [docs/dashboard-api.md](docs/dashboard-api.md)
for the snapshot schema and the CLI `serve` transport/auth model.

## CodexBar for macOS

> Every AI coding limit, in your menu bar.

[![Latest release](https://img.shields.io/github/v/release/steipete/CodexBar?style=flat-square&color=0a0a0c)](https://github.com/steipete/CodexBar/releases/latest)
[![macOS 14+](https://img.shields.io/badge/macOS-14%2B-0a0a0c?style=flat-square)](https://github.com/steipete/CodexBar/releases/latest)
[![Homebrew](https://img.shields.io/badge/brew-steipete%2Ftap%2Fcodexbar-orange?style=flat-square)](https://github.com/steipete/homebrew-tap)
[![AUR](https://img.shields.io/aur/version/codexbar-cli?style=flat-square&color=1793d1)](https://aur.archlinux.org/packages/codexbar-cli)
[![Site](https://img.shields.io/badge/site-codexbar.app-16d3b4?style=flat-square)](https://codexbar.app)

<a href="https://codexbar.app"><img src="docs/social.png" alt="CodexBar — every AI coding limit in your menu bar. 69 providers." width="100%" /></a>

CodexBar is a small macOS 14+ menu bar app that keeps AI coding-provider limits visible and shows when each usage
window resets. It has no Dock icon, a minimal popover, dynamic usage meters, and either one status item per provider
or a single Merge Icons item with a provider switcher.

<img src="docs/codexbar.png" alt="CodexBar menu popover with provider tiles, usage bars, and reset countdowns" width="520" />

### Why CodexBar

- **Plan around resets.** Track session, weekly, and monthly windows with countdowns to the next reset.
- **See credits and spend.** View credit balances, provider billing summaries, Admin API spend dashboards, and local
  cost scans where the provider exposes enough data.
- **Know when a provider is having trouble.** Optional status polling adds incident badges to the menu and an overlay
  to the menu bar icon.
- **Keep credentials local.** CodexBar reuses supported OAuth/device-flow sessions, API keys, browser cookies, local
  files, and provider CLIs. It does not store passwords.

### Install CodexBar

#### GitHub Releases

Download the macOS app or CLI assets from <https://github.com/steipete/CodexBar/releases>.

#### Homebrew

```bash
brew install --cask codexbar
```

#### CLI on macOS and Linux

Homebrew:

```bash
brew install steipete/tap/codexbar
```

Arch Linux:

```bash
yay -S codexbar-cli
```

Release tarballs are also available for:

- macOS: `arm64` and `x86_64`
- Linux glibc: `aarch64` and `x86_64`
- Linux static musl: `aarch64` and `x86_64`

#### First run

1. Open **Settings > Providers** and enable the providers you use.
2. Sign in to the sources those providers require: CLIs, browser sessions, OAuth/device flow, API keys, local app
   files, or provider apps.
3. Optionally configure **Settings > Providers > Codex > OpenAI cookies** for additional web dashboard data.

#### Configure providers from the CLI

Provider toggles and API keys are stored in the resolved CodexBar config file. New installs use
`~/.config/codexbar/config.json`; existing installs using `~/.codexbar/config.json` continue to load the legacy path.

```bash
codexbar config providers
codexbar config enable --provider grok
codexbar config disable --provider cursor
```

For API-key providers, set a key without opening Settings:

```bash
printf '%s' "$ELEVENLABS_API_KEY" | codexbar config set-api-key --provider elevenlabs --stdin
```

`set-api-key` trims the piped value, uses restrictive config-file permissions, and enables the provider by default.
Use `--no-enable` to save the key without enabling the provider. See [CLI configuration](docs/cli-configuration.md)
for the complete flow.

### Provider coverage

CodexBar currently supports 69 provider IDs, and the integration list continues to grow:

- [Codex](docs/codex.md) — OAuth API or local Codex CLI, plus optional OpenAI web dashboard extras.
- [OpenAI](docs/openai.md) — Admin API key usage/cost graphs with legacy credit-balance fallback.
- [Azure OpenAI](docs/azure-openai.md) — API key, endpoint, and deployment validation probe.
- [Claude](docs/claude.md) — OAuth API, browser cookies, or CLI PTY fallback; session and weekly usage where available.
- [Cursor](docs/cursor.md) — Browser session cookies for plan + usage + billing resets.
- [OpenCode](docs/opencode.md) — Browser cookies for workspace subscription usage.
- [OpenCode Go](docs/opencode.md) — Usage API, browser fallback, and local SQLite cost history.
- [Alibaba Coding Plan](docs/alibaba-coding-plan.md) — Web cookies or API key for coding-plan quotas.
- [Alibaba Token Plan](docs/alibaba-token-plan.md) — Bailian browser/manual cookies for token-plan credits.
- [Qwen Cloud](docs/qwen-cloud.md) — 5-hour and weekly individual Token Plan usage via browser/manual cookies.
- [Gemini](docs/gemini.md) — OAuth-backed quota API using Gemini CLI credentials (no browser cookies).
- [Antigravity](docs/antigravity.md) — Local language server probe, `agy` CLI HTTPS source, and Google OAuth fallback (experimental).
- [Droid](docs/factory.md) — Browser cookies + WorkOS token flows for Factory usage + billing.
- [Copilot](docs/copilot.md) — GitHub device flow + Copilot internal usage API.
- [Devin](docs/devin.md) — Chrome localStorage session or manual Bearer token for daily and weekly quotas.
- [z.ai](docs/zai.md) — API token for personal/team quota, MCP, 5-hour, and hourly usage windows.
- [Manus](docs/manus.md) — Browser `session_id` auth for credit balance, monthly credits, and daily refresh tracking.
- [MiniMax](docs/minimax.md) — API token, cookie header, or browser cookies for coding-plan usage.
- [T3 Chat](docs/t3chat.md) — Browser cookies capture for Base and Overage usage buckets.
- [ZoomMate](docs/zoommate.md) — Chrome cookie auto-import or manual cURL capture for credits usage.
- [Kimi](docs/kimi.md) — Auth token (JWT from `kimi-auth` cookie) for weekly quota + 5‑hour rate limit.
- [Kilo](docs/kilo.md) — API token with CLI-auth fallback for Kilo Pass usage.
- [Kiro](docs/kiro.md) — CLI-based usage; monthly credits + bonus credits.
- [Vertex AI](docs/vertexai.md) — Google Cloud gcloud OAuth with token cost tracking from local Claude logs.
- [Augment](docs/augment.md) — Augment CLI or browser cookies for credits tracking and usage monitoring.
- [Amp](docs/amp.md) — Browser cookie-based authentication with Amp Free usage tracking.
- [Ollama](docs/ollama.md) — API key access plus browser cookies for Ollama Cloud usage windows.
- [Synthetic](docs/synthetic.md) — API key quota endpoint for rolling five-hour, weekly token, and search-hourly usage.
- [JetBrains AI](docs/jetbrains.md) — Local XML-based quota from JetBrains IDE configuration; monthly credits tracking.
- [Warp](docs/warp.md) — API token for GraphQL request limits and monthly credits.
- [ElevenLabs](docs/elevenlabs.md) — API key for character credits and voice slot usage.
- [OpenRouter](docs/openrouter.md) — API token for credit-based usage tracking across multiple AI providers.
- [Windsurf](docs/windsurf.md) — Browser localStorage session import or local SQLite cache for plan usage.
- [Zed](docs/zed.md) — Zed editor Keychain session for plan, edit-prediction quota, billing cycle, and overdue invoices.
- [Perplexity](docs/perplexity.md) — Account usage credits from Perplexity usage data.
- [Xiaomi MiMo](docs/mimo.md) — Browser cookies for balance and token-plan usage.
- [Doubao](docs/doubao.md) — API key for Volcengine Ark request-limit probes.
- [Sakana AI](docs/sakana.md) — Manual Cookie header for 5-hour and weekly quota windows.
- [Abacus AI](docs/abacus.md) — Browser cookie auth for ChatLLM/RouteLLM compute credit tracking.
- [Mistral](docs/mistral.md) — Browser cookies for API spend, credit balance, and monthly-plan usage.
- [DeepSeek](docs/deepseek.md) — API key for credit balance tracking (paid vs. granted breakdown).
- [Fireworks](docs/fireworks.md) — API key + account slug for 30-day spend from the billing summary API.
- [DeepInfra](docs/deepinfra.md) — API key for prepaid balance, current-month spend, and spending-limit tracking.
- [Moonshot / Kimi API](docs/moonshot.md) — API key for Moonshot/Kimi API account balance tracking.
- [Venice](docs/venice.md) — API key for DIEM or USD balance tracking.
- [Codebuff](docs/codebuff.md) — API token (or `~/.config/manicode/credentials.json`) for credit balance + weekly rate limit.
- [Crof](docs/crof.md) — API key for dollar credit balance and optional request quota tracking.
- [Command Code](docs/command-code.md) — Browser or manual cookies for monthly USD credits from Command Code billing.
- [Qoder](docs/qoder.md) — Browser or manual cookies for Qoder big model credit usage.
- [StepFun](docs/stepfun.md) — Username + password login for Step Plan rate limits (5‑hour + weekly windows) and subscription plan name.
- [AWS Bedrock](docs/bedrock.md) — AWS access keys or a named AWS profile (SSO/assume-role via the AWS CLI) for Cost Explorer spend, monthly budgets, and optional CloudWatch Claude activity.
- [Grok](docs/grok.md) — Grok CLI billing RPC plus grok.com browser-session fallback.
- [GroqCloud](docs/groqcloud.md) — API key for Enterprise Prometheus request/token/cache-hit metrics.
- [LLM Proxy](docs/llm-proxy.md) — API key + base URL for aggregate proxy quota stats and provider breakdowns.
- [ClawRouter](docs/clawrouter.md) — API key for monthly budget, spend, requests, tokens, and routed-provider usage.
- [sub2api](docs/sub2api.md) — Self-hosted gateway key quota, subscription limits, wallet balance, and per-key usage.
- [Wayfinder](docs/wayfinder.md) — Local router gateway polling for health, per-route breakdown, savings, and decision latency.
- [LiteLLM](docs/litellm.md) — Virtual key + proxy URL for personal and team budget/spend tracking.
- [Deepgram](docs/deepgram.md) — API key usage summaries across speech, agent, token, and TTS metrics.
- [Poe](docs/poe.md) — API key for current point balance and recent points history.
- [Chutes](docs/chutes.md) — API key for subscription usage, rolling and monthly quota windows, and pay-as-you-go quotas.
- [Neuralwatt](docs/neuralwatt.md) — API key for subscription kWh usage and prepaid credit balance.
- [ZenMux](docs/zenmux.md) — Management API key for rolling five-hour and seven-day quota windows plus PAYG balance.
- [xAI](docs/xai.md) — Management API key + team ID for prepaid credit balance and daily platform spend.
- [IBM Bob](docs/ibm-bob.md) — API key for monthly Bobcoin budget and usage across subscription teams.
- Open to new providers: [provider authoring guide](docs/provider.md).

Authentication and data sources are provider-specific. Some use OAuth or a CLI, some use an API key, and some use
browser cookies or local app data. Start with the [provider overview](docs/providers.md), then open the individual
provider guide under `docs/`. New integrations can follow the [provider authoring guide](docs/provider.md).

### Features

- Per-provider usage meters and reset countdowns.
- Optional Codex web dashboard data, including code-review remaining, usage breakdowns, and credits history.
- Inline spend and usage charts for supported API-backed providers.
- Local 7/30-day Usage & Spend estimates, grouped by native currency.
- Merge Icons mode, provider switchers, configurable labels/icons/bars, and highest-usage auto-selection.
- Adaptive refresh plus manual and fixed 1m, 2m, 5m, 15m, and 30m intervals.
- Bundled `codexbar` CLI for scripts and CI, with macOS and Linux builds.
- WidgetKit widgets, localized app and website, optional session-quota notifications, and weekly-reset confetti.
- On-device parsing by default. Browser-cookie imports are opt-in and reused; passwords are not stored.

### Privacy and macOS permissions

CodexBar does not crawl the filesystem. When an enabled feature needs local data, it reads bounded known locations such
as browser cookies/local storage, provider config files, or local JSONL logs. Plain Adaptive refresh does not inspect
local agent activity. The separate agent-aware Adaptive option asks before checking the running-process list and bounded
session metadata; declining returns to plain Adaptive.

- **Full Disk Access is optional** and is only needed for Safari cookies/local storage used by web-based providers.
- **Keychain access** may be used for browser Safe Storage, cached cookie headers, and OAuth/device-flow credentials.
- **Files & Folders prompts** can come from provider CLIs or local probes working in a project directory or external
  volume; this is not background disk scanning.
- CodexBar does not request Screen Recording or Accessibility permissions in the background. A user-triggered helper
  may request Automation permission to open Terminal.

See [Keychain prompt troubleshooting](docs/keychain-prompts.md) for safe checks and support-report guidance.

### Documentation

- [Provider overview](docs/providers.md)
- [CLI reference](docs/cli.md) and [CLI configuration](docs/cli-configuration.md)
- [Configuration](docs/configuration.md)
- [Dashboard Snapshot API](docs/dashboard-api.md)
- [Widgets](docs/widgets.md)
- [Architecture](docs/architecture.md)
- [Refresh loop](docs/refresh-loop.md) and [status polling](docs/status.md)
- [Development](docs/DEVELOPMENT.md)
- [Packaging](docs/packaging.md) and [release checklist](docs/RELEASING.md)
- [Changelog](CHANGELOG.md)

### Build from source

The Swift package requires macOS 14+ and Swift 6.2+.

```bash
# Build and package CodexBar.app with ad-hoc signing
./Scripts/package_app.sh
open CodexBar.app

# Development loop: build, package, relaunch, and verify the app
./Scripts/compile_and_run.sh

# Run the sharded test suite before packaging and relaunching
./Scripts/compile_and_run.sh --test

# Run formatting and lint checks
make check
```

For a CLI-only build, use `swift build`. See [Development](docs/DEVELOPMENT.md) for the full workflow.

## Related projects

- [Win-CodexBar](https://github.com/Finesssee/Win-CodexBar) - Windows companion.
- [codexbar-waybar](https://github.com/Marouan-chak/codexbar-waybar) - Waybar integration.
- [Codexbar GNOME](https://extensions.gnome.org/extension/9841/codexbar/) - GNOME Shell extension.
- [codexbar-cinnamon-applet](https://github.com/jacobcalvert/codexbar-cinnamon-applet) - Cinnamon panel applet.
- [noctalia-codex-usage](https://github.com/rayoplateado/noctalia-codex-usage) - Noctalia/Quickshell plugin.
- [KodexBar](https://github.com/tylxr59/KodexBar) - KDE Plasma widget.
- [codexbar-plasmoid](https://github.com/psimaker/codexbar-plasmoid) - KDE Plasma 6 widget.
- [showy-quota](https://github.com/enieuwy/showy-quota) - SketchyBar, tmux, and Zellij quota strips.
- [codexbar-cosmic-applet](https://github.com/andrew-verde/codexbar-cosmic-applet) - COSMIC desktop panel applet.
- [CodexBar Plasma](https://github.com/Lucenx9/codexbar-plasma) - KDE Plasma 6 widget.
- [CodexBar Meter](https://github.com/noctalia-dev/community-plugins/tree/main/codexbar-meter) - Noctalia v5 widget.
- [AI Usage Limits](https://github.com/lenadweb/stream-deck-ai-limits) - Elgato Stream Deck integration.

## Credits and license

The upstream project is maintained by Peter Steinberger ([steipete](https://twitter.com/steipete)) and is licensed
under the [MIT License](LICENSE). This fork preserves the upstream copyright notice and is also MIT-licensed.

CodexBar's cost tracking was inspired by [ccusage](https://github.com/ryoppippi/ccusage) (MIT).
