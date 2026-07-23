# CodexBar Ink Android prototype

This isolated Android project proves the fixture-backed BOOX rendering loop without using live Provider data or
macOS Keychain access.

Real-device results and remaining secure-transport gates are recorded in
[`docs/research/boox-snapshot-rendering-loop.md`](../../docs/research/boox-snapshot-rendering-loop.md).

## Variants

- `fixtureGenericDebug`: bundled redacted snapshot and standard Android invalidation; no Onyx AAR.
- `fixtureBooxDebug`: the same reader plus failure-open Onyx `REGAL`/`GU` partial refresh and `GC` cleanup.
- `offline*`: no cleartext transport opt-in; reserved for the later secure transport path.

The application ID for the installed prototype is `com.ysimo.codexbar.ink.fixture.debug`.

## Build and test

```bash
cd Android/CodexBarInk
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew :reader-core:test :app:assembleFixtureGenericDebug :app:assembleFixtureBooxDebug
```

The canonical fixture is consumed directly from `docs/fixtures`; it is not copied into a second source of truth.

## Authenticated fixture host

The fixture server exposes only `GET /dashboard/v1/snapshot`. It returns 401 for a wrong bearer token and 404 for
every other path. It contains synthetic redacted data only and must never serve real account snapshots.

```bash
python3 tools/fixture_server.py --host 0.0.0.0 --port 8787

JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew :app:assembleFixtureBooxDebug \
  -PcodexbarInkFixtureUrl=http://MAC_LAN_IP:8787/dashboard/v1/snapshot \
  -PcodexbarInkFixtureToken=codexbar-ink-fixture-token
```

Do not commit a LAN IP or token. The committed default token is intentionally fixture-only.

## Leaf3C install

```bash
android run --apks=app/build/outputs/apk/fixtureBoox/debug/app-fixture-boox-debug.apk \
  --activity=com.ysimo.codexbar.ink.MainActivity
```

BOOX firmware 4.2 may automatically freeze a newly installed third-party app. If the package reports
`enabled=3`, explicitly unfreeze CodexBar Ink in BOOX App Management or run:

```bash
adb shell pm enable --user 0 com.ysimo.codexbar.ink.fixture.debug
```

Do not pass `--debug` merely to install a debuggable APK: that flag waits for a debugger and can look like a launch
failure. Wake the device before judging visibility; a BOOX sleep screen is a `dream` Window above the Activity.
