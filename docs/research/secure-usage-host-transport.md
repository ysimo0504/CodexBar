# CodexBar Ink 安全 Usage Host 传输方案

最后核验：2026-07-22

## 问题

BOOX 优先的 CodexBar Ink 应如何连接 Mac 上的 CodexBar Usage Host，既支持同一局域网，也支持离开本地网络后的个人远程读取，同时不把 provider 凭据、reader bearer token 或原始 usage 路由暴露给不受信任的网络？

范围只包含个人设备上的只读 snapshot 传输。公网多用户服务、账号共享、写操作和 provider 凭据下发不在本阶段范围内。

## 决策

**真实账号 MVP 默认采用 Tailscale + HTTPS，但必须先修复当前 Host、路由和 token 边界。**

目标拓扑：

```text
BOOX Leaf3C / CodexBar Ink / Android Keystore
        |
        | HTTPS + Authorization: Bearer ...
        | Tailscale tailnet；精确 grant/ACL
        v
Mac 的稳定 MagicDNS / *.ts.net 名称
  Tailscale Serve：TLS 自动终止
        |
        | 只允许 /dashboard/v1/snapshot
        | 后端 Host 受控重写或精确允许
        v
127.0.0.1:8080 / codexbar serve
```

不开放路由器端口，不使用 Tailscale Funnel，不发布公网。Tailscale 提供设备间 WireGuard 加密和 tailnet 身份；HTTPS 提供标准服务端身份校验；bearer 继续作为应用层最小授权边界。

当前代码不能直接按此拓扑上线，存在三个阻断项：

1. **Tailscale Serve Host 不兼容。** Serve 保留客户端的 `Host`；loopback Host 只允许 `127.0.0.1`、`localhost`、`[::1]`，正常 `device.tailnet.ts.net` 请求会在路由前收到 403。
2. **loopback 原始路由可被代理放大。** loopback 下 `/usage`、`/cost` 不要求 bearer；若代理整个 backend origin，tailnet 客户端会得到未认证数据路由。
3. **reader token 扩散到 provider 子进程环境。** `CODEXBAR_DASHBOARD_TOKEN` 来自进程环境；TTY runner 默认继承同一环境并交给 provider CLI。

真实账号 MVP 的 go 条件：

- 代理只暴露 snapshot，或 proxy 模式强制所有数据路由认证；
- 精确支持 external HTTPS Host，同时保留 DNS rebinding 防护；
- reader token 从所有 provider/helper 子进程环境剔除；
- Leaf3C 真机证明 Tailscale VPN、MagicDNS、HTTPS 和休眠恢复可用；
- snapshot provider 错误文本完成 display-safe hardening。

## 当前实现事实

### 监听与传输

