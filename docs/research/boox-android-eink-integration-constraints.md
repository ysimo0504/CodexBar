# BOOX Android 与墨水屏集成约束

状态：BOOX-first 实现约束已收敛；Leaf3C 真机能力仍待
[设备取证](https://github.com/ysimo0504/CodexBar/issues/13)验证。
最后核验：2026-07-22

## 决策摘要

CodexBar Ink 首版继续采用原生 Android APK，但产品能力分成两层：

- 平台中立层负责 Dashboard Snapshot、认证、净化后的 last-good、生命周期和静态 UI。
- `DisplayAdapter` 隔离电子纸刷新。`GenericDisplayAdapter` 只用标准 Android 重绘；
  `OnyxDisplayAdapter` 才能调用 Onyx SDK。
- Onyx 适配失败时必须退化到标准 Android 显示，不能阻止启动、读缓存或同步。
- 5 分钟同步只在前台 `RESUMED` 生命周期运行；恢复前台时立即同步。后台、熄屏、BOOX 休眠和
  Doze 中不保活。
- MVP manifest 只申请 `android.permission.INTERNET`。不申请唤醒、后台服务、自启动、安装 APK、
  忽略电池优化或外部存储权限。
- Reader Client 只保存 Usage Host URL、Dashboard API token、显示偏好和净化后的 last-good；
  不保存任何 Usage Provider 凭据。
- token 由 Android Keystore key 加密后放在应用私有内部存储；`android:allowBackup="false"`。
- 个人受信任 LAN MVP 可以有一个显式的 `lan` 构建允许 HTTP，但必须承认 bearer token 和快照
  没有传输机密性、服务端真实性或防篡改保证。普通 release 构建保持 cleartext 禁用。
- 首个 Onyx 候选依赖固定为 `onyxsdk-device:1.3.5`。不引入 pen、scribble、DBFlow、
  `HiddenApiBypass`、ABI filter 或动态版本。`1.3.5.1` 只在 Leaf3C 真机对照后考虑升级。
- 首版只用 View/root 级 `REGAL` 或 `GU` 局刷和 `GC` 全刷。语义内容变化才刷新；不长期改
  app-scope 模式，不用 FAST、动画或未验证的彩色控制 API。

本报告不重复硬件规格、产品使用场景或 Dashboard JSON 字段。它们分别由
[Leaf3C 目标设备报告](boox-leaf3c-target-context.md)和
[Dashboard Snapshot Reader 边界报告](dashboard-snapshot-reader-seam.md)负责。

## Android 基线

### 当前证据边界

用户看到的“4.2”是 BOOX 固件版本，不是 Android 4.2。Leaf3C 当前 Android release、API Level、
安全补丁、build fingerprint 和 BOOX 完整构建号仍未取得真机证据。当前 ADB 只连接到非 BOOX
设备，因此本次没有把 Android 11 / API 30 从“高置信工作假设”升级为已验证事实。

Android 官方定义 `Build.VERSION.SDK_INT` 为设备当前运行软件的 SDK 版本；AOSP 对照表确认
Android 11 对应 API 30。实现必须读取运行时值，不从 BOOX OS 4.2、营销型号或上市年份反推。
参见 [Build.VERSION](https://developer.android.com/reference/android/os/Build.VERSION)和
[AOSP 版本/API 对照表](https://source.android.com/docs/setup/reference/build-numbers)。

### 构建版本决策

- 在 Leaf3C preflight 前不锁定 `minSdk`。若真机确认 API 30，BOOX-only MVP 可把 API 30 作为
  首台设备基线；若随后纳入较旧 Android 阅读器，再用真机测试支持较低 API。
- `compileSdk`、`targetSdk`、`minSdk` 是不同契约。不能复制 Onyx sample 的
  `compileSdk 30 / targetSdk 30 / minSdk 24`，也不能为了绕开安全行为长期压低 `targetSdk`。
- 使用当前稳定 Android 工具链；每次提升 `targetSdk` 都重新跑 Leaf3C 安装、网络、Keystore、
  SDK 初始化和刷新矩阵。
- 仅在调用标准 Android 新 API 时按 `SDK_INT` 分支。Onyx 能力按行为探测，不按 Android
  release 推断。

Android 的 [`<uses-sdk>` 文档](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
说明 `minSdkVersion` 控制最低可安装系统，`targetSdkVersion` 选择目标平台行为；两者不能互换。

### 运行时能力探测

`OnyxDisplayAdapter` 初始化按以下顺序失败关闭：

1. 记录脱敏后的 `Build.MANUFACTURER`、`BRAND`、`MODEL`、`DEVICE` 和 `SDK_INT`；型号只作诊断。
2. 只有 BOOX/Onyx 候选设备才加载 vendor adapter。不要把字符串 `Leaf3C` 当唯一能力开关。
3. 在隔离边界内调用 `Device.currentDeviceIndex()`，再探测 `EpdController.supportRegal()`。
4. 对实际附着到 Window 的 root View 做一次可观察的 GU/REGAL 局刷和 GC 清残测试。
5. 捕获 SDK 初始化、链接和 vendor 调用错误，关闭本次进程的 Onyx 能力，切回
   `GenericDisplayAdapter`。

当前官方仓库没有 Leaf3C 名称，也没有通用 `isEpdSupported` 示例。检查 `onyxsdk-device:1.3.5`
AAR 可见未知 board 会落到 `BaseDevice`；其普通 invalidate 会调用标准 `View.invalidate()`，大量
vendor 操作则无动作。部分上层 SDK 方法还会忽略底层返回值，因此“调用未抛错”不等于能力已生效。
真机屏幕结果才是最终判据。

## 最小权限与安全存储

### MVP manifest

| 项目 | MVP | 原因 |
| --- | --- | --- |
| `android.permission.INTERNET` | 允许 | 请求 Usage Host snapshot |
| `ACCESS_NETWORK_STATE` | 禁止 | 首版从请求成功/失败判断连接，无需额外权限 |
| `WAKE_LOCK` | 禁止 | 不在休眠中保活 |
| `FOREGROUND_SERVICE*` | 禁止 | 没有用户期望的后台长任务 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 禁止 | 不绕过 Doze |
| `RECEIVE_BOOT_COMPLETED` | 禁止 | MVP 不自启动 |
| `REQUEST_INSTALL_PACKAGES` | 禁止 | App 不自更新、不安装其他 APK |
| `READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE` | 禁止 | 配置和缓存只进私有内部存储 |
| Provider、账号、Cookie 相关权限 | 禁止 | Provider 凭据只属于 Mac Usage Host |

`INTERNET` 足以在目标 Android 11 设备建立 LAN TCP/HTTP(S) 连接。未来支持 Android 17 且
`targetSdk >= 37` 时，Android 官方要求 LAN 运行时权限；那是扩展设备范围时的单独迁移，不能为
Leaf3C MVP 提前扩大权限。参见
[Android Local Network Permission](https://developer.android.com/privacy-and-security/local-network-permission)。

### token 与 last-good

- 用 Android Keystore 生成不可导出的 AES key；用 `AES/GCM/NoPadding` 加密 token，再把密文、
  IV 和版本写入应用私有内部存储。
- 不要求每次使用 key 时进行用户认证，否则桌面仪表盘无法自动同步。硬件/StrongBox 支持不是
  先决条件，也不能未经探测宣称为硬件保护。
- Host URL、显示偏好和净化后的 last-good 也只放内部存储。无需 SD 卡或共享目录。
- manifest 设置 `android:allowBackup="false"`；不让 token、Host 地址或 last-good 进入云备份或
  设备迁移。真机验收仍检查 BOOX 是否有额外 OEM 备份入口。
- token 不写日志、异常、analytics、截图元数据、剪贴板、query string 或导出文件。日志只允许
  脱敏错误类别和 request ID。
- 更换 Host、清除配置或退出配对时，删除 token、Keystore alias 和对应 last-good。
- 401 保留 last-good 供离线查看，但停止自动重试风暴并要求重新配置 token。

Android 官方说明内部 app-specific storage 不需要存储权限，其他应用不能访问；Android 10+
设备上的该区域由系统加密。Keystore 保证 key material 不导出，但应用进程被攻破时攻击者仍可能
调用 key，所以它不是对已解锁恶意进程的绝对防护。参见
[App-specific storage](https://developer.android.com/training/data-storage/app-specific)、
[Android Keystore](https://developer.android.com/privacy-and-security/keystore)和
[Android cryptography](https://developer.android.com/privacy-and-security/cryptography)。

## 前台生命周期、Doze 与电源

### 同步状态机

1. 进程启动：先解密并展示净化后的 last-good，不等待网络。
2. Activity 进入 `RESUMED`：立即发起一次 snapshot 请求；同一时刻只允许一个请求。
3. 请求完成后，从完成时刻开始下一次 5 分钟延迟；禁止失败后并行或补跑多次。
4. 仍为 `RESUMED` 且屏幕 interactive 时继续；进入 `PAUSED/STOPPED`、熄屏或退出时取消循环。
5. 恢复前台：重新执行步骤 1、2。旧延迟不补偿，先同步一次。
6. 每次网络失败都保留 last-good，并以有限退避展示 offline/stale；用户“立即同步”仍受 Host
   cache 语义约束。

可用 `repeatOnLifecycle(Lifecycle.State.RESUMED)` 承载循环；该 API 在生命周期低于目标状态时
取消 block，恢复时重新启动。参见
[`repeatOnLifecycle`](https://developer.android.com/reference/androidx/lifecycle/RepeatOnLifecycleKt)。

### 不使用后台 5 分钟任务

WorkManager 周期任务最短间隔是 15 分钟，执行时间仍会受约束和系统优化影响。Doze 会暂停网络，
延迟 JobScheduler、WorkManager、普通 alarm 和 sync；因此 WorkManager 不能兑现休眠中每 5 分钟
刷新。参见
[WorkManager periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)和
[Doze/App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)。

MVP 不使用 WorkManager、AlarmManager、wake lock、前台服务、FCM 或电池优化白名单来弥补这个
限制。产品承诺是“前台每 5 分钟；唤醒立即同步”，不是“休眠时持续更新”。

### BOOX 电源、冻结与自启动

BOOX 官方说明设备自动休眠用于省电，默认可在休眠后切断 Wi-Fi、蓝牙和后台音频；自动关机也可
由电源管理配置。网络断开、Mac 休眠和 Host 暂不可达必须视为正常状态。参见
[BOOX 自动休眠与关机延迟](https://support.boox.com/#/document/69bb6e42de2ae6af66489165)。

BOOX 还提供第三方 App 后台冻结，且可设置“第三方 App 安装后默认冻结”。冻结会改变后台行为；
安装后必须检查 CodexBar Ink 状态，并分别测试冻结/未冻结下的熄屏、唤醒和手动重开。参见
[BOOX 应用冻结](https://support.boox.com/#/document/69bb71d1de2ae6af66489292)。

当前公开资料没有 Leaf3C 开机自启动契约。MVP 不声明 boot receiver，不在后台拉起 Activity，也
不承诺关机后自动恢复。若后续真机需求成立，单独设计 kiosk/launcher 方案。

可选“接电常亮”默认关闭。用户显式开启且设备正在充电时，前台 Activity 可使用
`FLAG_KEEP_SCREEN_ON`；一旦离电、进入后台或用户关闭选项就清除。该 flag 只能由 Activity 使用，
应用进入后台后系统仍可熄屏。参见
[Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)。

## LAN 与 cleartext 边界

### MVP 拓扑

- Leaf3C 与 Mac Usage Host 位于同一受信任家庭/办公 WLAN。
- 首版由用户填写固定私网 Host URL；不依赖 mDNS、广播发现、IPv6 或公网访问。
- Reader 只调用认证的 `/dashboard/v1/snapshot`，只在 `Authorization: Bearer` header 放 token。
- 禁止 query token，禁止把 Authorization 跨 Host/协议重定向。snapshot 请求默认拒绝重定向。
- 设置连接、读取和总请求超时；所有非 200、schema 错误和解析错误都保留 last-good。

完整 payload、freshness、per-provider last-good 和错误净化规则见
[Dashboard Snapshot Reader 边界报告](dashboard-snapshot-reader-seam.md)。

### cleartext 决策

Android 9 / API 28 起，面向 API 28+ 的应用默认禁用 cleartext；Network Security Configuration
可显式开启。HTTP 没有机密性、真实性或完整性，局域网内观察者可读取 token/快照并篡改响应。
参见
[Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)和
[Cleartext communications risk](https://developer.android.com/privacy-and-security/risks/cleartext-communications)。

构建策略：

- 普通 release：`base-config cleartextTrafficPermitted="false"`，只接受 HTTPS。
- 个人 `lan` flavor：用独立 Network Security Configuration 显式允许 HTTP；UI 持续标记“受信任
  LAN，未加密”。代码只接受用户确认的私网目标，拒绝公网地址和重定向。
- Network Security Configuration 是静态文件。若 Host 是运行时输入的私网 IP，不能声称已在
  平台层做到每个动态 Host 的精确 allowlist；`lan` flavor 的 cleartext 放行仍是构建级风险。
- 不实现 trust-all `TrustManager`、跳过 hostname verification 或把自签证书静默信任。
- TLS、Tailscale、证书配置和远程访问由后续 transport ticket 决定，不在 Reader 中临时造协议。

“同一 WLAN”只缩小暴露面，不把 HTTP 变成安全传输。Host 防火墙仍应只放行预期私网和端口，
AP 客户端隔离必须在真机验证。

## APK 侧载、更新与签名

BOOX 官方说明其开放 Android 系统支持第三方 App 安装；Android 8+ 对非商店安装按来源要求用户
开启“安装未知应用”。个人 MVP 首选 ADB 或用户主动安装签名 APK，不依赖 Google Play。
参见 [BOOX 第三方 App 安装](https://support.boox.com/#/document/69bb71d0de2ae6af66489289)和
[Android alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution)。

更新契约：

- 尽早锁定稳定 application ID；具体命名和所有权仍需项目决策。
- 所有可更新 release APK 使用同一长期 signing certificate；私钥不进入仓库。
- 每次发布递增 `versionCode`。升级使用系统 installer 或 `adb install -r`。
- 新 APK 的 application ID、签名证书和 versionCode 必须满足 Android 更新规则；签名不一致会
  要求卸载旧 App，导致本地 token 与 last-good 被删除。
- App 本身不下载或安装 APK，因此不申请 `REQUEST_INSTALL_PACKAGES`，也不做静默自更新。
- 发布前同时测试 debug、未压缩 release 和 minified release；Onyx sample 本身关闭 minify，
  vendor AAR 没有公开 consumer ProGuard rules，不能只验证 debug。

Android 官方更新规则见
[How app updates work](https://developer.android.com/google/play/app-updates)和
[Sign your app](https://developer.android.com/studio/publish/app-signing)。

2026 年 Android developer verification 仍保留 ADB 安装路径；官方 FAQ 明确 ADB 安装不要求注册。
直接面向更多用户分发时，2027 年后的全球规则需要重新核验。Leaf3C 是否属于 Google certified
device 也未验证，不能据中国市场型号作推断。参见
[Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)。

## Onyx 依赖漂移、HTTPS 与许可

核验的官方仓库 commit：
[`3fb2b55646eda97e1f8993bd980f6d9821df379c`](https://github.com/onyx-intl/OnyxAndroidDemo/commit/3fb2b55646eda97e1f8993bd980f6d9821df379c)
（2026-06-29）。

### 版本事实

| 官方位置 | device | pen | 判断 |
| --- | --- | --- | --- |
| [README](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/README.md) | 1.1.11 | 1.2.1 | 已落后于 sample |
| [sample build.gradle](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/app/OnyxAndroidDemo/build.gradle) | 1.3.5 | 1.5.4 | 当前官方 sample 实际值 |
| [官方 Maven metadata](https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-device/maven-metadata.xml) | 1.3.5.1 latest/release | 不适用 | 无公开兼容矩阵或 changelog |

首个原型固定：

```kotlin
repositories {
    maven("https://repo.boox.com/repository/maven-public/")
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5")
}
```

约束：

- 官方 sample 仓库写的是 HTTP Maven URL，但同一路径 HTTPS 已验证可用。只允许 HTTPS。
- 禁止 `1.+` 或 `latest.release`。开启 dependency locking/verification，记录 artifact checksum。
- `1.3.5` POM 传递依赖 `fastjson2:2.0.48.android8` 与 `androidx.annotation:1.0.0`；升级前检查
  dependency graph。`1.3.5.1` 还加入 Kotlin stdlib 1.6.10。
- 1.3.5 和 1.3.5.1 device AAR 都只有 manifest、`classes.jar` 和资源，无 JNI `.so`。不要复制
  sample 的 `armeabi-v7a` filter；它会无谓限制 APK。
- 不依赖 `onyxsdk-pen`、scribble、data/base SDK、DBFlow 或 sample 的旧 AndroidX 版本。
- 官方 sample 在 Android R+ 调用 `HiddenApiBypass.addHiddenApiExemptions("")`，属于宽泛隐藏 API
  绕过。MVP 禁止复制该依赖和调用。先在 Leaf3C 测试最小 device SDK；若失败，退化 generic，
  再单独做安全/兼容评审。
- 官方 GitHub 仓库没有公开 LICENSE，Maven POM 也没有足以确认再分发授权的 license 声明。
  个人原型可继续技术验证；向第三方分发前必须向 Onyx 确认 SDK 使用与再分发许可。

已核验 artifact：

- `onyxsdk-device-1.3.5.aar` SHA-256
  `6e57dab76f679512329f9dbb0985bba406418f18d272ad00d1cfac98e6bbe0f4`
- `onyxsdk-device-1.3.5.1.aar` SHA-256
  `8eb975d4632dc7dda6300bef8410834b424ed2812287b857088a3c11c6c9c207`

这些 checksum 只描述 2026-07-22 从官方 HTTPS Maven 取得的 artifact；正式构建应由依赖验证文件
锁定，不能依赖本文手工值。

## DisplayAdapter 刷新策略

### 两层模式不能混用

Onyx 文档暴露两类概念：

- `UpdateOption`：NORMAL、FAST_QUALITY、REGAL、FAST、FAST_X，是应用级/用户可见刷新配置。
- `UpdateMode`：GU、REGAL、GC、DU、ANIMATION 等，是一次 View/区域刷新的低层 waveform 选择。

官方说明 GU 是 16 级灰阶局刷，REGAL 是文字页优化局刷，GC 是 16 级灰阶全刷，DU 是黑白局刷，
动画类模式以细节换速度。参见
[EPD Update Mode](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EPD-Update-Mode.md)和
[EPD Screen Update](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EPD-Screen-Update.md)。

MVP 不写 `UpdateOption`，不覆盖用户在 E Ink Center 为 App 选择的持久配置。adapter 只对自己的
root View/可见区域发低层 update。

### MVP 算法

`DisplayAdapter` 最少提供：

```text
capabilities()
attach(rootView)
renderSemanticChange(dirtyRegion?)
fullRefresh(reason)
detach()
```

Onyx 实现规则：

1. `attach` 在 View 已附着且主线程执行。真机证明 REGAL 有效时优先 REGAL，否则 GU。
2. Dashboard Snapshot 映射为稳定 render model；只有可见文本、卡片顺序、错误状态、freshness
   bucket 或用户偏好变化才调用 `renderSemanticChange`。
3. 合并同一 frame/短窗口内的多项状态变化，避免每张卡单独刷。
4. 局刷只覆盖稳定 root/区域；冷启动首屏、用户“清除残影”和累计 N 次语义局刷后使用 GC。
5. N 不是定时器，也不在文档中硬编码。通过 Leaf3C 残影矩阵确定；snapshot 内容不变时不累计。
6. `detach` 重置 View update mode，并清理本 adapter 设置过的 transient/app-scope 状态。
7. 不在 UI 线程调用可能阻塞的 `waitForUpdateFinished()`；不并发发多个 waveform 请求。
8. Compose 若用于 UI，把实际承载内容的 `ComposeView`/root View 交给 adapter；精确 region 是否
   有效必须真机验证，不能假设 Compose 重组等于 EPD 局刷。

候选调用仅限：

- `EpdController.setViewDefaultUpdateMode(view, REGAL/GU)`
- `EpdController.invalidate(view, REGAL/GU)` 或已验证的区域重载
- `EpdController.repaintEveryThing(GC)` / 等价 GC 全刷
- `EpdController.resetViewUpdateMode(view)`
- `EpdController.supportRegal()` 仅作前置提示，不作最终证据

不要在 MVP 使用全局/system scope、电源、Wi-Fi、前光、应用冻结、状态栏、触摸禁用、屏保、
系统属性、waveform 文件、FAST/ANIMATION、night mode、color CU 或 pen API。Onyx SDK 暴露这些方法
不代表普通第三方 App 应使用。

BOOX 官方还允许用户从 E Ink Center 调整当前 App 刷新；静态阅读内容偏向高清模式，滚动/视频
才偏向快速模式。CodexBar Ink 是静态仪表盘，因此 FAST 不是默认候选。参见
[BOOX 第三方 App 优化](https://support.boox.com/#/document/69bb71d0de2ae6af6648928c)和
[BOOX 刷新模式入口](https://support.boox.com/#/document/69bb7444de2ae6af6648935b)。

## UI、display、输入与无障碍

- 使用响应式 dp 布局和 sp 字体，不把 1264 × 1680、300 ppi 或 150 ppi 写成 View 尺寸。
- 从 Window 实际可用 bounds/insets 排版；状态栏、导航栏、手势区和 BOOX 浮动控件都可能改变
  可用区域。不要调用 vendor 隐藏状态栏 setter。
- 产品方向保持 `sensorPortrait`，支持两个竖屏方向。仍需能安全处理配置变化和意外横屏；Android
  16+ 大屏可能忽略方向限制，响应式布局是最终防线。
- 单列、高对比、纯灰阶仍能表达全部状态。颜色只作冗余提示；不使用渐变、透明叠层、阴影、
  shimmer、无限动画或连续滚动。
- 正文不小于 Onyx 指南建议的 14sp；交互目标至少 48dp。字体用 sp 并测试 BOOX 字体缩放。
- 所有按钮有文字或无障碍 label，内容顺序与视觉顺序一致；同步完成不自动反复播报整页。
- 首版仅依赖触摸。实体翻页键 keycode、长按和左右侧映射待 preflight，不拦截 Back/音量键。
- 不引入 pen SDK；Leaf3C 首版没有手写需求。

Onyx 的 E Ink UI 指南要求以黑白/灰阶为主、避免透明层和动画、优先分页，正文至少 14sp，边缘
按钮至少 48dp。Android 官方要求按可用窗口和 density 用 dp/sp 排版，并建议无障碍触控目标至少
48dp。参见
[Onyx E Ink UI guide](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/Eink-Develop-Guide.md)、
[Android screen compatibility](https://developer.android.com/guide/practices/screens_support)、
[dp/sp units](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units)和
[Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)。

## 明确禁止项

首个 BOOX MVP 禁止：

- 把 BOOX OS 4.2 当 Android release/API Level。
- 按型号字符串、屏幕像素或上市参数硬编码能力。
- 把 Onyx SDK 初始化成功、boolean 返回值或 class 存在当作刷新成功证据。
- 复制官方 demo 的完整依赖图、旧 AGP/AndroidX、`armeabi-v7a` filter 或
  `HiddenApiBypass`。
- 使用动态 Onyx SDK 版本或 HTTP Maven 仓库。
- 在普通 release 全局允许 cleartext，或把受信任 LAN 描述为加密安全。
- token 放 query、日志、SharedPreferences 明文、外部存储、备份或剪贴板。
- Provider 凭据、Cookie、OAuth token 或原始 Provider 响应进入 Reader。
- 后台 5 分钟 WorkManager、exact alarm、wake lock、前台服务、开机自启或忽略电池优化。
- App 自更新、静默安装 APK 或申请安装包权限。
- 每秒更新时间、动画、无语义重组或原始 JSON 时间戳变化触发 EPD 刷新。
- 持久修改系统/app-scope 刷新、电源、Wi-Fi、前光、冻结、触摸、按键或状态栏设置。
- 在真机 A/B 前固定 REGAL、GU、GC 次数、局刷区域或常亮策略。

## Leaf3C 真机验收

完整只读 ADB 身份命令见
[Leaf3C 目标设备报告的 preflight](boox-leaf3c-target-context.md#真机-adb-preflight)。本任务补充以下
集成验收；公开附件必须移除序列号、SSID、IP、fingerprint 中的敏感部分和 token。

### Gate A：平台与安装

- 设置页与 ADB 同时确认 model/device/product、Android release、`SDK_INT`、安全补丁、BOOX
  完整固件/build 和 ABI。
- 用同一 release signing certificate 完成首次安装和 `adb install -r` 升级；token/last-good 保留。
- 确认不需要 Play Store；记录“安装未知应用”或 ADB 的实际路径。
- 检查应用默认冻结状态、E Ink Center 当前配置、自动休眠和自动关机设置。

### Gate B：SDK 与退化

- 先用 `onyxsdk-device:1.3.5`，且不加 `HiddenApiBypass`，记录 SDK 初始化、
  `currentDeviceIndex`、`supportRegal` 和异常。
- 分别验证 debug、未压缩 release、minified release。
- 强制关闭 Onyx adapter 后，generic 渲染、同步、last-good 和设置必须全部可用。
- 只有 1.3.5 存在可复现问题时才与 1.3.5.1 对照；升级必须重新记录 dependency/checksum。

### Gate C：刷新矩阵

用同一份 synthetic Dashboard Snapshot fixture、固定亮度和固定 E Ink Center 设置测试：

- 冷启动 GC 后分别运行 GU、REGAL；比较文字边缘、灰阶/颜色、闪烁、响应和残影。
- 连续执行至少 20 次有语义变化的卡片更新，在第 5/10/20 次候选点做 GC，拍摄同机位照片。
- 测试小区域变化、整卡变化、排序变化、stale bucket 变化、内容不变轮询和手动清残。
- 切出 App、熄屏、唤醒和旋转后确认 transient/view mode 已正确恢复，不污染其他 App。
- 产出 Leaf3C 固件 4.2 对应的推荐局刷模式与 N；结果不能外推到其他 BOOX/固件。

### Gate D：生命周期与电源

- 前台连续 20 分钟：立即同步一次，之后约每 5 分钟一次，无重叠请求、无无效刷新。
- 熄屏超过一个轮询周期：不保活、不发 5 分钟请求；唤醒先显示 last-good，再立即同步。
- 在 BOOX 默认“休眠后断 Wi-Fi”下测试重连；Host 不可达、Mac 休眠和 AP client isolation 都有
  明确 offline/stale 表达。
- 分别测试冻结/未冻结、接电/离电、常亮关闭/显式开启。常亮只在接电前台生效。
- 自动关机重启后不承诺自启动；用户手动打开可恢复 last-good。

### Gate E：网络与秘密

- HTTPS release 拒绝 HTTP；`lan` flavor 能访问明确确认的私网 HTTP Host，并持续显示未加密警告。
- 拒绝 query token、跨 Host/协议 redirect、公网 HTTP 和无效 schema。
- 抓取应用日志和备份结果，确认没有 bearer token、Provider 凭据、Host 原始错误正文或未净化
  snapshot。
- Keystore key 删除、token 轮换、401、清除配置、重装和签名不匹配都有确定行为。

### Gate F：显示与输入

- 记录 `wm size`、density、insets、两种竖屏方向、系统字体缩放和 E Ink Center 覆盖区域。
- 所有状态在灰阶下可区分；正文至少 14sp，触控目标至少 48dp。
- TalkBack/标准无障碍检查可读；内容刷新不反复抢焦点。
- 记录实体键 keycode，但不把按键支持作为 MVP 通过条件。

## 下游交接

### Reader 渲染原型可以立即采用

- 建立平台中立 Android core 与 `GenericDisplayAdapter`。
- 用 synthetic schema v1 fixture 完成解码、per-provider last-good、5 分钟前台循环和语义 diff。
- 定义 `DisplayAdapter` seam；Onyx SDK 可稍后插入，不能反向污染 snapshot model。
- manifest 只留 `INTERNET`，私有存储和 Keystore 接口先定下来。

### BOOX 原型开始前必须完成

- Leaf3C Android/API/固件 preflight。
- `onyxsdk-device:1.3.5` 无 HiddenApiBypass 的启动和 generic fallback 验证。
- GU/REGAL/GC 基础行为与退出清理验证。

### 个人 trusted-LAN MVP 前必须完成

- transport ticket 明确 `lan` flavor 与最终 HTTPS/Tailscale 边界。
- Host 安全错误净化和 schema v1 canonical fixture。
- Keystore、backup 禁用、签名升级、冻结/休眠/重连、残影 cadence 的真机证据。

### 对外分发或扩设备前必须完成

- Onyx SDK 许可确认、依赖审计和 minified release 验证。
- Android developer verification、Android 17 LAN permission 和目标平台新行为复核。
- 每个新品牌/型号独立 vendor adapter 与刷新矩阵；不得把 Leaf3C 结果当通用 Android 契约。

## 一手来源

### 项目内证据

- [BOOX Leaf3C 目标设备与使用上下文](boox-leaf3c-target-context.md)
- [Dashboard Snapshot seam for reader clients](dashboard-snapshot-reader-seam.md)
- [E-reader platform comparison](e-reader-platform-operating-systems.md)

### BOOX / Onyx

- [Onyx Android Demo，固定 commit](https://github.com/onyx-intl/OnyxAndroidDemo/tree/3fb2b55646eda97e1f8993bd980f6d9821df379c)
- [当前 sample dependency 配置](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/app/OnyxAndroidDemo/build.gradle)
- [sample HiddenApiBypass 初始化](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/app/OnyxAndroidDemo/src/main/java/com/android/onyx/demo/SampleApplication.java)
- [EPD Update Mode](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EPD-Update-Mode.md)
- [EPD Screen Update](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EPD-Screen-Update.md)
- [EpdDeviceManager](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/EpdDeviceManager.md)
- [Onyx E Ink 开发指南](https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/doc/Eink-Develop-Guide.md)
- [onyxsdk-device Maven metadata](https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-device/maven-metadata.xml)
- [onyxsdk-device 1.3.5 POM](https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-device/1.3.5/onyxsdk-device-1.3.5.pom)
- [BOOX 安装第三方 App](https://support.boox.com/#/document/69bb71d0de2ae6af66489289)
- [BOOX 第三方 App 优化](https://support.boox.com/#/document/69bb71d0de2ae6af6648928c)
- [BOOX 应用冻结](https://support.boox.com/#/document/69bb71d1de2ae6af66489292)
- [BOOX 自动休眠、Wi-Fi 与关机延迟](https://support.boox.com/#/document/69bb6e42de2ae6af66489165)
- [BOOX 刷新模式](https://support.boox.com/#/document/69bb7444de2ae6af6648935b)

旧 `help.boox.com` 英文安装、App Optimization 和 Refresh Modes 链接在 2026-07-22 已返回 404；
本报告改用当前 `support.boox.com` 文档和官方 GitHub/Maven 证据。

### Android / AOSP

- [Build.VERSION](https://developer.android.com/reference/android/os/Build.VERSION)
- [Android versions and API levels](https://source.android.com/docs/setup/reference/build-numbers)
- [`<uses-sdk>`](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
- [WorkManager periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)
- [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [App-specific storage](https://developer.android.com/training/data-storage/app-specific)
- [Alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution)
- [App update rules](https://developer.android.com/google/play/app-updates)
- [App signing](https://developer.android.com/studio/publish/app-signing)
- [Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)
- [Screen compatibility](https://developer.android.com/guide/practices/screens_support)
- [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)
