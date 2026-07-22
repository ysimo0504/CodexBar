# Android UI 技术栈与渲染状态边界

状态：MVP 决策完成

核验日期：2026-07-23

目标设备：BOOX Leaf3C，Android 11 / API 30，BOOX 固件 4.2

## 决策

CodexBar Ink 的首个可安装 APK 使用 **单 Activity + 经典 Android Views/XML + ViewBinding**。MVP 不使用
Jetpack Compose，也不采用 Compose/View 混合界面。

UI 只消费平台中立的不可变 `DashboardPresentationState`。每个电子纸语义区域对应一个稳定、真实且已
附着到 Window 的 Android `View`：全局新鲜度头部、Codex 卡片、Claude 卡片、通用 Provider 容器和每个
通用 Provider 行。状态提交并完成布局后，`DisplayUpdateCoordinator` 才把变化区域交给
`DisplayAdapter`。

这延续了[平台中立 Reader 边界](../adr/0001-platform-neutral-reader-boundary.md)和
[BOOX Android 集成约束](boox-android-eink-integration-constraints.md)，不改变 Dashboard Snapshot v1。

## 为什么选择 Views/XML

| 维度 | Views/XML | Jetpack Compose | 窄混合方案 |
| --- | --- | --- | --- |
| Onyx root/区域所有权 | 每个语义区域可直接持有真实 `View` | `ComposeView` 是 View，但内部语义节点不是独立 Android View | 同时维护两套边界 |
| 局刷映射 | 状态差异直接映射到稳定目标 View | 需要额外把 Compose 坐标映射回宿主 View，并在 Leaf3C 证明 | 仍需 Compose 坐标桥 |
| 更新可观察性 | binder、目标 View 和 adapter 调用可以分别记录 | 重组次数可观察，但重组不等于电子纸刷新 | 两套可观察模型 |
| 单屏实现成本 | XML、ViewBinding、Espresso 即可 | 增加 Compose compiler、BOM、runtime、预览和测试依赖 | 同时承担两套依赖与生命周期 |
| 字体/旋转预览 | Layout Editor 可直接预览 | Compose Preview 可用 | 两套预览 |
| MVP 收益 | 最少机制，匹配 vendor API | 对单个静态仪表盘没有足够额外收益 | 没有明确收益 |

