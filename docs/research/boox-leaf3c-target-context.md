# BOOX Leaf3C 目标设备与使用上下文

状态：目标设备决策完成；实现前仍需完成
[Leaf3C 真机身份取证任务](https://github.com/ysimo0504/CodexBar/issues/13)
更新时间：2026-07-22

## 决策摘要

CodexBar Ink 的首个目标设备确定为 **文石 BOOX Leaf3C**，不是外观和尺寸相近的 BOOX Go Color 7。两者是不同型号，不能互用型号、固件兼容性或设备 SDK 行为结论。

首版使用上下文确定为：

- 单用户、个人设备、APK 侧载，不以应用商店发布为前提。
- 日常放在桌面或床头支架上作为专用仪表盘；显示期间通常连接 USB-C，电池只承担短时移动。
- 7 英寸竖屏、全屏、单列、高对比度仪表盘；颜色仅作辅助，不承载状态语义。
- Mac 上运行 Usage Host；Leaf3C 作为只读 Reader Client。
- 两端处于同一受信任家庭或办公 WLAN；Reader 只持有 Dashboard API token，不持有任何上游 Provider 凭据。
- Activity 可见时每 5 分钟轮询一次；应用恢复时立即同步；进入后台或设备休眠后停止 5 分钟循环。
- 设备醒来后先显示本地 last-good 快照并立即同步。不以“休眠期间持续更新”作为 MVP 能力。
- 专用显示时保持应用在前台；MVP 不承诺无人值守 24/7 常亮。设备休眠与自动关机仍由用户的
  BOOX 电源设置控制，未来若增加应用内常亮开关，只允许在接电时显式开启。

用户看到的“4.2”应先按 **BOOX 固件版本**理解，不能解释为 Android 4.2，也不能由此推出 Android 主版本或 API Level。Leaf3C 的 Android 版本、固件完整构建号和固件渠道必须由真机确认后再锁定构建配置。

## 证据与置信度

| 结论 | 当前判断 | 置信度 | 实现前动作 |
| --- | --- | --- | --- |
| 目标型号是 BOOX Leaf3C | 用户给出的设备名与 BOOX 官方手册目录中的型号一致 | 高 | 在“关于设备”和 ADB 中记录精确 model、device、fingerprint |
| Leaf3C 与 Go Color 7 是不同型号 | 官方支持资料分别使用不同产品名；不能用 Go Color 7 资料填补 Leaf3C 空白 | 高 | 测试记录只接受真机标识，不做营销名猜测 |
| 7 英寸 Kaleido 3，1680 × 1264 | 上市时参数卡与 E Ink 官方 7 英寸 Kaleido 3 面板信息相符 | 高 | 用 `wm size`、截图和实际 insets 确认应用可用区域 |
| 黑白 300 ppi、彩色 150 ppi | E Ink 官方 Kaleido 3 说明与 Leaf3C 参数卡一致 | 高 | UI 不依赖彩色区分，按灰阶可读性验收 |
| 4 GB RAM、64 GB eMMC、2300 mAh | 同期保存的 Leaf3C 产品参数卡 | 中高 | 用 `/proc/meminfo`、`df` 和实机续航测试确认运行约束 |
| 首发系统为 Android 11 | 同期 Leaf3C 参数卡和上市报道 | 中高 | 必须读取当前真机 `ro.build.version.*`；不以首发信息代替当前状态 |
| Android 11 对应 API 30 | Android 官方版本文档 | 高 | 若真机仍为 Android 11，应看到 SDK 30 |
| 真机当前固件为 4.2 | 用户观察；BOOX 官方确认 4.2 正在分批推送，但未找到 Leaf3C 的公开 4.2 兼容清单 | 待确认 | 记录设置页截图、`ro.build.display.id`、incremental 和更新时间 |
| Leaf3C 已公开进入 4.1.1 领航版范围 | BOOX 中国官方支持页明确列出 Leaf3C | 高 | 仅作升级历史旁证，不据此推断用户固件渠道 |
| BOOX Android SDK 可用于刷新控制 | Onyx 官方 Android Demo 与 E Ink 开发文档持续维护 | 高 | 运行时探测 Leaf3C/当前固件实际支持的模式，不硬编码假设 |
| 5 分钟后台周期可由 WorkManager 保证 | 不成立；Android 官方规定周期 Work 最小间隔为 15 分钟，休眠还会延迟任务 | 高 | 5 分钟只在前台可见时运行，后台不承诺刷新 |

## 设备与屏幕基线

以下参数是设计基线，不是替代真机预检的最终构建常量。

| 项目 | 基线 |
| --- | --- |
| 产品 | 文石 BOOX Leaf3C，中国市场型号 |
| 屏幕 | 7.0 英寸 E Ink Kaleido 3 彩色电子纸 |
| 面板地址分辨率 | 1680 × 1264 |
| 像素密度 | 黑白 300 ppi；彩色滤色层 150 ppi |
| 内屏尺寸 | 约 141.9 × 106.8 mm |
| 处理器 | 高通八核、最高 2.0 GHz；现有可靠参数卡未给出精确 SoC 型号 |
| 内存/存储 | 4 GB LPDDR4X / 64 GB eMMC 5.1，支持 TF 卡 |
| 无线 | 2.4 GHz 与 5 GHz Wi-Fi，Bluetooth 5.0 |
| 接口 | USB Type-C |
| 电池 | 2300 mAh |
| 尺寸/重量 | 约 156 × 137 × 6 mm / 190 g |
| 首发系统 | Android 11；当前系统必须真机确认 |

不要在代码或文档中把处理器写成 Snapdragon 662。可靠的 Leaf3C 参数卡只确认“高通八核 2.0 GHz”；精确 SoC、ABI 和硬件代号应从真机读取。也不要把彩色层的 150 ppi 换算成另一组应用布局分辨率；Android 仍按系统报告的可用像素和 density 布局。

## “固件 4.2”与 Android 版本

这里存在两个独立的版本命名空间：

1. **BOOX 固件/系统体验版本**：例如 3.5.4、4.1.1、4.2，决定 BOOX 自有 UI、阅读能力和可能的 E Ink 优化行为。
2. **Android 平台版本/API Level**：例如 Android 11 / API 30，决定 APK 兼容性、权限、网络安全策略和后台执行规则。

BOOX 官方已公布 Firmware V4.2，并说明更新按批次推送、功能因型号而异，应以设备内更新日志为准。现有公开页面没有提供可用于确认 **Leaf3C 已获稳定版 4.2** 的型号清单。中国官方资料能确认 Leaf3C 曾获得 3.5.4 正式更新，并被列入 4.1.1 领航版设备范围，但这不能证明用户手中的 4.2 属于稳定版、领航版或其他渠道。

因此当前记录为：

- “Leaf3C 上显示 4.2”是合理但尚未取证的用户观察。
- 不把 4.2 写成 Android 版本。
- 不假设 BOOX 固件升级一定改变或一定不改变 Android 主版本。
- 在取得 `ro.build.version.release` 和 `ro.build.version.sdk` 前，不锁死 Gradle `minSdk`。
- 若真机确认为 Android 11 / API 30，可将 API 30 作为首台设备基线；Onyx 专用能力仍需运行时探测。

## MVP 使用上下文

### 方向与握持

- 产品设计方向为竖屏。
- 应支持两个竖屏旋转方向，建议 Android 使用 `sensorPortrait`，以适应实体翻页键位于左侧或右侧的握持方式。
- 在真机检查实体键、系统导航条、状态栏和切口前，不锁定绝对的 0° 或 180°。
- 使用响应式 dp 布局，不把 1264 × 1680 写成固定 View 尺寸。
- MVP 不依赖实体翻页键；若后续映射按键，先通过 `getevent` 记录真实 keycode。

### 日常模式与供电

推荐默认模式是“Leaf3C 放在固定支架、通常接入 USB-C，设备清醒且仪表盘位于前台时用于桌面扫一眼”，
而不是全天候电子标牌：

- 首次进入或从休眠恢复：立即读取本地 last-good，再请求 Host。
- 前台可见：每 5 分钟读取一次 Dashboard Snapshot。
- 后台或休眠：停止前台定时器；不使用 wake lock、精确闹钟或常驻前台服务来维持 5 分钟刷新。
- 手动“立即同步”：读取 Host 当前缓存语义，不绕过 Host 强制探测上游 Provider。
- 默认遵循设备自动休眠和自动关机设置。
- 如果未来增加桌面常亮开关，只在充电时允许 `keepScreenOn`，并明确提示电池和残影成本。
- 不承诺依靠 2300 mAh 电池连续运行整天；后续真机验收分别记录接电显示与短时离电使用的功耗。

BOOX 官方说明默认休眠会关闭音频、Wi-Fi 和蓝牙以节电，也允许用户在电源管理中配置休眠后的连接行为。应用必须把 Wi-Fi 断开、Mac 休眠和 Host 暂不可达视为正常状态：保留 last-good、展示采集时间与陈旧状态，恢复网络后重试。

### 网络边界

- Mac Usage Host 与 Leaf3C 位于同一受信任 WLAN。
- 首次配置可使用 Mac 的固定私网 IPv4 地址；建议路由器设置 DHCP 保留。
- 不把 mDNS、IPv6 或公网可达性作为 MVP 前提。
- 路由器/AP 必须关闭客户端隔离，Mac 防火墙必须只放行预期端口与本地网段。
- Reader 使用短权限 Dashboard API bearer token；不得复制 Codex、Claude 或其他 Provider 的凭据和 Cookie。
- 明文 HTTP + bearer token 只可作为显式的受信任局域网开发配置，不等同于安全传输。
- Android 目标版本默认限制明文流量。若 MVP 暂用 HTTP，必须由专用开发构建或限定目标地址的 network security config 显式启用；不能全局、静默放开到生产配置。
- TLS、Tailscale 或其他跨网安全访问属于后续传输边界工作，不扩进本设备识别任务。

## 开发与界面约束

### Android/应用生命周期

- `INTERNET` 是基础权限；网络请求、token 存储和错误文本必须与 Provider 凭据隔离。
- Android 官方 WorkManager 周期任务最短为 15 分钟，且 Doze/省电策略会推迟任务。5 分钟刷新应由前台生命周期感知循环承担。
- `onResume` 立即同步；`onStop` 停止循环。进程被系统终止后依靠持久化的净化 last-good 恢复。
- 不在首版申请忽略电池优化，不使用精确闹钟，不要求自启动。只有实机证明恢复体验无法满足需求时再评估 BOOX 自启动选项。
- BOOX 的“应用冻结”可能阻止后台/恢复行为；侧载后应确认本应用未被冻结并完成一次首次启动。
- ABI、当前 API、Web/HTTP 行为与包安装能力都以 ADB 预检为准。

### 电子纸渲染

- 一列高对比度信息层级；所有状态在纯灰阶下仍可识别。
- 避免动画、渐变、透明叠加、阴影和连续滚动。分页或稳定区域更新优先。
- Onyx 指南建议正文不小于 14sp；边缘交互目标至少 48dp，居中图标至少 36dp。
- 仅在 Dashboard Snapshot 语义变化时重绘；时间文本不要造成每秒刷新。
- 首屏、手动刷新和周期清残可使用全刷；卡片局部变化优先局刷，但最终 waveform 必须在 Leaf3C 真机 A/B 测试。
- Onyx SDK/刷新调用全部封装在 `DisplayAdapter` 后，普通 Android 渲染保持可测试。
- 首轮候选测试可比较 NORMAL/GU 与 REGAL；GC 全刷用于冷启动、手动清残和经实测确定的周期。不要在设备识别阶段写死模式或频率。
- 应用必须能在 Onyx API 不可用时退化为标准 Android 重绘。

## 真机 ADB preflight

### 准备

在 BOOX 的应用管理/调试设置中启用 USB 调试，连接 Mac，接受设备上的 RSA 授权。只读取和记录状态；本预检不自动修改电源、旋转或网络设置。

将脱敏后的输出和设置页截图附到后续设备验证记录。公开记录不得包含 ADB 序列号、账号、SSID、MAC
地址、设备私网 IP、Dashboard token 或其他凭据；命令中 `<MAC_LAN_IP>`、`<APK_PATH>` 和
`<PACKAGE_ID>` 需替换为实际值。

### 1. 连接和设备身份

```bash
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.brand
adb shell getprop ro.product.model
adb shell getprop ro.product.name
adb shell getprop ro.product.device
adb shell getprop ro.build.product
adb shell getprop ro.build.fingerprint
```

验收：营销名、model/device/product 和 fingerprint 一并保存。若字符串只显示通用 BOOX 名称，不用营销名反推硬件；以完整输出和设置页照片共同归档。

### 2. Android、固件和安全补丁

```bash
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.security_patch
adb shell getprop ro.build.display.id
adb shell getprop ro.build.id
adb shell getprop ro.build.version.incremental
```

验收：分别记录 Android release/API Level 与 BOOX display/build/incremental，不把任一字段改写为另一命名空间。另在“设置 > 关于设备”拍摄产品型号、固件完整版本、Android 版本和安全补丁日期。

### 3. ABI、SoC、内存和存储

```bash
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.hardware
adb shell getprop ro.soc.manufacturer
adb shell getprop ro.soc.model
adb shell cat /sys/devices/soc0/soc_id
adb shell cat /proc/meminfo
adb shell df -h /data
```

部分属性或 sysfs 文件可能为空或权限不足。应如实记录，不用第三方同尺寸机型补值。

### 4. 显示、density 和旋转

```bash
adb shell wm size
adb shell wm density
adb shell settings get system accelerometer_rotation
adb shell settings get system user_rotation
adb shell dumpsys input | rg 'SurfaceOrientation'
adb exec-out screencap -p > /tmp/codexbar-ink-leaf3c.png
```

验收：记录物理/override 尺寸、density、系统旋转设置和两种竖屏方向的截图；确认状态栏、导航区、实体按键侧和触控目标。可选按键取证：

```bash
adb shell getevent -l
```

只短时运行并按一次目标实体键，记录事件后用 Ctrl-C 结束。

### 5. WLAN、电源和休眠

```bash
adb shell cmd wifi status
adb shell ip addr show wlan0
adb shell ping -c 3 <MAC_LAN_IP>
adb shell settings get system screen_off_timeout
adb shell settings get global stay_on_while_plugged_in
adb shell dumpsys power | rg 'mWakefulness|mIsPowered|Display Power'
adb shell dumpsys deviceidle
adb shell dumpsys battery
```

同时在“设置 > 电源管理”记录自动休眠、自动关机和休眠后 Wi-Fi 延迟断开策略。完成一次“前台同步 → 熄屏/休眠 → 唤醒 → Wi-Fi 重连 → 立即同步”流程，确认 last-good 在断网期间仍可读。

### 6. 安装、启动和日志

```bash
adb install -r <APK_PATH>
adb shell pm list packages | rg codexbar
adb shell monkey -p <PACKAGE_ID> -c android.intent.category.LAUNCHER 1
adb shell dumpsys package <PACKAGE_ID> | rg 'versionName|versionCode|targetSdk|minSdk|primaryCpuAbi'
adb logcat --clear
adb logcat | rg 'CodexBarInk|AndroidRuntime'
```

安装后完成一次正常首启，并在 BOOX 应用管理中记录：应用冻结状态、是否需要自启动、当前 E Ink 优化和刷新模式。MVP 默认不要求自启动；先验证从桌面手动启动、恢复和休眠后的行为。

### 7. 应用待机与系统相关键

```bash
adb shell am get-standby-bucket <PACKAGE_ID>
adb shell dumpsys deviceidle whitelist
adb shell settings list system | rg -i 'sleep|rotation|wifi'
adb shell settings list global | rg -i 'idle|wifi|stay_on'
```

这些命令用于发现真实配置，不用于批量写入。BOOX 自有设置键可能随固件变化；没有输出时以设置 UI 与行为测试为准。

## 预检后才能锁定的未知项

- 设备精确 model、codename、product 和 build fingerprint。
- 当前 Android release、API Level、安全补丁日期。
- “4.2”的完整构建号、推送渠道、安装日期及设备内 changelog。
- SoC 精确型号、ABI 列表和可用存储。
- `wm size`、density、系统栏/insets 与两个竖屏方向的实际可用区域。
- 休眠时 Wi-Fi 的断开时机、唤醒重连耗时和 AP 客户端隔离状态。
- 当前固件上可用的 Onyx SDK 接口、刷新 waveform 和局刷残影表现。
- 应用冻结、自启动、待机 bucket 对恢复行为的影响。
- 5 分钟前台轮询的实际耗电、温升和残影清理频率。
- 实体翻页键 keycode，以及是否值得纳入 MVP。
- 专用局域网 HTTP 配置在当前 Android 网络安全策略下的实际连通性。

## 下游约束与交接

本任务可以据此关闭“目标设备与使用上下文”的选择，但不能把待真机确认项当作已验证规格：

- BOOX 约束实验：负责真机刷新模式、局刷/全刷、残影、冻结和电源行为。
- Reader 渲染循环：负责合成 fixture、schema v1 解码、语义变化重绘、按 Provider 保留 last-good、5 分钟前台循环。
- 传输与凭据：负责 token/Android Keystore、局域网 HTTP 专用配置及以后 TLS/Tailscale 方案。
- 信息层级：按 7 英寸竖屏单列设计，不依赖颜色，满足 14sp 与 48dp 触控基线。
- 最终 MVP 验收：必须附本报告所列 ADB 输出、设置页截图和一次完整睡眠/唤醒/重连证据。

在完成一次 ADB preflight 前，不锁定 Gradle `minSdk`、ABI、绝对屏幕尺寸、硬编码刷新模式或常亮策略。

## 来源

### 官方与一手资料

- [BOOX 中国支持：用户手册目录（列出 Leaf3C）](https://support.boox.com/a7fc/1dea)
- [BOOX 单触系列用户手册，2024-12-10 PDF](https://onyx-static.oss-cn-shenzhen.aliyuncs.com/manual/BOOX%E5%8D%95%E8%A7%A6%E7%B3%BB%E5%88%97(20241210)%20.pdf)
- [BOOX 中国支持：第三方 APP 安装](https://support.boox.com/#/document/69bb71d0de2ae6af66489289)
- [BOOX 中国支持：APP 使用、冻结与 USB 调试](https://support.boox.com/#/document/69bb6fd7de2ae6af664891c9)
- [BOOX 中国支持：休眠、Wi-Fi 与自动关机](https://support.boox.com/#/document/69bb6e42de2ae6af66489165)
- [BOOX 中国支持：电池与冻结应用建议](https://support.boox.com/#/document/69bb7531de2ae6af6648942b)
- [BOOX 中国支持：OS 4.1.1 领航版设备范围](https://support.boox.com/#/document/69bb6ce5de2ae6af664890a7)
- [BOOX 中国支持：Leaf3C 3.5.4 正式更新](https://support.boox.com/a8d6/07bf)
- [BOOX 官方：Firmware V4.2 更新公告](https://shop.boox.com/blogs/news/firmware-v4-2-update)
- [BOOX 官方固件页：功能因型号而异](https://shop.boox.com/pages/firmware)
- [E Ink 官方：Kaleido 3](https://www.eink.com/brand/detail/Kaleido3?pubDate=20251117)
- [E Ink 官方：7 英寸 Kaleido 3 面板 EC070KC1](https://www.eink.com/product/detail/EC070KC1)
- [Onyx 官方 Android Demo](https://github.com/onyx-intl/OnyxAndroidDemo)
- [Onyx 官方 E Ink 开发指南](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Eink-Develop-Guide.md)
- [Onyx 官方刷新模式说明](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Update-Mode.md)
- [Onyx 官方屏幕更新说明](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Screen-Update.md)
- [Android 官方：Android 11 / API 30](https://developer.android.com/about/versions/11)
- [Android 官方：WorkManager 周期任务约束](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Android 官方：闹钟、Doze 与后台调度](https://developer.android.com/develop/background-work/services/alarms)

### 上市时参数保存证据

BOOX 当前公开支持站未提供 Leaf3C 的完整产品规格页。以下同期报道保存了 Leaf3C 上市参数和产品参数卡，用于补足硬件基线；涉及当前软件状态的字段仍以真机为准：

- [IT之家：文石 BOOX Leaf3C 上市报道](https://www.ithome.com/0/752/163.htm)
- [报道中保存的 Leaf3C 产品参数卡原图](https://img.ithome.com/newsuploadfiles/2024/2/a737e11d-022e-4a16-95f1-366dacdbed04.jpg)
