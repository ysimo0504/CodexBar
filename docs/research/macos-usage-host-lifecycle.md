# macOS Usage Host lifecycle for CodexBar Ink

Last verified: 2026-07-23

## Decision

Use an **app-managed Usage Host inside the CodexBar menu-bar application** for the personal MVP.
The main app owns the loopback listener, snapshot-only reader gateway, reader token, Tailscale Serve
configuration, lifecycle monitoring, and diagnostics. It starts the Host only when the user enables
CodexBar Ink, and the existing `SMAppService.mainApp` login setting supplies login startup.

Do not make a foreground `codexbar serve` terminal the normal lifecycle. Do not add a LaunchAgent for
the MVP. A bundled `SMAppService` login-item helper is the deferred reliability step if crash recovery
while the main app is absent becomes necessary.

This is a lifecycle choice, not authorization to implement or activate the Host in this ticket.

## Why this choice fits the current fork

- CodexBar already registers its main application at login through `SMAppService.mainApp`
  ([`LaunchAtLoginManager.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBar/LaunchAtLoginManager.swift)).
- The app bundle already ships `CodexBarCLI` in `Contents/Helpers`, so foreground CLI remains a useful
  diagnostic path without becoming a second production lifecycle
  ([`package_app.sh`](https://github.com/ysimo0504/CodexBar/blob/main/Scripts/package_app.sh)).
- The current server reads the reader bearer from `CODEXBAR_DASHBOARD_TOKEN`; the inherited process
  environment can then reach provider helpers. An in-process Host can receive the token as an in-memory
  value instead of argv or environment
  ([`CLIServeCommand.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift),
  [`TTYCommandRunner.swift`](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Host/PTY/TTYCommandRunner.swift)).
- The menu app is the natural place for enable/disable, pairing, token rotation, Host health, Tailscale
  health, and actionable diagnostics. A headless job would require a second configuration and IPC surface.

