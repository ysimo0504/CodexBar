---
status: accepted
---

# Use self-hosted LAN HTTPS for the CodexBar Ink MVP

## Context

CodexBar Ink needs to read the redacted Dashboard Snapshot from a user's Mac without exposing provider credentials,
reader tokens, or snapshot data to the local network. The MVP is personal, read-only, and BOOX-first. It must work
without a hosted relay, third-party identity service, public DNS, router port forwarding, or a subscription.

The earlier Tailscale-first design provided a practical encrypted overlay, but made a third-party coordination service
part of the default path. Tailscale remains a useful optional remote-access adapter; it is no longer an MVP
requirement.

Non-functional requirements:

- one user, one Mac, and a small number of readers; fewer than one snapshot request per minute;
- no public listener and no availability promise outside the current LAN;
- TLS confidentiality and integrity, explicit Host and route allowlists, and an application bearer token;
- provider credentials stay on the Mac and never enter pairing material;
- failure retains only the sanitized last-good snapshot and never downgrades to cleartext;
- installation and ordinary reading require no cloud account or hosted service.

## Decision

The signed CodexBar app will own a LAN-only HTTPS Usage Host:

```text
BOOX / CodexBar Ink
  |  HTTPS to the current private-LAN address
  |  exact paired certificate SHA-256 pin
  |  Authorization: Bearer <reader token>
  v
CodexBar LAN TLS listener
  |  exact Host and GET /dashboard/v1/snapshot only
  v
identity-redacted Dashboard Snapshot v1
```

- CodexBar creates a long-lived self-signed TLS identity on explicit Usage Host enablement. Private-key material stays
  in the signed app's private storage with owner-only permissions; the reader token remains in its dedicated Keychain
  item.
- Pairing material contains a version, current private-LAN HTTPS URL, stable Host ID, certificate SHA-256 pin, and
  reader token. It is shown locally for manual entry first; a QR presentation may be added without changing the wire
  contract.
- The Android client stores the token encrypted by Android Keystore and stores the non-secret URL, Host ID, and
  certificate pin in private preferences excluded from backup and device transfer.
- The client uses an instance-scoped TLS trust manager that accepts only the exact paired leaf certificate and checks
  certificate validity. The pin is the server identity, so a private IP address may change without depending on a
  public CA or public DNS name. No global trust override or HTTP fallback is allowed.
- The listener binds only while the user enables the feature and only on private/link-local interfaces. It exposes
  authenticated `GET /dashboard/v1/snapshot`; `/usage`, `/cost`, unknown paths, non-GET methods, malformed requests,
  and unpaired Hosts fail closed.
- Address changes are explicit reader state. The certificate and Host ID remain stable, but the reader must receive the
  new private URL from the Mac; it never scans public networks or silently substitutes an untrusted address.
- Remote access is deferred. A future adapter may use a user-managed WireGuard network or Tailscale, but it must reuse
  the same gateway, token, snapshot, and no-downgrade invariants.

## Consequences

### Positive

- No third-party hosted service, account, public DNS name, router port, or cloud relay is required.
- TLS and an out-of-band certificate pin protect against passive LAN observers, DNS spoofing, and an unpaired host.
- The existing provider-neutral snapshot, strict gateway, token rotation, last-good cache, and display behavior remain
  reusable.
- Operational cost is zero after pairing.

### Negative

- Reading works only while Mac and BOOX share a reachable private network.
- The Mac app must own TLS identity creation, private-key permissions, interface changes, and local-network diagnostics.
- Pairing must be repeated after certificate loss or intentional identity rotation.
- Certificate pinning replaces public-PKI hostname identity; its implementation and rotation tests become
  security-critical.

### Neutral

- Tailscale code may remain as an optional adapter, but settings and acceptance language must not make it mandatory.
- A user-managed WireGuard or reverse-proxy deployment is an advanced future mode, not part of MVP acceptance.

## Alternatives considered

### Tailscale Serve HTTPS

Good remote reachability and standard HTTPS, but rejected as the default because it requires a third-party coordination
service and account. It remains optional.

### Plain LAN HTTP

Rejected for real data because bearer tokens and snapshots are observable and modifiable on the LAN. It remains valid
only for redacted fixture tests.

### Public HTTPS reverse proxy

Rejected because it adds public attack surface, DNS/certificate operations, and router or server administration for a
personal reader.

### Application-layer encryption over HTTP

Rejected because designing a custom request encryption and replay protocol is riskier than using TLS.

### Bundled third-party proxy

Rejected for the default path because it adds packaging and lifecycle dependencies when the signed app can own the
small listener.

## Failure modes and recovery

- TLS or pin mismatch: hard failure, keep last-good, require explicit re-pairing; never try HTTP.
- Wrong or rotated token: 401, keep last-good, pause authenticated refresh until re-paired.
- Mac sleep, app exit, Wi-Fi change, or unreachable address: bounded timeout and last-good; foreground retry only.
- Public/non-private address: refuse pairing and refuse listener publication.
- Lost TLS identity: generate a new identity only after explicit enable/repair; readers must re-pair.
- Malformed or oversized snapshot: reject the new response and retain last-good.

## References

- [Platform-neutral Reader boundary](0001-platform-neutral-reader-boundary.md)
- [Secure Usage Host transport research](../research/secure-usage-host-transport.md)
- [BOOX snapshot rendering loop](../research/boox-snapshot-rendering-loop.md)
- GitHub issues #5 and #16
