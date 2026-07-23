# CodexBar Ink APK 签名与侧载交付决策

状态：个人 BOOX 侧载方案已确定；公开分发仍被 Onyx 书面许可阻塞。
最后核验：2026-07-23

## 决策摘要

- 正式 BOOX APK 固定使用 application ID `com.ysimo.codexbar.ink`。`fixture`、`debug` 后缀只属于测试包，
  不能成为可升级的正式安装身份。
- 个人侧载使用一份长期自管 release signing key；密钥只在本机离线签名环境和加密备份中存在，
  不进入 Git、CI、BOOX 或 APK 分发目录。
- `versionName` 使用 SemVer；`versionCode` 使用单调递增的 UTC 日历编号 `YYYYMMDDNN`。
- 正常更新使用同证书、较高 `versionCode` 的 APK 执行 `adb install -r`。回滚采用“前向回滚”：从已知良好
  commit 重建，但赋予新的更高 `versionCode`，不尝试覆盖安装低版本 release APK。
- 个人构建只通过 USB/ADB 和用户控制的加密私有归档交付。App 不自更新、不申请安装权限。
- 未来商店或第三方公开分发使用独立 application ID、独立签名与发布管线；不复用个人侧载私钥。
- 截至核验日，Onyx 官方 demo 仓库未声明 license，`onyxsdk-device:1.3.5` POM 也没有 `licenses` 元数据。
  在取得 Onyx 书面再分发许可前，不公开发布包含该 AAR 的 BOOX APK。

## 稳定安装身份

正式个人侧载包的身份为：

| 属性 | 决策 |
| --- | --- |
| application ID | `com.ysimo.codexbar.ink` |
| 签名 alias | `codexbar-ink-release` |
| 发布 tag | `ink-v<versionName>`，例如 `ink-v0.1.0` |
| APK 文件名 | `codexbar-ink-v<versionName>-vc<versionCode>-boox.apk` |

当前 Leaf3C 上的 `com.ysimo.codexbar.ink.fixture.debug` 是可并存的测试包，不是首个正式 release。首个正式包
安装前先核验 application ID、签名证书指纹和版本，之后不得更换其中任何更新身份字段。

Android 会比较已安装 APK 与更新 APK 的证书；证书不一致时只能作为不同包安装，或先卸载旧包。卸载会删除
应用私有配置、token 和 last-good，因为 Reader 明确禁用了备份。

## 密钥创建与保管

项目所有者在一台受控 Mac 上创建一份 RSA 4096 release key，证书有效期 100 年。示意命令中的路径和密码必须
由操作者替换，密码通过交互输入，不放进 shell history、Gradle 文件或环境文件：

```sh
keytool -genkeypair \
  -keystore <private-keystore-path>/codexbar-ink-release.jks \
  -alias codexbar-ink-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 36500
```

保管规则：

1. 主副本放在仅所有者可访问的加密离线存储；不放在源码 checkout。
2. 另做两份加密备份：一份离线介质，一份不同物理位置的受控加密存储。
3. keystore 与 key 使用不同的强随机密码，密码放在密码管理器；恢复说明与密钥分开保管。
4. 每年至少进行一次只读恢复演练：从备份复制到临时隔离目录、签名测试 APK、核对证书 SHA-256，随后安全
   清理临时副本。演练不触碰生产安装。
5. 仓库只记录证书 SHA-256 指纹、alias、创建日期、到期日期和备份演练日期；不得记录私钥或密码。
6. 不做例行轮换。若密钥疑似泄露，立即停止分发；在 Leaf3C Android 11 上没有经过实测的 signing lineage
   迁移前，按“密钥丢失/泄露恢复”处理。

Android 官方建议证书至少覆盖 25 年，并明确自管 signing key 丢失后无法继续更新原 App。100 年有效期降低个人
长期侧载因证书过期而中断的风险，但不替代密钥备份。

## 版本规则

`versionName` 使用 SemVer，例如 `0.1.0`、`0.2.0-rc.1`。`versionCode` 使用：

```text
YYYYMMDDNN
```

- 日期取 UTC；`NN` 为当日从 `01` 到 `99` 的发布序号。
- 例如 2026-07-23 的首个发布是 `2026072301`。
- 每个交付过的 APK 都永久占用其 `versionCode`，不得复用。
- 构建在发布前必须验证新值大于私有发布清单中的最大值。
- 该格式到 2099 年仍低于 Google Play 允许的 `2,100,000,000` 上限。

Android 使用较高 `versionCode` 判断更新并阻止降级；用户看到的版本只来自 `versionName`。即使恢复旧代码，也必须
产生新的更高 `versionCode`。

## 构建产物与来源证明

每次个人 release 生成同一私有目录下的三项文件：