Apple documents that `SMAppService.mainApp` launches the application on subsequent logins. Apple also
distinguishes a bundled LoginItem helper, which is relaunched after crash or non-zero exit; the current
main-app registration does not promise that crash-restart behavior
([`SMAppService.register()`](https://developer.apple.com/documentation/servicemanagement/smappservice/register%28%29),
[`SMAppService`](https://developer.apple.com/documentation/servicemanagement/smappservice)). Therefore
main-app crash recovery is explicitly deferred, not assumed.

## Options compared

| Lifecycle | Login/startup | Failure recovery | Sleep/network | Secret boundary | Update/diagnostics | MVP |
| --- | --- | --- | --- | --- | --- | --- |
| Foreground `codexbar serve` | Manual terminal | None after shell/process exit | Manual restart/check | Environment or argv today | CLI logs only; binary path may drift | No |
| App-managed Host | Existing opt-in main-app login startup | Restart listener tasks in-process; whole-app crash deferred | Observe wake and network path, then revalidate | Keychain at rest; in-memory injection | Same signed app, settings, logs, version | **Yes** |
| LaunchAgent | Login bootstrap | `launchd` can keep/restart job | Job still needs its own wake/network logic | plist/env are unsafe; Keychain and IPC grow scope | Separate helper, plist, logs, migration | Deferred |

Apple's `launchd.plist(5)` says `KeepAlive` can continuously run and throttle a repeatedly failing job,
but `NetworkState` is no longer implemented. A LaunchAgent would not solve network readiness, Tailscale
state, token custody, or diagnostics by itself
([`launchd.plist(5)`](https://keith.github.io/xcode-man-pages/launchd.plist.5.html)).

## Target lifecycle

### Enable and login

1. User enables CodexBar Ink in the signed CodexBar app.
2. The app generates a cryptographically random reader bearer and stores it in a dedicated Keychain item.
   No real Keychain item is read or changed during planning/tests.
3. The app starts one in-process loopback gateway on an ephemeral or configured localhost port.
4. The app verifies and applies the exact Tailscale Serve mapping only after the loopback gateway is healthy.
5. If launch-at-login is enabled, `SMAppService.mainApp` starts CodexBar at later logins; the same coordinator
   restores the Host from settings and Keychain.

The reader stores one HTTPS origin using the Mac's stable MagicDNS `*.ts.net` name. It reconnects with
bounded exponential backoff plus jitter, while continuing to render last-good data.

### Runtime, sleep, and network changes

The coordinator owns a small state machine:

`disabled`, `starting`, `localReady`, `tailnetReady`, `degraded(reason)`, `stopping`.

- Listener failure: restart locally with bounded backoff; never start two listeners.
- Mac sleep: stop scheduling probes and mark transport suspended without discarding the last snapshot.
- Wake: revalidate loopback health, Tailscale backend state, MagicDNS hostname, Serve mapping, and HTTPS.
- Network change: use `NWPathMonitor` as a change signal, then perform explicit health checks; path
  availability alone does not prove tailnet or HTTPS health.
- App termination: stop the listener cleanly. Persistent Serve may remain configured but must fail closed
  while the loopback backend is absent.

Apple provides `NSWorkspace.didWakeNotification` / `willSleepNotification` and `NWPathMonitor` for these
signals
([`didWakeNotification`](https://developer.apple.com/documentation/appkit/nsworkspace/didwakenotification),
[`NWPathMonitor`](https://developer.apple.com/documentation/network/nwpathmonitor)).

## Transport ownership

The lifecycle owns Tailscale Serve **and** a snapshot-only local gateway. It must not proxy the complete
current CLI origin.

```text
Leaf3C
  HTTPS + Bearer
    Tailscale Serve, tailnet only
      exact external path and Host policy
        127.0.0.1 snapshot-only gateway
          Dashboard Snapshot producer
```

Required invariants:

- external `GET /dashboard/v1/snapshot` is the only data route;
- `/usage`, `/cost`, unknown paths, non-GET methods, duplicate Host, and duplicate Authorization fail closed;
- preserve exact Host/DNS-rebinding protection; never use wildcard Host or trust arbitrary forwarded headers;
- either accept only the resolved exact MagicDNS FQDN or rewrite Host inside a loopback-only trusted proxy;
- no router port forward and no Tailscale Funnel;
- no automatic LAN HTTP fallback after DNS, TLS, Tailscale, 401, or 403 failure.

Tailscale documents that `tailscale serve --bg` persists its configuration across reboot and Tailscale
restart. The coordinator must therefore reconcile desired and actual Serve state instead of assuming that
app process lifetime owns it
([`tailscale serve`](https://tailscale.com/docs/reference/tailscale-cli/serve)).

Current machine evidence: `/usr/local/bin/tailscale` is a stale wrapper pointing at a missing
`/Applications/Tailscale.app`. This is a local readiness failure to show in diagnostics; the app must not
silently install or replace Tailscale.

## Reader-token boundary

- Generate at least 256 random bits; display/transfer only during explicit pairing.
- Store on Mac in a dedicated signed-app Keychain item; store on Android in Android Keystore-backed storage.
- Pass to the in-process authenticator as a value, never through argv, plist, shell script, global defaults,
  URL, query string, clipboard history, or log fields.
- Remove `CODEXBAR_DASHBOARD_TOKEN` and future reader-secret keys from every provider/helper child
  environment, with focused tests at the common process-launch seam.
- Rotation creates a new token, updates pairing, and invalidates the previous token after explicit
  confirmation or a short bounded overlap. Logs record only a non-secret token generation identifier.
- A reader 401 means credentials need repair; it does not trigger provider collection and does not erase
  last-good data.

## Diagnostics contract

The CodexBar settings surface and unified log expose only non-secret state:

- Host enabled/disabled and local listener health;
- Tailscale executable missing/broken, backend disconnected, authentication/key expiry, or permission error;
- current MagicDNS name fingerprint/change, without dumping unrelated tailnet peers or addresses;
- Serve mapping present/mismatched and last reconciliation time;
- last local health result, external HTTPS result, 401, 403, timeout, sleep/wake, and retry time;
- app/CLI/schema version compatibility.

Never log Authorization, raw token, provider credentials, cookies, full snapshots, account identity, or
unredacted provider errors.

## Compatibility and deferred hardening

MVP compatibility rules:

- app and Host ship together; no cross-version background binary selection;
- schema compatibility remains governed by Dashboard Snapshot v1;
- app update stops/restarts the in-process Host and reconciles persistent Serve state on next launch;
- foreground `codexbar serve` remains opt-in diagnostics and fixture development only.

Deferred after personal MVP evidence:

- bundled `SMAppService` LoginItem helper for whole-app crash recovery;
- signed XPC contract between app and helper, if introduced;
- automatic token overlap/revocation policy;
- multi-reader administration;
- alternate HTTPS gateway when Tailscale is unavailable.

## Implementation item

Create one implementation issue covering the app-managed coordinator, snapshot-only gateway, exact Host
policy, Keychain-backed in-memory token injection, child-environment scrubbing, Serve reconciliation,
sleep/network recovery, safe diagnostics, and focused tests. It blocks real-account acceptance, but not the
fixture-only Leaf3C rendering spike.

Related transport analysis: [`secure-usage-host-transport.md`](secure-usage-host-transport.md).