Android 的 `View.invalidate(left, top, right, bottom)` 自 API 28 起弃用，且文档明确说明从 API 21 起传入的
dirty rectangle 会被忽略。因此 API 30 上不能把标准 Android dirty rect 当成“受限电子纸局刷”的保证；
应把**稳定的语义目标 View**交给 Onyx adapter。标准 Android fallback 只负责正常失效与重绘。
参见 [`View.invalidate`](https://developer.android.com/reference/android/view/View#invalidate(int,int,int,int))。

Compose 可以通过 `ComposeView` 嵌入 View 层级，也提供重组/跳过计数和 compiler stability 报告，但这些
信号只解释 Compose 的 UI 工作，不证明 E Ink 控制器执行了哪种 waveform 或刷新了哪个物理区域。
当前官方示例使用稳定 Compose BOM `2026.06.00`，在 API 30 上运行 Compose 本身不是兼容性障碍；
不选择它是因为本屏的 vendor View 边界和额外构建/测试成本，而不是系统版本。
参见 [Compose in Views](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views)、
[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、
[诊断 Compose stability](https://developer.android.com/develop/ui/compose/performance/stability/diagnose)和
[Compose lifecycle](https://developer.android.com/develop/ui/compose/lifecycle)。

若未来出现多屏导航、复杂交互，或在 Leaf3C 上证明 Compose 子区域坐标桥能稳定控制实际局刷，再单独
复审 Compose。不要为了未来可能性让 MVP 形成混合 UI。

## 精确 MVP 栈

- Android Gradle Plugin `9.2.1`、Gradle `9.4.1`、JDK 17；使用 AGP 9 的 built-in Kotlin。
- `compileSdk = 36`、`targetSdk = 36`、`minSdk = 30`。API 30 是已连接 Leaf3C 的 BOOX-first 基线，
  不是对所有电子阅读器的兼容承诺。
- 单个 `ComponentActivity`，无 Fragment、Navigation、依赖注入框架或后台 Worker。
- XML + ViewBinding；浅层 `ConstraintLayout` 或小型专用 `ViewGroup`。不使用 Compose 依赖、插件或 BOM。
- 首屏不使用 `RecyclerView`。Codex、Claude 使用固定优先槽位；其他 Provider 绑定到稳定的通用行容器。
  若未来必须引入 `RecyclerView`，关闭 `itemAnimator` 并保持 stable ID。
- `androidx.lifecycle` 承载 Activity/ViewModel 和 `RESUMED` 前台循环；配置变化不通过 manifest
  `configChanges` 规避。
- Onyx `onyxsdk-device:1.3.5` 只进入可选 BOOX 模块/variant；generic 构建不依赖 vendor SDK。
- 所有依赖固定版本，并启用 Gradle dependency locking/verification。

AGP 9.2 的官方兼容表要求 Gradle 9.4.1、Build Tools 36.0.0 和 JDK 17；Android 16 SDK 设置页给出
`compileSdk`/`targetSdk` 36。参见
[AGP 9.2 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes)、
[AGP 9 built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes)和
[Android 16 SDK setup](https://developer.android.com/about/versions/16/setup-sdk)。

## 最小模块结构

```text
:reader-core   Kotlin/JVM
  Snapshot DTO/decoder -> validation/sanitization -> per-provider last-good
  -> freshness/order -> immutable presentation state + semantic change set

:app           Android application
  lifecycle/transport/persistence -> ViewModel -> XML/View binder
  -> DisplayUpdateCoordinator -> GenericDisplayAdapter
  src/generic -> no vendor dependency
  src/boox -> Onyx capability probe -> REGAL/GU partial update -> GC cleanup
```

`:reader-core` 不得引用 Android、`View`、Activity、Onyx SDK、HTTP client、Keystore 或本地化 formatter。
`:app` 定义 `DisplayAdapter` 接口并始终包含 generic 实现。Onyx 实现只存在于 `boox` product flavor/source
set，SDK 只用 `booxImplementation` 引入；`genericDebug` 的依赖图必须完全没有 Onyx AAR。Onyx 实现只能依赖
adapter 契约，不能读取 Dashboard DTO、token 或 Provider 凭据。若 vendor 代码以后增长，再无行为变化地
提取为第三模块；MVP 不提前增加模块。

Android 官方 product flavor/source set 能为同一 App 生成不同代码与依赖的 build variant，适合保持
generic 构建无 vendor SDK。参见 [Configure build variants](https://developer.android.com/build/build-variants)。

个人原型可提供 `fixture` 与 `booxFixture` variant。真实 LAN/TLS transport、长期签名和发布 variant 由各自
票据决定，不在 UI 技术选型中偷带实现。

## 单向状态与提交时序

```text
DashboardSnapshotV1 DTO
  -> validate schema and required fields
  -> sanitize unknown/error data
  -> merge healthy fields into per-provider last-good
  -> DashboardPresentationState + SemanticChangeSet
  -> DashboardBinder binds changed stable Views only
  -> next pre-draw/frame after layout is committed
  -> DisplayUpdateCoordinator coalesces semantic regions
  -> GenericDisplayAdapter or OnyxDisplayAdapter
```

### 核心契约

- `DashboardPresentationState` 是不可变值，包含全局新鲜度、固定优先卡、按稳定顺序排列的通用行和
  last-good 上的 stale/error 装饰；不包含 `View`、drawable 或本地资源 ID。
- `SemanticChangeSet` 由 Reader Core 比较前后 presentation state 生成，是是否触发电子纸刷新的唯一业务
  依据。不能用“某个 View 调用了 invalidate”或“Compose 重组了”反推业务变化。
- `DashboardBinder` 只绑定 change set 指定的区域。相同内容不写 View，不调用 display adapter。
- 新 Provider、删除/重排 Provider、字体/方向/insets 改变和根布局尺寸改变，升级为 root/full-region 更新。
- 单一卡片文本或状态变化只提交该卡片的实际 View。多个同帧变化合并一次，不串行启动 waveform。
- UI bind 后通过一次 `OnPreDrawListener` 或下一帧回调提交 display update，确保目标 View 已测量、布局并
  附着；Activity 停止时取消待提交回调。

## DisplayAdapter 契约

```text
attach(rootView)
capabilities
renderSemanticChange(targetViews, reason)
fullRefresh(reason)
detach()
```

### GenericDisplayAdapter

- 依靠普通 Android View 更新；必要时只调用目标 View 的无参数 `invalidate()`。
- 不承诺 waveform、物理 dirty rect 或残影清理。
- 在非 BOOX 设备、Onyx 类不存在、初始化失败或任意 vendor 调用异常时始终可用。

### OnyxDisplayAdapter

- 只在主线程、root 已附着 Window 后 attach，并运行能力探测。
- 文本/卡片局刷优先使用真机已证明可用的 `REGAL`；否则退回 `GU`。
- 冷启动、根布局重排、用户手动清残和真机确定的周期清残使用 `GC` 全刷。
- 只把真实的稳定目标 View 传给 Onyx SDK，不依赖 Android dirty rect。
- attach、模式设置或刷新中的任何异常都使本进程 vendor 能力失败关闭；已经提交的普通 View 内容继续
  显示，后续走 generic。
- detach 时取消回调、清空目标引用、恢复 View update mode；不阻塞等待，不并发 waveform。

Onyx 官方把 GU 描述为 16 灰阶局刷、REGAL 描述为面向文字优化的 16 灰阶局刷、GC 描述为 16 灰阶
全刷，并展示了 `setViewDefaultUpdateMode(view, ...)` 与对 View 执行刷新。参见
[EPD Update Mode](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EPD-Update-Mode.md)和
[EPD Screen Update](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EPD-Screen-Update.md)。

## 视觉、配置与无障碍契约

### 固定首屏层级

1. 标题与全局 freshness/last sync。
2. Codex 优先卡。
3. Claude 优先卡。
4. 其他/未知 Provider 通用行。
5. 必要的静态操作区，例如“立即同步”或“清残”。

竖屏使用 `sensorPortrait`，支持两个 portrait 方向。界面单列、首屏无滚动、灰阶可辨，不依赖颜色、
渐变、透明层、阴影、动画或每秒时钟。普通正文不得小于 14sp，触控目标至少 48dp。字体使用 sp；
Android 的 View 无障碍指南要求至少 48dp 触控目标。参见
[Android accessibility for Views](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)和
[Accessibility foundations](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)。

### Insets、字体和配置变化

- 启用 edge-to-edge，并把 `systemBars` 与 `displayCutout` insets 应用到安全内容容器；MVP 不隐藏系统栏。
- 方向、字体比例、locale、density 或系统栏变化由系统重建 Activity；ViewModel/持久化 last-good 恢复状态。
- 不缓存旧 Activity、root View 或区域 View。新 root attach 后才允许 display adapter 工作。
- 字体放大导致高度不足时，优先保留 freshness、Codex、Claude；通用 Provider 折叠为稳定的前 N 行加
  “另有 N 项”，不缩到 14sp 以下，也不临时改成滚动。
- XML Layout Editor 为标准、两个 portrait 方向、大字体和极端长文本准备 `tools:` 预览数据。

Android 15+ 对较新 target 的 edge-to-edge 行为及 Window Insets 需要显式处理；配置变化默认会重建
Activity。参见 [Edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge)、
[Window insets](https://developer.android.com/develop/ui/views/layout/insets)和
[Handle configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes)。

### 未知值与错误

- 未知 Provider 使用通用行，显示 snapshot 的安全 display name 或 opaque ID，不崩溃、不隐藏整页。
- 未知 window 使用通用 label/value；按 snapshot 稳定顺序展示，溢出时汇总，不改变优先卡位置。
- Provider 错误在 last-good 上添加 stale/error 状态，不清空仍可用指标。
- 整体 snapshot 错误只改变全局 freshness/error；旧内容继续可读。
- TalkBack 顺序与视觉顺序一致。标题使用 heading 语义；每张卡作为可理解的组；装饰图形不暴露。
- 后台轮询结果不反复抢占无障碍焦点，也不为无变化数据发送 announcement。
- 禁止 transition、layout animation、ripple、indeterminate spinner 和默认列表 item animation。

## 验证缝合

| 层 | 测试 | 证明什么 |
| --- | --- | --- |
| `:reader-core` JVM | canonical fixture、schema、unknown provider/window、per-provider last-good、freshness、排序、diff | 平台中立状态确定性 |
| binder JVM/Android | fake region sink；相同状态零更新；单卡/多卡/root change 映射 | 语义变化到稳定区域 |
| View instrumented | Espresso：初始内容、两种 portrait、大字体、insets、长文本、无滚动 | XML UI 契约 |
| accessibility | Espresso AccessibilityChecks + 手动 TalkBack 顺序 | 触控目标与语义 |
| generic adapter | fake attached Views；失败不影响绑定 | 普通 Android failure-open |
| Onyx adapter | Leaf3C：REGAL/GU A/B、GC 清残、attach/detach、旋转、休眠恢复 | 真实 EPD 行为 |

Android 官方将快速隔离测试放在 `test`，把依赖真机/框架的测试放在 `androidTest`；Espresso 提供 View
交互与无障碍检查。参见 [What to test](https://developer.android.com/training/testing/fundamentals/what-to-test)、
[Espresso basics](https://developer.android.com/training/testing/espresso/basics)和
[Espresso accessibility checks](https://developer.android.com/training/testing/espresso/accessibility-checking)。

任何 JVM 或 emulator 测试都不能声称证明了 E Ink waveform、物理局刷范围或残影表现。最终判据必须是
当前 Leaf3C 固件 4.2 上的可见结果。

## 回退与延后项

### 必须存在的回退

- Onyx 不可用或失败：`GenericDisplayAdapter`，App 继续显示。
- REGAL 不支持或效果未证明：GU。
- 局部刷新目标无法安全确定：root/GC，而不是猜测坐标。
- 新 snapshot 无效：保留 per-provider last-good 与全局错误状态。

### 明确延后

- Compose 或 hybrid UI。
- Compose 子区域坐标桥与重组到 waveform 的映射。
- Fragment/Navigation、多屏设置流程、RecyclerView、DI 框架、动态主题。
- 动画、图表、连续滚动、FAST waveform、彩色专用 vendor API、app-scope 全局模式。
- 后台 5 分钟更新、长期签名/商店分发和 Onyx SDK 再分发许可结论。

## 下一步实现入口

“Prove the BOOX snapshot rendering loop”应按以下最短路径实施：

1. 建立上述两个模块和 fixture/booxFixture variant。
2. 先让 `:reader-core` 解码 canonical fixture 并输出 presentation state/change set。
3. 用 XML 绑定固定首屏，generic adapter 跑通两种 portrait、大字体和无变化零刷新。
4. 在 Leaf3C 安装 fixture APK，确认 App 可见后再接 Onyx adapter。
5. A/B 验证 REGAL/GU 与 GC；任何 vendor 失败都保留 generic 可见界面。

本决策不实现 Android App、不访问 live Provider、不读取 macOS Keychain。
