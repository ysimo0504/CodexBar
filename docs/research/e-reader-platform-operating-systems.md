# E-reader platform comparison for CodexBar Ink

Last verified: 2026-07-22

## Question

Across BOOX, Kindle, Kobo, reMarkable, Supernote, PocketBook, Bigme, iReader, Hanvon,
Meebook, and NOOK, which operating-system base and supported application surfaces make a
CodexBar Ink client feasible?

This comparison covers E Ink readers and note devices, not LCD tablets, raw E Ink panels, or
desktop E Ink monitors. Platform facts are model- and generation-specific. A brand name alone
is not a reliable compatibility boundary.

## Decision

Build the first client as a native Android APK for BOOX.

BOOX has the strongest documented combination for this project:

- normal Android APK installation and, on supported models, Google Play;
- a built-in browser for a low-cost web fallback;
- per-app E Ink display controls for users;
- a maintained public Android SDK with EPD update modes and screen refresh APIs.

Keep `/dashboard/v1/snapshot` platform-neutral. Treat BOOX refresh integration as an Android
adapter, not as part of the snapshot contract. Later ports divide into three different products:

1. Android APK clients for sufficiently open Android readers;
2. native Linux/Qt clients for devices with supported SDK or developer access;
3. a deliberately simple web client for closed devices whose only extension surface is a browser.

The web route is a fallback, not the MVP baseline. A vendor saying “browser” does not prove modern
PWA support, reliable background polling, local-network access, or compatibility with the eventual
dashboard JavaScript bundle.

## Comparison

Legend: **Yes** means a current first-party source documents the path. **Limited** means the path
exists with model, security, or distribution constraints. **Unconfirmed** means no current
first-party documentation was found; it does not mean the capability is technically impossible.

| Platform / family | OS base | APK / third-party apps | Browser / PWA surface | Public SDK / refresh control | CodexBar Ink fit |
| --- | --- | --- | --- | --- | --- |
| **BOOX E Ink tablets/readers** | Android; exact version varies by model. For example, Note Air5 C is Android 15. | **Yes.** BOOX documents Google Play on supported models, browser-downloaded APKs, and computer sideloading. | **Yes** for NeoBrowser/web pages. Installable PWA behavior still needs device testing. | **Yes.** The maintained Onyx Android demo documents EPD refresh APIs, modes, WebView integration, and screensaver hooks. | **Best MVP target.** Native Android plus a BOOX-only display adapter. |
| **Bigme B1051 / B6 generation** | Open Android 14 on the cited models. | **Yes** for Google Play and third-party Android apps. Bare-APK workflow is not documented in the cited pages. | Browser/web use is supported; installable PWA behavior is unconfirmed. | User-facing xRapid/refresh modes are documented. No public E Ink developer SDK was found. | **Strong later Android target.** Share the APK core; isolate refresh behavior behind a vendor adapter. |
| **Supernote Manta / Nomad** | Chauvet, based on Android 11. Earlier A5/A6 devices were Linux-based. | **Limited but supported.** Firmware provides an explicit Sideloading switch and a curated app store. Google Play is not documented. | System WebView and browser behavior are present. PWA installation is unconfirmed. | A public React Native/Android plugin SDK exists, but it runs inside NOTE/DOC plugin hosts rather than as a general app channel. No public third-party refresh API was found. | **Plausible Android port.** Prefer a sideloaded APK for the standalone dashboard; separately evaluate the plugin host. Validate networking, background polling, and redraw behavior on-device. |
| **Hanvon N10 Pro / N10 second generation** | Android; N10 Pro is documented as Android 14 and the first generation as Android 11. | **Yes** on cited models: curated app market plus installation of third-party apps. Google Play and bare-APK details are unconfirmed. | A browser can be installed as an Android app; PWA behavior is unconfirmed. | Fast-refresh engines are advertised, but no public E Ink device SDK was found. Hanvon's developer portal is for OCR/AI services, not display control. | **Plausible Android port.** Model-level validation required. |
| **iReader Smart family** | SmartOS is documented; the current public material reviewed does not establish its Android base/version. | **Limited.** iReader documents a third-party app market and app installation, but not Google Play or a general bare-APK contract. | Web/document browsing is advertised. PWA behavior is unconfirmed. | User-facing high-refresh/i-Display features are advertised; no public refresh SDK was found. | **Research target, not a committed port.** Obtain a model and verify ordinary Android APK compatibility first. |
| **Meebook M7 and related models** | Regional distributor material lists Android 11 for M7; versions vary across models. | **Likely/limited.** Regional material documents Google Play/RuStore, but global manufacturer documentation is sparse. | Browser is listed. PWA behavior is unconfirmed. | No public E Ink SDK was found. | **Potential Android port, low evidence confidence.** Confirm with a real retail model before planning work. |
| **PocketBook Linux readers** | Linux 3.10.65 or 4.9.56 on current cited reader models. | APK does not apply. Native InkView applications are possible. | **Yes.** Current products list a browser; manuals document JavaScript, cookies, and downloads. Modern PWA compatibility is unconfirmed. | **Yes, but niche.** PocketBook publishes an SDK; InkView exposes full, partial, dynamic, A2, and fine updates. Public consumer app distribution is poorly documented. | **Viable native Linux port**, but a separate UI/toolchain and installation path. Not an Android APK target. |
| **PocketBook InkPad Eo / Color Note** | Android 11 / Android 12 respectively. PocketBook is therefore a split platform, not a single Linux target. | Third-party apps and Google Services are documented. | Browser is included. PWA behavior is unconfirmed. | The cited product pages do not document a public Android E Ink refresh SDK. | **Potential Android port.** Track separately from PocketBook Linux devices. |
| **reMarkable developer-mode-capable devices** | reMarkable OS: a custom Yocto/Linux distribution; Xochitl is proprietary. | APK does not apply. Native Qt Quick applications can be cross-compiled and copied over SSH in Developer Mode. | No supported general device browser was found. | **Supported native development path.** Official SDK/toolchains and a Qt E Paper backend exist. Fine-grained waveform APIs were not found. Developer Mode weakens security and is not available on reMarkable 2. | **Technically viable native port**, but developer-oriented deployment makes it unsuitable for the first consumer client. |
| **Kobo eReaders** | Linux with U-Boot and embedded Qt components, confirmed by Kobo's open-source repository. | APK does not apply. No current public consumer app SDK/store was found. | **Limited.** Kobo documents a Beta Web Browser across current models. PWA compatibility is unconfirmed. | Kernel/build sources are public, but no supported public app/refresh API was found. | **Web experiment only** unless Kobo introduces a supported app surface. Native work would currently depend on unsupported device modification. |
| **Kindle eReaders** | Kindle OS uses a Linux/C++ stack; Amazon also publishes per-model open-source archives. | No current Kindle eReader APK, public app SDK, or consumer app-store route was found. Amazon's app platform applies to Fire/Vega products, not Kindle eReaders. | **Limited.** Current guides document a browser with JavaScript, SSL, and cookies. Modern PWA/local-LAN behavior is unconfirmed. | No supported refresh API was found. | **Web experiment only.** Do not make it an MVP dependency. |
| **NOOK GlowLight 4 family** | Current first-party E Ink documentation does not state the underlying OS/version. Do not copy Android claims from NOOK LCD tablets to GlowLight. | No supported third-party app route is documented. “Sideloading” in the GlowLight guide means EPUB/PDF content, not APKs. | No device browser is documented for current GlowLight. | No public SDK or refresh API was found. | **Closed target.** Not viable without unsupported modification or a future vendor surface. |

