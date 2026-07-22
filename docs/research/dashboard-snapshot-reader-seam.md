# Dashboard Snapshot seam for reader clients

Last verified: 2026-07-22

## Question

Can the existing `GET /dashboard/v1/snapshot` endpoint be the platform-neutral boundary between a
Mac CodexBar Usage Host and a BOOX-first CodexBar Ink reader? If so, what must the reader rely on,
and what is the minimum hardening needed before treating schema v1 as a durable client contract?

This audit is based only on the repository's implementation, tests, and documentation. It does not
exercise real providers, browser cookies, accounts, or Keychain access.

## Decision

**Go for a fixture-backed BOOX rendering spike; conditional go for a real-account MVP.** Reuse the
existing endpoint and schema because they already have the right ownership boundary and enough
display data:

- the Mac selects and queries providers;
- the reader receives provider-generic display cards, not raw provider models;
- the endpoint always applies bearer authentication and redacts account identity;
- timestamps, errors, cache cadence, and a staleness threshold are present;
- provider ordering is represented independently from color or platform UI.

No new host endpoint, ETag, streaming protocol, or provider-specific Android model is required for
the first rendering loop. The required producer-contract hardening track is **contract lock-down**:
publish explicit v1 compatibility rules and a checked-in canonical JSON fixture, then make the
Android DTO decoder consume that fixture. The current implementation tests the producer
thoroughly, but no external consumer fixture yet proves the cross-language boundary.

The reader must also own **per-provider last-good presentation state**. The host's dashboard cache
is a whole-response cache and treats every root-object HTTP 200 as successful, including snapshots
whose cards contain provider errors. It is therefore not a reliable per-provider last-good store.

Three current behaviors must be treated as deliberate MVP constraints:

1. a reader “refresh now” action performs an authenticated GET but cannot bypass the host's
   `--refresh-interval` cache;
2. the route exposes a `status` slot, but its normal collection path does not currently request
   provider service status, so a reader must work correctly when `status` is always `null`.
3. `error.message` is free-form `localizedDescription`. The schema contains no credential field,
   but the host does not enforce or test that this free text can never contain a token, cookie,
   account path, or other sensitive provider detail.

None blocks a controlled rendering spike. Before a personal trusted-LAN MVP reads real accounts,
the host must enforce a display-safe error invariant; the reader must also never persist, log, or
show raw `error.message`. This is required even on a trusted LAN because the current producer can
put provider response content into the snapshot body.

## Boundary and ownership

The exact route is a GET-only path selected by the serve router. The handler authorizes before it
loads config, reads cache state, or starts provider work. It then selects the enabled providers,
collects usage and supported cost data on the Mac, and projects the result with
`DashboardSnapshotBuilder` using `.redacted` identity mode
([`CLIServeCommand.swift`, router, lines 52-73](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L52-L73),
[`CLIServeCommand.swift`, authenticated handler, lines 890-931](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L890-L931),
[`CLIServeCommand.swift`, snapshot construction, lines 1159-1205](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1159-L1205)).

Consequences:

- Provider credentials and provider-specific fetch logic stay on the Mac.
- The reader needs only a host URL and host-access bearer token.
- Provider filtering on the reader is local filtering of `providers`; the snapshot route has no
  provider query parameter.
- Provider enablement and source selection remain Mac-side config. The config is reloaded per
  request, and cache keys include a normalized config fingerprint
  ([`CLIServeCommand.swift`, config snapshot and fingerprint, lines 935-965](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L935-L965)).

