---
status: accepted
---

# Keep provider collection behind a platform-neutral Reader boundary

CodexBar Ink will reuse the versioned Dashboard Snapshot as its only provider-data boundary. The Usage Host owns
provider credentials, collection, ordering, redaction, and schema production; Reader Clients never import provider
logic or credentials. This keeps the BOOX APK shippable first without making Android or Onyx concepts part of the
wire contract.

## Boundary

Data moves in one direction:

```text
Usage Providers
  -> Usage Host
  -> Dashboard Snapshot v1
  -> Platform Shell
  -> Reader Core
  -> immutable Presentation State
  -> platform UI
  -> Display Adapter
```

- **Usage Host** owns provider access, refresh work, redaction, safe error projection, provider order, and the
  versioned `/dashboard/v1/snapshot` response. It exposes no Reader UI or device behavior.
- **Dashboard Snapshot** is the complete cross-platform contract. Provider IDs and extension strings are opaque;
  no client imports the Swift `UsageProvider` model.
- **Platform Shell** owns HTTP/authentication, secure token storage, receipt time, lifecycle-driven polling,
  persistence, locale/time formatting inputs, and launching the platform UI. Android, browser, and later native
  clients implement separate shells.
- **Reader Core** is deterministic and platform-neutral. It validates schema v1, normalizes unknown values, merges
  healthy fields into per-provider last-good state, calculates freshness, orders cards, and emits semantic changes
  plus an immutable Presentation State. It owns no HTTP, Keystore, Android lifecycle, View, or Onyx SDK type.
- **Platform UI** renders Presentation State. Codex and Claude may use priority layouts, but every other or unknown
  provider uses the same generic card path.
- **Display Adapter** receives semantic render regions after UI state is committed. `GenericDisplayAdapter` uses
  standard platform invalidation; `OnyxDisplayAdapter` may request BOOX partial/full refresh. Adapter failure cannot
  block decoding, persistence, or generic rendering.

## Dashboard Snapshot v1 compatibility

- Clients accept only integer `schemaVersion == 1`; any other version or an invalid required top-level field rejects
  the whole new snapshot while retaining last-good state.
- Adding optional object fields, providers, windows, or opaque string values is compatible. Clients ignore unknown
  fields and render unknown values generically.
- Removing or renaming required fields, changing their wire types, or changing existing field semantics requires a
  new versioned route.
- Nullable fields may be absent or JSON `null`. Timestamps are ISO-8601 strings; readers accept fractional seconds.
  Numeric percentages, credits, and costs are decoded as floating-point values even when encoded as integers.
- Provider display order is `display.sortKey`, then opaque `id`; array order is not the durable ordering contract.
- Reader Core keeps per-provider last-good data. A card-level error never erases previously usable metrics, and a
  whole-snapshot failure changes only global freshness/error state.
- Every implementation must decode
  [`dashboard-snapshot-v1-canonical.json`](../fixtures/dashboard-snapshot-v1-canonical.json) in a contract test.

## Consequences

BOOX refresh APIs remain replaceable and cannot leak into transport or state models. Browser, Kindle/Kobo browser
experiments, Bigme Android, and later Linux-native clients can reuse the snapshot and state rules while supplying
their own Platform Shell, UI, persistence, and display behavior. The trade-off is intentional duplication of thin
platform integration code instead of a shared UI/runtime that would couple all readers to Android or the browser.