## Platform groups

### Group A: Android APK candidates

1. **BOOX** — first release and reference device.
2. **Bigme** — strongest documented follow-up.
3. **Supernote and Hanvon** — feasible, but vendor policy and display behavior need real-device proof.
4. **PocketBook Android models** — feasible in principle; separate from PocketBook Linux.
5. **iReader and Meebook** — do not schedule until an exact model proves normal APK install,
   networking, and stable background/foreground behavior.

An Android package is not automatically portable at E Ink quality. Network, JSON, state, and
layout code can be shared; refresh modes, full/partial update policy, screensaver integration,
keep-alive behavior, and vendor settings must remain adapters.

### Group B: supported native Linux ports

- **reMarkable** has the clearest modern toolchain, but deployment requires Developer Mode and is
  model-limited.
- **PocketBook Linux** exposes useful InkView refresh functions, but its SDK/distribution story is
  older and fragmented.

These are separate applications, not rebuilds of the Android APK.

### Group C: browser-only experiments

- **Kindle** and **Kobo** have documented browsers but no supported general application channel.
- A minimal server-rendered or dependency-light web dashboard may work, but must be proven on each
  browser. Do not assume service workers, installable PWA, WebSockets, background timers, modern
  TLS, or unrestricted private-LAN requests.
- **NOOK GlowLight** has no documented browser surface and is excluded.

## Consequences for the CodexBar Ink architecture

- Keep the reader protocol small JSON over HTTP(S); do not expose Swift/macOS types.
- Preserve the existing provider-generic card model from `/dashboard/v1/snapshot`.
- Let the Usage Host own provider credentials. Reader clients store only host configuration and a
  host-access token.
- For trusted-LAN MVP testing, use the existing authenticated snapshot endpoint. Do not describe
  bearer-token plain HTTP as secure; remote use requires Tailscale or HTTPS.
- Render a static, high-contrast portrait hierarchy. Poll at five-minute intervals and redraw only
  after a semantic snapshot change.
- Separate `DisplayAdapter` from domain/render state. BOOX can use Onyx EPD APIs; generic Android
  remains functional without them; later vendors can supply their own adapters.
- Treat “last good snapshot + freshness/stale state” as shared client behavior across all ports.

## Proof still required