```text
codexbar-ink-v0.1.0-vc2026072301-boox.apk
SHA256SUMS
release.json
```

`release.json` 至少记录：

- application ID、`versionName`、`versionCode` 和签名证书 SHA-256；
- Git commit 和 annotated tag；
- 完整构建命令、Gradle wrapper、AGP、JDK、compile/target/min SDK 版本；
- `onyxsdk-device` 版本及依赖校验值；
- APK SHA-256、构建 UTC 时间和构建机器架构；
- 测试/check 命令与结果。

交付前使用 Android SDK 工具核验，不依赖文件名：

```sh
apksigner verify --verbose --print-certs <apk>
apkanalyzer manifest application-id <apk>
apkanalyzer manifest version-code <apk>
shasum -a 256 <apk>
```

CI 只做无秘密的编译、单元测试和静态检查。release keystore、密码、已签名个人 APK 与私有发布清单均不得上传
CI、GitHub Actions artifact 或公共 GitHub Release。签名只在所有者受控环境中进行。

## 首装与更新

### 首次正式安装

1. 从私有归档取得 APK、`SHA256SUMS` 和 `release.json`，核对 APK SHA-256 与证书 SHA-256。
2. 确认安装目标是预期 BOOX 设备，再执行 `adb install <apk>`。
3. BOOX 可能默认冻结新装第三方 App；安装后检查状态，必要时显式启用该 package。
4. 启动 App，验证版本、Host 配对、前台同步、last-good、休眠唤醒和墨水屏刷新。

### 日常更新

1. 先核对新 APK 的 package、证书和 checksum，确认 `versionCode` 大于已安装值。
2. 执行 `adb install -r <apk>`；不得使用允许测试包的降级参数绕过正式发布规则。
3. 检查 package 仍启用，启动 App，并确认配置、token 和 last-good 保留。
4. 记录设备、旧/新版本、APK checksum、安装时间和验收结果，不记录 token。

## 回滚与密钥事故

### 发布回滚

正式 release 不做原位降级。需要回滚时：

1. checkout 已知良好 commit；
2. 只应用必要的兼容/安全修正；
3. 设置新的更高 `versionCode`，并使用如 `0.1.1-rollback.1` 的 `versionName`；
4. 用同一 release key 签名、重新跑验收，再通过 `adb install -r` 更新。

若前向回滚无法构建，最后手段是卸载并重装旧 APK；该路径会清除本地 token、配置与 last-good，必须重新配对，
并先撤销旧 Host token。

### 密钥丢失或泄露

自管 signing key 无法从证书或旧 APK 重新生成。确认主副本与两份备份均不可恢复，或确认密钥泄露后：

1. 停止交付并撤销该 Reader 的 Host token；
2. 记录身份断裂事件和最后可信 APK checksum；
3. 卸载旧 App，删除其本地数据；
4. 创建新密钥和新发布世代，重新安装并配对；
5. 若旧包仍需暂时保留，恢复包必须使用不同 application ID，不能冒充原位更新。

这是破坏性恢复，不承诺数据迁移。备份演练是避免该结果的主要控制。

## 个人侧载与未来公开渠道

个人 BOOX 渠道的边界：

- APK 只存在于用户控制的加密私有归档，并通过本地 USB/ADB 安装。
- App 不下载 APK、不静默更新，也不申请 `REQUEST_INSTALL_PACKAGES`。
- 个人 signing key 永不上传 Play Console、第三方商店或公共 CI。

未来公开渠道必须另开决策，至少使用独立 application ID、独立 signing key/Play App Signing、独立隐私与安全审查、
商店版本规则以及公开可审计的供应链。个人安装不会自动迁移为商店安装。

Onyx 集成当前只允许个人原型和本地验证。官方
[OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo) 仓库的 GitHub license 元数据为空，根目录没有
license 文件；官方 `onyxsdk-device:1.3.5` POM 也没有许可字段。缺少公开许可不等于获得再分发权。因此：

- 未取得 Onyx 对目标用途和地区的书面许可前，不公开上传包含 vendor AAR 的 BOOX APK。
- 若无法取得许可，公开构建只能移除 Onyx AAR，发布使用标准 Android 刷新的 generic variant。
- 任何公开发布前重新核验仓库、POM、SDK 条款和书面授权；本决策不是法律意见。

## 依据

- [Android：Sign your app](https://developer.android.com/studio/publish/app-signing)
- [Android：Version your app](https://developer.android.com/studio/publish/versioning)
- [Android：How app updates work](https://developer.android.com/google/play/app-updates)
- [Android：Alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution)
- [OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo)
- [Onyx Maven POM 1.3.5](https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-device/1.3.5/onyxsdk-device-1.3.5.pom)