The route intentionally requests only the selected Codex account rather than the CLI's optional
all-account projection (`includeAllCodexAccounts: false`)
([`CLIServeCommand.swift`, dashboard usage context, lines 913-929](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L913-L929)).
Configured token-account providers likewise resolve to their active account when no account
override is supplied
([`TokenAccountCLI.swift`, `resolvedAccounts`, lines 56-85](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/TokenAccountCLI.swift#L56-L85)).
Schema v1 should therefore be treated as one active card per enabled provider; it has no stable
account/card identifier for a future multi-account dashboard.

## Wire contract

The producer model is intentionally `Encodable`, not a Swift domain model shared with clients.
The complete top-level shape is declared in
[`DashboardPayloads.swift`, lines 12-34](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardPayloads.swift#L12-L34),
and every provider field is declared in
[`DashboardPayloads.swift`, lines 36-80](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardPayloads.swift#L36-L80).

### Required top-level shape

| Field | v1 wire type | Reader interpretation |
| --- | --- | --- |
| `schemaVersion` | integer, currently `1` | Hard compatibility gate. |
| `generatedAt` | ISO-8601 string | Time the host assembled this response body, not the HTTP receipt time. |
| `staleAfterSeconds` | integer | Age threshold for global stale UI. |
| `host` | object | Host build/cadence metadata. |
| `host.codexBarVersion` | string or `null` | Running host build, when resolvable. |
| `host.refreshIntervalSeconds` | integer | Host response-cache TTL, not a mandatory client poll interval. |
| `providers` | array | Generic cards. It can be empty. |

The builder fixes `schemaVersion` to 1, rounds a positive refresh interval up to whole seconds, and
computes `staleAfterSeconds = max(180, refreshIntervalSeconds * 3)`
([`DashboardSnapshotBuilder.swift`, lines 37-45](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L37-L45),
[`DashboardSnapshotBuilder.swift`, lines 258-263](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L258-L263)).

### Provider card

Each provider has these required keys; nullable values are still emitted as JSON `null` by the
custom encoders:

| Field | v1 wire type | Reader interpretation |
| --- | --- | --- |
| `id` | string | Opaque provider ID, currently the `UsageProvider` raw value. |
| `name` | string | Host-owned display name; fallback is `id`. |
| `enabled` | boolean | Config enablement. Normal endpoint output selects enabled providers, so current cards are enabled. |
| `source` | string | Usage data source; blank source is normalized to `"unknown"`. |
| `status` | object or `null` | Optional service status. Do not reserve required UI space for it. |
| `identity` | object or `null` | Redacted email/plan information. |
| `windows` | array | Zero or more generic quota windows. |
| `credits` | object or `null` | Remaining balance plus unit. |
| `cost` | object or `null` | Today and/or 30-day USD estimates. |
| `display` | object | Ordering/color hints. |
| `error` | object or `null` | Provider or cost error; healthy data can coexist with an error. |
| `updatedAt` | ISO-8601 string or `null` | Best available row timestamp, with the caveat below. |

The projection is generic rather than a Codex/Claude union. Provider descriptors contribute only
name, labels, and color; all provider usage is reduced to the same windows/credits/cost/status
shape
([`DashboardSnapshotBuilder.swift`, `makeProvider`, lines 49-81](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L49-L81)).
The builder test pins the encoded card, including host metadata, identity, two windows, credits,
cost, and display hints
([`DashboardSnapshotBuilderTests.swift`, lines 74-125](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L74-L125)).

#### Windows

Each window has `kind`, `label`, `usedPercent`, `remainingPercent`, and nullable `resetAt`.
Built-in kinds are `session`, `weekly`, and `tertiary`; provider-specific extra windows use their
own IDs. Percentages are clamped to `0...100`, and remaining percent is derived as `100 - used`
([`DashboardSnapshotBuilder.swift`, lines 159-181](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L159-L181),
[`DashboardSnapshotBuilder.swift`, lines 205-218](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L205-L218)).

The Android client should therefore model `kind` as an open string and render `label`; it must not
fail decoding when a new provider-specific kind appears.

#### Status, errors, and partial cards

Status levels produced today are `ok`, `warning`, `critical`, and `unknown`
([`DashboardSnapshotBuilder.swift`, lines 89-107](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L89-L107)).
However, `serveUsageOutput` currently calls each fetch with `status: nil`, so the standard snapshot
route does not poll provider status pages
([`CLIServeCommand.swift`, lines 1134-1148](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1134-L1148)).

The card error is the usage error when present, otherwise the cost error. Cost failure does not
remove successful windows or identity; the test confirms that it is projected alongside the
usable card
([`DashboardSnapshotBuilder.swift`, lines 61-81](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L61-L81),
[`DashboardSnapshotBuilderTests.swift`, lines 240-270](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L240-L270)).
The reader must render available metrics first and an error annotation second, rather than replace
the whole card whenever `error != null`.

The error object contains integer `code`, string `message`, and an optional `kind` classification
([`CLIErrorReporting.swift`, lines 4-15](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIErrorReporting.swift#L4-L15)).

`ProviderErrorPayload.message` is populated directly from `error.localizedDescription`, and the
dashboard builder forwards that payload unchanged
([`CLIErrorReporting.swift`, lines 11-22](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIErrorReporting.swift#L11-L22),
[`DashboardSnapshotBuilder.swift`, lines 61-76](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L61-L76)).
This has a concrete unsafe source: a Claude OAuth server error incorporates up to 400 characters of
the response body into its localized description, and adjacent logging code explicitly avoids that
description because response bodies can contain identifying information
([`ClaudeOAuthUsageFetcher.swift`, lines 21-39](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Providers/Claude/ClaudeOAuth/ClaudeOAuthUsageFetcher.swift#L21-L39),
[`ClaudeUsageFetcher.swift`, lines 962-976](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Providers/Claude/ClaudeUsageFetcher.swift#L962-L976)).
The payload has no explicit token/cookie/credential member, but there is no dashboard scrubber or
test invariant proving that arbitrary provider error descriptions are display-safe. Existing
coverage positively confirms that arbitrary message text such as `temporary failure` is preserved
([`DashboardSnapshotBuilderTests.swift`, lines 208-236](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L208-L236)).
For a rendering spike, use synthetic fixtures and render a generic error label. Before real-account
MVP use, sanitize the dashboard payload on the host and keep detailed error text Mac-side.

Raw `usage` and `openaiDashboard` provider structures are absent from this endpoint; the focused
test asserts that an error card contains the display projection but not those raw internals
([`DashboardSnapshotBuilderTests.swift`, lines 208-238](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L208-L238)).

#### Identity isolation and redaction

The serve handler always requests `.redacted`; the reader cannot request full identity
([`CLIServeCommand.swift`, lines 1192-1199](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1192-L1199)).
The builder obtains identity only through `usage.identity(for: provider)`, which rejects an
identity whose provider ID does not match the card provider
([`DashboardSnapshotBuilder.swift`, lines 110-125](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L110-L125),
[`UsageFetcher.swift`, lines 444-447](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/UsageFetcher.swift#L444-L447)).

Redaction replaces the email local part with `redacted`, keeps the final domain, and emits only
`redacted` for a domainless value
([`DashboardSnapshotBuilder.swift`, lines 128-137](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L128-L137),
[`DashboardSnapshotBuilderTests.swift`, lines 168-206](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L168-L206)).
This is pseudonymization, not anonymity: the domain, plan, source, percentages, cost, and provider
error text remain visible to the reader and on the transport.

### JSON encoding details

The shared CLI encoder uses Foundation's ISO-8601 date strategy and camel-case property names.
Current tests pin timestamps such as `2027-01-15T08:00:20Z`
([`CLIErrorReporting.swift`, `encodeJSON`, lines 72-78](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIErrorReporting.swift#L72-L78),
[`DashboardSnapshotBuilderTests.swift`, lines 92-114](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L92-L114)).

Reader rules:

- parse timestamps as ISO-8601/RFC 3339 instants and tolerate optional fractional seconds;
- decode percentages, credits, and costs as floating-point numbers even when JSON prints an
  integral value;
- give nullable members a default of `null`, tolerating either explicit `null` or absence;
- ignore unknown object fields;
- map unknown provider IDs, window kinds, sources, status levels, priorities, and units to generic
  display fallbacks rather than failing the whole snapshot.

The first three provider windows and nullable member behavior are explicit in the payload encoders
([`DashboardPayloads.swift`, lines 82-161](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardPayloads.swift#L82-L161)).

## Provider IDs and ordering

`providers[].id` is the raw string of the host's `UsageProvider` enum
([`Providers.swift`, lines 5-69](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Providers/Providers.swift#L5-L69),
[`DashboardSnapshotBuilder.swift`, lines 57-65](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L57-L65)).
The Android client should keep it as an opaque string. Codex and Claude may receive preferred
presentation treatment by comparing their documented IDs, but unknown IDs must still render as
ordinary cards.

Display order is represented by `display.sortKey`: the builder maps Mac config order to values
`0, 10, 20, ...`; a provider not found in config receives `10000 + payloadIndex`
([`DashboardSnapshotBuilder.swift`, lines 17-35](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L17-L35)).
Config order itself is the stored provider array order
([`CodexBarConfig.swift`, lines 114-125](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCore/Config/CodexBarConfig.swift#L114-L125)).

The collection machinery restores caller order after concurrent provider fetches
([`CLIServeCommand.swift`, lines 1246-1280](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1246-L1280)),
but clients should still sort by `display.sortKey` and use `id` as a deterministic tie-breaker.
Array position is transport order, not the durable UI contract. On E Ink, ignore
`display.accentColor` as a semantic signal; use high-contrast text/patterns, while retaining
`sortKey`.

## Freshness, cache, and failure semantics

There are three different clocks. The client must not collapse them:

1. `generatedAt` — when this exact snapshot body was assembled.
2. `providers[].updatedAt` — the newest of status, usage, credits, and cost timestamps. If none
   exists but the card has an error, the builder uses `generatedAt`; therefore it is not always a
   “last successful provider data” timestamp
   ([`DashboardSnapshotBuilder.swift`, lines 243-256](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/DashboardSnapshotBuilder.swift#L243-L256)).
3. HTTP receipt time — known only to the reader.

The reader's global stale calculation should be:

```text
stale = now >= generatedAt + staleAfterSeconds
```

Use `error` before interpreting a card's `updatedAt` as healthy freshness. Show a compact absolute
or relative “updated” value from `generatedAt`, and preserve `provider.updatedAt` for per-card
detail.

The host caches successful HTTP responses for `--refresh-interval`; cache misses coalesce. A
last-good whole response can replace a failed dashboard refresh for ten refresh intervals, with a
five-minute floor and one-hour ceiling. A returned fallback is the original response body, so its
old `generatedAt` is the only stale signal; there is no `isStale` field or fallback response header
([`CLIServeCommand.swift`, cache policy, lines 199-217](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L199-L217),
[`CLIServeCommand.swift`, last-good selection, lines 283-349](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L283-L349),
[`CLIServeCommand.swift`, stale TTL, lines 1056-1063](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1056-L1063)).

A snapshot with one or more provider errors is still a successful object response and can be
cached. Specifically, `shouldCacheServeResponse` checks for embedded error rows only when the root
JSON value decodes as an array; a dashboard root object falls through to `true`. That response is
stored as both the fresh cache entry and the dashboard's whole-response last-good, replacing an
earlier healthy snapshot. The per-row last-good merge logic applies to the array-shaped `/usage`
and `/cost` responses, not to provider cards inside the object-shaped dashboard snapshot
([`CLIServeCommand.swift`, `shouldCacheServeResponse`, lines 1069-1077](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1069-L1077)).
The reader must not assume every HTTP 200 is fully healthy or rely on the host to preserve each
provider's previous healthy metrics.

Maintain reader-side last-good state per provider. On a healthy/partial card, update every usable
field supplied by the host. On a provider failure that removes previously usable metrics, retain
the prior metrics and overlay the current error/freshness state. Fail closed across identity
changes: schema v1 has no stable account ID, so clear rather than merge when the available
redacted identity tuple changes. If identity is absent and account continuity cannot be proven,
provider-ID-only merging is acceptable only for the explicitly single-account personal MVP; it
must not become the multi-account contract.

Outside the fallback window—or after a host restart has cleared the in-memory cache—the route can
return a top-level non-200 error body rather than a snapshot. The Android client must persist its
own last successfully decoded provider state and keep rendering it with stale/offline state on
401, 5xx, timeout, network loss, or invalid JSON. It should not persist raw provider error text.

Every dashboard response is marked `Cache-Control: no-store` at the HTTP layer even though the
host has its private in-memory response cache
([`CLIServeCommand.swift`, lines 1415-1428](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L1415-L1428)).
The route emits no ETag, Last-Modified, or semantic revision. Because a newly assembled response
has a new `generatedAt`, raw JSON equality is not a meaningful redraw test. The reader should map
the snapshot into its visible render model and compare that model, evaluating freshness state
separately.

## Authentication and transport boundary

The endpoint fails closed when no token is configured. Credentials are accepted only as
`Authorization: Bearer ...`; query-string tokens are rejected, and comparison uses fixed-length
SHA-256 digests without prefix timing leakage
([`CLIServeAuth.swift`, lines 4-50](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeAuth.swift#L4-L50)).
Wire tests cover 401, bearer challenge, `no-store`, correct-token JSON, query-token rejection, and
the empty-provider schema
([`CLIServeRawHTTPTests.swift`, lines 127-186](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/CLIServeRawHTTPTests.swift#L127-L186)).

This does not provide transport encryption. A non-loopback bind refuses startup without both a
token and explicit `--allow-plain-http`; the token and response still cross the LAN in cleartext
([`CLIServeAuth.swift`, lines 107-143](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeAuth.swift#L107-L143),
[`dashboard-api.md`, lines 40-73](https://github.com/ysimo0504/CodexBar/blob/main/docs/dashboard-api.md#L40-L73)).
Trusted-LAN cleartext is an explicitly accepted MVP deployment choice, not a property of schema
v1. Remote access belongs behind TLS or a private overlay and should be resolved by the separate
transport work.

## BOOX MVP client contract

Implement the first reader against these rules:

1. Store only host URL, bearer token, reader display preferences, and sanitized local last-good
   provider state; do not persist the raw response object.
2. Send `GET /dashboard/v1/snapshot` with the token in the Authorization header.
3. Accept only HTTP 200 snapshot bodies with `schemaVersion == 1`; preserve last-good state for all
   other outcomes.
4. Ignore unknown fields and unknown string values. Reject missing/type-invalid required top-level
   fields rather than partially replacing last-good state.
5. Sort cards by `(display.sortKey, id)`. Apply local provider visibility filters after decoding.
6. Render generic cards; Codex/Claude can be placed first by user preference, but they must not use
   separate wire DTOs.
7. Render available metrics even when a card also has `error`; use generic reader-owned error text
   and do not persist/log/display the host's raw `error.message` in the MVP.
8. Maintain per-provider last-good metrics because host whole-response caching can promote a
   provider-error snapshot. Do not merge across an observed identity change.
9. Poll every five minutes. Configure the Mac host with a five-minute response-cache interval if
   the desired cadence is one provider fetch per reader poll; this yields a 15-minute stale hint.
10. Map to an E Ink render model and redraw only when visible values, card order/filtering, error
   state, or freshness bucket changes. Do not redraw solely because `generatedAt` changed.
11. Keep BOOX EPD refresh APIs below a display adapter; none belongs in this JSON contract.

The rendering spike must use synthetic fixtures until the host-side safe-error invariant is
implemented and tested. Reader suppression is defense in depth; it does not prevent sensitive text
from crossing the network.

With a 300-second host cache, “refresh now” inside that window returns the cached snapshot. The
router has no force-refresh query and the cache is consulted before provider work
([`CLIServeCommand.swift`, lines 987-1022](https://github.com/ysimo0504/CodexBar/blob/main/Sources/CodexBarCLI/CLIServeCommand.swift#L987-L1022)).
For MVP, label this action “Sync now” and define it as an immediate host read. If product testing
shows that users require an upstream provider fetch on demand, add a separately specified,
authenticated force-refresh operation later; do not overload an undocumented query parameter.

## Minimum contract hardening

### Required before calling v1 durable

1. **Document compatibility rules.** Adopt the rules below in the platform-neutral boundary spec.
2. **Check in one canonical v1 JSON fixture.** It should include a healthy card, a partial/error
   card, explicit nullable fields, an extra provider-specific window, and an unknown-provider
   example.
3. **Test both sides against that fixture.** The Swift producer should regenerate/compare it, and
   the Android client should decode it and produce the expected generic render model.
4. **Pin ordering/freshness edge cases.** Add focused producer assertions for multiple-provider
   sort keys, a dashboard provider-error response replacing whole-response last-good, and a
   fallback response retaining its original `generatedAt`.
5. **Define a display-safe error boundary.** Prefer a host-owned sanitized dashboard error message
   or a small stable error reason. Until then, clients must treat raw `message` as sensitive and
   ephemeral. A test must prove that representative token/cookie-bearing provider errors do not
   put those values on the dashboard wire.

The current focused tests are strong producer evidence but construct Swift values and inspect
selected JSON members; the raw HTTP smoke test only validates the empty-provider top-level shape
([`DashboardSnapshotBuilderTests.swift`, lines 6-125](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/DashboardSnapshotBuilderTests.swift#L6-L125),
[`CLIServeRawHTTPTests.swift`, lines 158-173](https://github.com/ysimo0504/CodexBar/blob/main/Tests/CodexBarTests/CLIServeRawHTTPTests.swift#L158-L173)).
That is not yet a cross-language compatibility fixture.

### Proposed v1 compatibility policy

- `schemaVersion == 1` is required on the `/dashboard/v1/` route. Clients reject other versions
  while retaining last-good data.
- Adding optional fields or new opaque string values is compatible within v1; clients ignore or
  generically render what they do not know.
- Removing a required field, changing a field's type or meaning, changing percentage units, or
  changing identity/privacy semantics requires a new versioned route and body version.
- Current nullable keys may be sent as `null`; readers also tolerate their absence.
- Provider IDs remain opaque stable identifiers within a schema version. Multi-account cards
  require a new stable card/account identity field before the host may emit duplicate provider IDs.
- Color is advisory. Ordering and textual labels remain usable on monochrome clients.
- Dashboard error text is display-safe and contains no provider credential, cookie, authorization
  header, secret query value, or private credential-file content. This is a proposed invariant,
  not a property enforced by the current producer.

### MVP blocker classification

| Finding | Rendering spike | Personal trusted-LAN MVP | Public/remote durable client |
| --- | --- | --- | --- |
| No canonical v1 fixture/compatibility policy | Not blocking | **Blocker before release-quality handoff** | **Blocker** |
| Host can cache provider-error snapshots as last-good | Not blocking | **Reader-side per-provider merge required** | Stable nonsecret card/account identity also required for multi-account safety |
| Free-form unsanitized `error.message` | Ignore raw field in synthetic fixtures | **Host-side safe-error invariant and tests required before real-account use** | **Blocker** |
| No ETag/304/semantic revision | Not blocking | Not blocking; compare render models | Optional optimization |
| Manual GET cannot bypass host TTL | Not blocking | Not blocking if labeled “Sync now” | Product decision before promising force refresh |
| Snapshot path does not fetch service status | Not blocking | Not blocking | Optional feature |

### Explicitly deferred; not MVP blockers

- ETag/Last-Modified or a host-generated semantic revision.
- Server push, WebSocket, SSE, or background wake protocol.
- An authenticated force-provider-refresh endpoint.
- Host discovery, pairing UI, token rotation UX, TLS termination, and remote access.
- Provider service-status polling on the snapshot path.
- Multi-account cards.
- BOOX/vendor refresh hints in the payload.

## Follow-up ownership

- [#11 Harden Dashboard Snapshot errors for reader clients](https://github.com/ysimo0504/CodexBar/issues/11)
  owns the host-side safe-error invariant and synthetic leakage tests.
- [#12 Choose the macOS Usage Host lifecycle](https://github.com/ysimo0504/CodexBar/issues/12)
  owns foreground CLI versus app-managed service versus LaunchAgent operation.
- [#6 Define the platform-neutral reader boundary](https://github.com/ysimo0504/CodexBar/issues/6)
  owns the durable v1 compatibility policy and canonical fixture.
- [#5 Prove the BOOX snapshot rendering loop](https://github.com/ysimo0504/CodexBar/issues/5)
  owns Android decoding of that fixture and per-provider last-good rendering behavior.

## Final assessment

`/dashboard/v1/snapshot` is already the correct seam and is sufficient for a synthetic-fixture BOOX
rendering spike. It becomes sufficient for the personal trusted-LAN MVP once the host enforces a
safe-error boundary, the reader implements per-provider last-good state, and the v1
fixture/compatibility policy is locked. Keep schema v1 provider-generic and keep E Ink behavior
client-side; harden the current endpoint instead of designing a replacement.