- Exact BOOX model, Android version, screen size/resolution, and firmware.
- APK install and launch on that BOOX device.
- Connectivity from the device to a Mac Usage Host on the same LAN.
- Snapshot authentication, timeout, stale fallback, and five-minute wake/poll behavior.
- BOOX GC/GU/REGAL/FAST mode comparison for this mostly static UI.
- Full refresh cadence that removes ghosting without visible churn.
- Whether a screensaver or launcher mode is valuable after the full-screen app MVP.

## Primary sources

### BOOX

- [Install apps through Google Play, browser-downloaded APK, or computer sideload](https://help.boox.com/hc/en-us/articles/10701308914964-Download-and-Install-Apps)
- [NeoBrowser](https://help.boox.com/hc/en-us/articles/10701363849108-NeoBrowser)
- [Per-app optimization](https://help.boox.com/hc/en-us/articles/8569442137108-App-Optimization)
- [Refresh modes](https://help.boox.com/hc/en-us/articles/10701257029780-Refresh-Modes)
- [Onyx Android SDK demo and EPD APIs](https://github.com/onyx-intl/OnyxAndroidDemo)
- [Note Air5 C product specification](https://shop.boox.com/collections/all/products/noteair5c)

### Android-derived alternatives

- [Bigme B1051](https://store.bigme.vip/products/b1051-series-10-3-color-e-ink-tablet-pc-with-cutting-edge-performance?variant=44294274384051)
- [Bigme B6](https://store.bigme.vip/collections/6-inch/products/bigme-b6-ai-color-ereader-with-android-14os)
- [Supernote Nomad platform specification](https://supernote.com/products/supernote-nomad)
- [Supernote Manta/Nomad firmware changelog](https://support.supernote.com/en_US/changelog-for-manta-and-nomad)
- [Supernote official plugin SDK introduction](https://github.com/Supernote-Ratta/docs-plugin/blob/main/en/index.mdx)
- [Supernote plugin packaging and installation guide](https://github.com/Supernote-Ratta/docs-plugin/blob/main/en/first-plugin.mdx)
- [Hanvon N10 second-generation announcement](https://www.hanwang.com.cn/index.php?a=show&c=index&catid=66&id=389&m=content)
- [Hanvon Android/open-platform statement](https://www.hanwang.com.cn/index.php?a=show&c=index&catid=66&id=397&m=content)
- [iReader Smart 5 Pro official product video](https://www.bilibili.com/video/BV1nn4y1f73v/)
- [Meebook M7 regional distributor specification](https://meebook.ru/product/meebook-m7-elektronnaya-kniga/)

### Linux and closed platforms

- [Kobo Reader open-source repository](https://github.com/kobolabs/Kobo-Reader)
- [Kobo Beta Web Browser support matrix](https://help.kobo.com/hc/en-us/articles/360017763733-About-Beta-Features)
- [reMarkable software stack](https://developer.remarkable.com/documentation/software-stack)
- [reMarkable SDK](https://developer.remarkable.com/documentation/sdk)
- [reMarkable Developer Mode](https://developer.remarkable.com/documentation/developer-mode)
- [reMarkable Qt E Paper backend](https://developer.remarkable.com/documentation/qt_epaper)
- [reMarkable 2 specification: custom Linux OS and no Developer Mode](https://support.remarkable.com/articles/Knowledge/About-reMarkable-2)
- [PocketBook product OS/browser matrix](https://products.pocketbook.ch/)
- [PocketBook software license and open SDK pointer](https://download.pocketbook-int.com/licenses/license-en.pdf)
- [PocketBook SDK 6.3.0](https://github.com/pocketbook/SDK_6.3.0)
- [PocketBook InkPad Eo Android specification](https://pocketbook.ch/en-ch/catalog/e-notes/pocketbook-inkpad-eo-ch)
- [Amazon E-reader team Linux/C++ stack description](https://www.amazon.jobs/en/jobs/10414785/software-development-engineer-e-reader)
- [Amazon Kindle open-source archives](https://digprjsurvey.amazon.com/csad/help/node/200203720)
- [Kindle Paperwhite 12th-generation user guide](https://d1ergij2b6wmg5.cloudfront.net/kug/kindle_paperwhite_12th/v1/en-US/html/kug.html)
- [NOOK GlowLight 4 user guide](https://dispatch.barnesandnoble.com/content/dam/rncx/2015/pdf/NOOK_Glowlight_4_User_Guide-v2.pdf)

## Confidence notes

- BOOX, Kindle, Kobo, reMarkable, Supernote, PocketBook, Bigme, Hanvon, and NOOK findings are based
  on current first-party documentation or vendor-owned source repositories.
- iReader evidence establishes SmartOS/app-market behavior but not its Android base.
- Meebook evidence is weaker because the manufacturer site provides little technical material; the
  cited M7 data comes from a regional official distributor.
- Absence claims mean “not found in current public first-party documentation,” not “impossible by
  rooting, jailbreaking, or reverse engineering.” Unsupported modification is outside the MVP.
