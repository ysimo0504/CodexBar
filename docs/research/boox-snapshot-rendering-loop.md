# BOOX Snapshot Rendering Loop Evidence

Date: 2026-07-23

## Result

The throwaway Android client completed the fixture-backed rendering loop on a mainland BOOX Leaf3C. It parsed the
canonical redacted Dashboard Snapshot, authenticated to the fixture-only snapshot route, retained sanitized
last-good data across network and authentication failures, rendered a no-scroll portrait dashboard, and invoked
BOOX partial and full-refresh APIs through a failure-open display adapter.

The final APK installed on the device uses the bundled redacted fixture so it remains useful after the USB fixture
bridge is removed. No live Provider probe, browser-cookie import, Keychain read, account snapshot, device serial, or
real bearer token was used or recorded.

## Device and build

- Device: mainland BOOX Leaf3C, BOOX firmware 4.2.
- Runtime: Android 11 / API 30.
- Display: 1264 x 1680 at 300 dpi in portrait.
- Prototype package: `com.ysimo.codexbar.ink.fixture.debug`.
- Activity: `com.ysimo.codexbar.ink.MainActivity`.
- APK: `app-fixture-boox-debug.apk`, built from the isolated `Android/CodexBarInk` project.
- Reader Core: pure Kotlin/JVM reducer and presenter over `docs/fixtures/dashboard-snapshot-v1-canonical.json`.
- UI: one Activity using classic Views/XML with stable header, provider, and provider-list regions.
- Display integration: BOOX-only flavor using `onyxsdk-device:1.3.5`; generic flavor has no Onyx dependency.

## Snapshot and authentication checks

The fixture server accepted only `GET /dashboard/v1/snapshot` with the synthetic fixture bearer token. It returned:

| Request | Observed result |
| --- | --- |
| Snapshot path, correct fixture token | `200`, canonical redacted snapshot rendered |
| Snapshot path, wrong fixture token | `401`, safe error plus retained last-good data |
| `/usage` | `404`, no data |
| `/cost` | `404`, no data |
| Unknown path | `404`, no data |
| `POST` | `405`, no data |

The device fetch used `adb reverse` to a loopback fixture server because the Leaf3C Wi-Fi was disabled and did not
reassociate when enabled. This proved request authentication and Reader behavior without weakening release
transport policy. It did not prove Tailscale, MagicDNS, tailnet ACLs, TLS hostname failure, Wi-Fi recovery, reboot
recovery, token rotation, or battery impact; those remain secure-transport acceptance work before real-account use.

## Reader behavior

- Cold launch displayed global freshness, Codex, Claude, and the unknown future provider in Host order.
- A wrong token changed only the safe header state to authentication failure; all last-good provider values remained.
- Removing the fixture bridge changed only the safe header state to temporary network failure; all last-good values
  remained.
- Cached storage contains the sanitized `ReaderState`, not raw responses or Provider credentials.
- Unknown schema versions reject the whole snapshot.
- Provider-level errors preserve only that provider's prior usable values and display a safe error beside them.

## E-ink and layout observations

- `supportRegal` returned false on this Leaf3C, so the adapter selected BOOX `GU` partial refresh instead of assuming
  REGAL support.
- Semantic header-only changes logged one BOOX partial-refresh region.
- Manual **Clean ghosting** logged a BOOX `GC` full refresh.
- Cold/root changes use a full refresh; steady refreshes with no semantic change do not submit a display update.
- BOOX reflection failures disable vendor refresh and retain normal Android invalidation.
- Both portrait rotations rendered without clipping and respected system-bar insets.
- Font scale 1.0 and 1.3 rendered the complete no-scroll dashboard, including both 48 dp actions.
- The UI uses grayscale-only opaque fills, no gradients, no animation, body text of at least 14 sp, and touch targets
  of at least 48 dp.
- Visual inspection found no content clipping. Physical ghosting was acceptable for this short fixture session, but
  a calibrated long-duration semantic-update threshold was not derived and must not be invented from this run.

## Leaf3C operational constraints

- BOOX automatically changed the newly installed third-party package to disabled state `3`. Explicitly enabling the
  package restored normal launch; users can also unfreeze it in BOOX App Management.
- A sleeping device showed the BOOX `dream` window above the Activity. Wake and dismiss keyguard before treating a
  blank/sleep screen as an application failure.
- `android run --debug` waits for a debugger and can resemble a failed launch. Plain install/start is the correct
  sideload verification path.
- BOOX prepended `capture from screenshot!` to raw `adb exec-out screencap` output; screenshot automation must strip
  that device-specific prefix before decoding the PNG.
- The tested Onyx AAR attempted to merge Wi-Fi, Bluetooth, and DUMP permissions. Manifest merging explicitly removes
  them; the APK retains only `INTERNET` plus AndroidX's scoped dynamic-receiver permission.

## Final device state

The no-network bundled-fixture APK was reinstalled, explicitly enabled, cold-launched, inspected through the Android
layout tree and a screenshot, and left visible in the foreground. USB port reversal was removed, the fixture server
was stopped, and Wi-Fi, font scale, and rotation settings were restored.