`codexbar serve` 默认监听 IPv4 `127.0.0.1:8080`，实现原始 HTTP/1.1，没有 TLS（[`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L15-L37)，[`CLILocalHTTPServer.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLILocalHTTPServer.swift#L274-L299)）。

非 loopback 监听必须同时提供 bearer 并显式传入 `--allow-plain-http`；启动日志明确说明 token 在每次请求中以明文跨网络发送（[`CLIServeAuth.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeAuth.swift#L107-L143)，[`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L649-L699)）。这项开关是危险确认，不是加密。

### 路由与认证

服务有 `/health`、`/usage`、`/cost`、`/dashboard/v1/snapshot` 四个 GET 路由（[`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L40-L73)）。

snapshot 始终先做 bearer 认证，再读取配置或缓存。非 loopback 监听会对三个数据路由都认证；loopback 监听只对 snapshot 认证（[`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L101-L136)，[`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L821-L905)）。代理改变了“loopback 等于只限本机”的前提。

认证只接受 `Authorization: Bearer`，拒绝 query token；token 经 SHA-256 后常量时间比较，未配置 token 时 fail closed（[`CLIServeAuth.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeAuth.swift#L4-L50)）。

### Host 与 Tailscale Serve 缺口

解析器要求且只允许一个 `Host` 和至多一个 `Authorization`。loopback allowlist 只接受 loopback 名称（[`CLILocalHTTPServer.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLILocalHTTPServer.swift#L31-L69)，[`CLILocalHTTPServer.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLILocalHTTPServer.swift#L109-L145)）。

Tailscale Serve 的官方实现为普通 TCP 后端设置目标 URL 后，又把出站 `Host` 设回入站 `Host`。访问 `https://mac-name.tailnet-name.ts.net/...` 时，后端看到该 FQDN，不是 `127.0.0.1`（[Tailscale `serve.go`](https://github.com/tailscale/tailscale/blob/main/ipn/ipnlocal/serve.go#L959-L980)）。当前 CodexBar 会返回 403。

不得以允许任意 Host 修复；这会撤销 DNS rebinding 防护。可接受修复：受控代理固定重写后端 `Host: 127.0.0.1`，或 Usage Host 增加显式 exact external Host allowlist / trusted-loopback-proxy 模式。不得信任非 loopback 对端的任意 `X-Forwarded-*` 或 Tailscale identity header。

### token 进程边界缺口

Host 优先从 `CODEXBAR_DASHBOARD_TOKEN` 读取 token，因为 argv 会通过 `ps` 泄露（[`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L715-L740)）。

但进程环境仍保留该值。TTY runner 默认使用整个 `ProcessInfo.processInfo.environment`，扩充后原样传给 provider CLI（[`TTYCommandRunner.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Host/PTY/TTYCommandRunner.swift#L640-L655)，[`TTYCommandRunner.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Host/PTY/TTYCommandRunner.swift#L703-L711)）。

风险不是 provider CLI 已知会读取该变量，而是 token 已离开最小知情边界：诊断、崩溃报告、第三方 CLI 或其子进程可能意外观察它。真实账号 MVP 前，应在统一的子进程环境 seam 删除该变量，并以 focused tests 固定不变量。

## 威胁模型与不变量

需要防御：

- 同一 Wi-Fi 上的被动嗅探与主动 ARP/DNS 中间人；
- bearer 被日志、URL、剪贴板、截图、备份、argv 或子进程环境带出；
- 代理意外暴露 `/usage`、`/cost` 或未来新增路由；
- DNS rebinding、Host confusion 和宽泛 forwarded-header 信任；
- BOOX 丢失后的离线数据提取；
- Mac 改名、证书变化、token 轮换和 tailnet 认证过期后的静默降级；
- redirect 把 Authorization 发往不同 origin。

必须保持：

- provider 凭据永远留在 Mac；reader 只读取 identity-redacted、provider-generic snapshot；
- bearer 只进 Authorization header；真实数据不走普通 LAN HTTP；
- TLS/hostname 失败绝不自动降级；代理默认拒绝未知路径；
- reader 网络失败保留最后成功快照；认证失败不触发 provider 查询或缓存预热。

## 方案比较

| 方案 | 传输安全 | 运维 | Leaf3C 适配 | 结论 |
| --- | --- | --- | --- | --- |
| 普通 LAN HTTP | 无；token/数据可观察和修改 | 低 | 需 cleartext opt-in | 仅 fixture spike |
| Tailscale IP + HTTP | overlay 加密；应用层仍 HTTP | 中 | Tailscale + cleartext opt-in | 过渡验证 |
| Tailscale Serve HTTPS | overlay + HTTPS；无路由器端口 | 中 | 需验证 VPN/休眠 | 真实账号默认 |
| LAN Caddy HTTPS | HTTPS；私有 CA 或真实域名 | 高 | 需安装/嵌入 CA | 无 Tailscale 备选 |
| 公网 HTTPS/Funnel | 攻击面显著增加 | 高 | 可行 | 不进个人 MVP |

### 普通 LAN HTTP

Android 9/API 28 起默认禁用 cleartext；HTTP 可被监听和篡改（[Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)，[cleartext 风险](https://developer.android.com/privacy-and-security/risks/cleartext-communications)）。它只适合合成 fixture：不连接真实 provider、显式危险标记、测试后清除 token、发布构建默认关闭。

### Tailscale IP + HTTP

绑定 Mac 的 Tailscale IPv4 并访问 `http://100.x.y.z:8080` 可绕过 Serve 的 FQDN Host 冲突；WireGuard 仍加密设备间链路（[Tailscale Security](https://tailscale.com/security)）。但 Android 需对精确目标 cleartext opt-in，URL 依赖 IP，且应用层没有 HTTPS 身份。它只用于 BOOX VPN 链路验证。

若使用，必须绑定精确 Tailscale IP 而非 `0.0.0.0`，保留 non-loopback 全数据路由认证，并以 grant 限制 Leaf3C 到 Mac 的单一端口。

### Tailscale Serve HTTPS

Serve 只在 tailnet 内共享服务；Funnel 才公开互联网。Serve 自动终止 HTTPS，并支持后台配置跨重启保持（[Tailscale Serve](https://tailscale.com/kb/1242/tailscale-serve)）。本方案禁用 Funnel。

MagicDNS 提供设备名（[MagicDNS](https://tailscale.com/kb/1081/magicdns)）。HTTPS 的完整 `*.ts.net` 机器 FQDN 会进入公开 Certificate Transparency 日志，机器名不得含真实姓名、客户名或秘密（[Tailscale HTTPS](https://tailscale.com/kb/1153/enabling-https)）。

tailnet 默认策略不能当最小权限。应写 exact Leaf3C -> Mac HTTPS grant/ACL（[access controls](https://tailscale.com/kb/1018/acls)）。Serve 只能在 Host/path hardening 后成为默认；直接代理整个 `127.0.0.1:8080` 不合格。

### Caddy / 本地 HTTPS

无 Tailscale 时，可由 Caddy 终止 TLS、固定重写后端 Host，并只代理 snapshot；现有文档已有此形态（[`dashboard-api.md`](../dashboard-api.md)）。公开域名使用公有 CA；纯 LAN 通常需 Caddy 本地 CA，再显式安装根证书或仅由 Ink 的 Network Security Configuration 信任（[Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https)）。

不得 trust-all，不得跳过 hostname verification，不得证书失败后回退 HTTP（[unsafe TrustManager](https://developer.android.com/privacy-and-security/risks/unsafe-trustmanager)，[unsafe hostname verifier](https://developer.android.com/privacy-and-security/risks/unsafe-hostname)）。Pinning 不是首选；若未来采用必须有 backup key 和轮换方案。

## 分阶段交付

### A：合成数据传输 spike

- Mac 使用 canonical fixture host；同 LAN 可临时显式 HTTP；
- Leaf3C 手工输入 IP，不存真实 bearer，不运行 provider probe；
- 覆盖成功、超时、401、500、坏 JSON、未知 schema；
- 结束后清除测试配置。

### B：Tailscale 真机可行性门

- 从官方渠道安装 Android universal APK；
- 验证 Leaf3C 的实际 Android API，不能把“BOOX 系统 4.2”当 Android 4.2；
- Mac 使用受支持的单一 Tailscale 变体（[macOS variants](https://tailscale.com/kb/1065/macos-variants)）；
- 用 fixture 验证 MagicDNS、HTTPS、direct/DERP；
- 验证锁屏、深度休眠、Wi-Fi 切换、重启、BOOX 冻结策略、VPN 通知和电池优化；
- 不以官方 minSdk 可安装替代真实设备结论。

官方 Android 客户端当前声明 `minSdk 26`，理论上兼容 Android 11，并提供独立 universal APK；这只证明安装下限，不证明 BOOX 后台 VPN 行为（[Tailscale Android](https://github.com/tailscale/tailscale-android)，[`android/build.gradle`](https://github.com/tailscale/tailscale-android/blob/main/android/build.gradle)）。

### C：个人真实账号 MVP

- 完成 Host/path/token 三个阻断项，只暴露 snapshot；
- Tailscale Serve HTTPS，不用 Funnel；写 exact Leaf3C -> Mac:443 grant；
- 使用随机 256-bit reader token；Android Keystore 加密保存，关闭应用备份；
- snapshot error 只显示 host 产生的安全分类；
- 真实 provider smoke test 必须由用户明确授权，避免 Keychain 提示。

### D：无 Tailscale 的 LAN HTTPS

- Caddy 只代理 snapshot，使用精确 Host/SNI；
- 私有 CA 明确配对与撤销；发布构建不全局启用 cleartext；
- 作为高级手动配置，不作为首次启动默认。

## token、配对与轮换

RFC 6750 将 bearer 定义为“持有即可使用”，要求保护存储和传输并使用 TLS；Authorization header 是推荐方式，query 参数因日志泄露不应使用（[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)）。

### 生成与配对

- 密码学安全随机源生成至少 32 bytes，以 64 位 hex 或无填充 base64url 编码；
- 不从设备名、用户名或时间派生；UI 只显示短指纹；
- Mac 本地 UI 短暂显示版本化 QR：`version`、HTTPS `baseURL`、`token`、稳定随机 `hostID`；
- QR 不是一次性密钥交换：截图可重复使用静态 bearer，必须允许立即轮换；
- 后续可用短期 pairing code 换取每设备 token；它需要新 API，不阻塞单用户 MVP。

### reader 存储

- Keystore 生成不可导出 key，token 密文进入 app 私有存储；
- `allowBackup=false`，不进入云备份或设备迁移；
- 不写日志、analytics、crash breadcrumb、URL、通知、截图或默认剪贴板；
- 忘记 Host 时删除 token 密文、key alias 和本地快照。

### Host 存储与子进程

- 不把长期 token 放 argv；Host 生命周期负责生成、持久化、启动注入和轮换；
- 优先从 Keychain 或权限受限 secret source 读入内存；
- 无论来源如何，provider/helper 子进程环境必须显式删除 token；
- 日志只记录 token 指纹或“已配置”。

### 轮换

当前单静态 token 只能随 Host 重启替换，旧 token 立即失效。MVP 流程：Mac 生成尚未启用的新
token -> Leaf3C 暂停同步并通过本地 QR 覆盖保存 -> Host 重启切换 -> Leaf3C 恢复同步并验证。
401 保留 last-good、停止高频重试并要求重新配对。未来每设备 token 可独立撤销，但应由明确
多设备需求驱动。

## 发现策略

默认用 QR 或手工输入精确 HTTPS base URL，不做 mDNS、广播或 LAN 扫描。MagicDNS 已提供解析；精确 FQDN 参与 TLS 主机名校验；QR 可携带 hostID 检测“同名不同 Host”。

Mac 改名、tailnet DNS 后缀或证书身份变化时必须显式重新配对，不能静默改用 IP/HTTP。Tailscale node key 过期是独立状态；reader 应区分“tailnet 未认证”和“Usage Host token 失效”（[key expiry](https://tailscale.com/kb/1028/key-expiry)）。

## 故障恢复

reader 始终保留最后一次成功且 schema 可解析的快照，显示收到时间与 stale 状态。

| 失败 | 状态 | 自动行为 |
| --- | --- | --- |
| DNS/Tailscale 未连接 | Host 离线 | last-good；有界退避；前台恢复重试 |
| TLS/hostname/CA | 安全连接失败 | 硬失败；绝不降级 HTTP |
| 401 | 配对失效 | 保留 last-good；停止高频重试；重新配对 |
| 403 | Host/policy 错误 | 保留 last-good；检查 Host/grant |
| 404/405 | 路径/版本错误 | 不探测其他数据路由 |
| 未知 schema | 客户端不兼容 | 保留 last-good；提示升级 |
| timeout/5xx | Host 暂时不可用 | 指数退避 + jitter |
| provider card error | 部分数据失败 | 显示可用字段 + 安全错误分类 |

snapshot 最好禁止 redirect；最低要求是不把 Authorization 跟随到不同 scheme、host 或 port。401 不自动删除历史；用户确认“忘记 Host”后才删除。

## Leaf3C 验收

目标记录为 BOOX Leaf3C、BOOX 系统 4.2。先通过系统信息或 ADB 记录真实 Android version、API level、build fingerprint 和网络栈版本。

必须通过：

- 官方 Tailscale APK 安装、登录、VPN 授权；
- 仅 Leaf3C 被 grant 到 Mac HTTPS 端口；
- MagicDNS FQDN、证书、正确 token 200、错误 token 401；
- `/usage`、`/cost`、未知路径均不可取得数据；
- 受控 Host 可用，恶意 Host 仍 403；
- Wi-Fi/Mac 睡眠/Leaf3C 锁屏恢复，direct 与 DERP；
- node key 过期、Mac 改名、token 轮换；
- TLS 错误不回退 HTTP；
- token 不出现在 logcat、崩溃日志、URL、备份和 provider 子进程环境；
- 所有失败中 last-good 保持可读并标记 stale。

真机门先用 fixture。全部边界通过后，才在用户明确授权下做真实 provider smoke test。

## 实现与 ticket 影响

地图限定为规划，所以本次不新增要求编写产品代码的 Wayfinder task。以下差距不阻止继续定义
MVP，但必须进入最终 issue-ready 交付计划中的窄实现项：
**Harden the Usage Host proxy and reader-token boundary**。

验收：

- exact external Host allowlist 或 only-loopback trusted proxy；
- 不接受 wildcard Host，不信任非 loopback forwarded headers；
- proxy 模式只提供 snapshot，或强制全部数据路由 bearer；
- Tailscale Serve 与 Caddy Host/path focused tests；
- provider/helper 子进程环境统一剔除 reader token；
- 不调用真实账号、browser cookie 或 Keychain。

该实现项阻塞真实账号 reader 验收，不阻塞 fixture spike。Leaf3C 原型任务加入
Tailscale/休眠/DERP；Host 生命周期任务拥有代理形态、token 生成、存储、启动、轮换和
child-env scrub 决策；最终 MVP 边界任务把该实现项写入交付计划和质量门；error hardening 继续
阻塞真实账号；Android 网络层固定 redirect、TLS、401 和 last-good。

## 非目标

- 不使用 Funnel、port forwarding、query token、trust-all、hostname bypass 或 silent downgrade；
- 不把 provider OAuth/API token 发给 reader；不把家庭 Wi-Fi 当明文安全证明；
- 不让 tailnet 默认 allow-all 代替 bearer；不在 MVP 做 LAN scanning；
- 不声称 BOOX 系统版本号等同 Android 版本号。

## 最终结论

Tailscale Serve HTTPS 是个人 MVP 的最佳默认：覆盖同 LAN 与远程、避免公网入口、降低证书运维。但顺序必须是 fixture + Leaf3C 可行性 -> Host/path/token hardening -> safe error/last-good -> exact grant + HTTPS + bearer -> 真实账号。

Serve Host 403、loopback raw 路由经代理泄露、child env token 扩散都是架构边界问题，不能留给部署说明规避。

## 主要来源

- [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)
- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
- [Tailscale Serve](https://tailscale.com/kb/1242/tailscale-serve) / [HTTPS](https://tailscale.com/kb/1153/enabling-https) / [MagicDNS](https://tailscale.com/kb/1081/magicdns)
- [Tailscale Security](https://tailscale.com/security) / [access controls](https://tailscale.com/kb/1018/acls) / [key expiry](https://tailscale.com/kb/1028/key-expiry)
- [Tailscale Android](https://github.com/tailscale/tailscale-android)
- [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https)
