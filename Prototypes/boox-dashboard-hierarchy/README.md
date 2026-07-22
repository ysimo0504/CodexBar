# PROTOTYPE — BOOX Leaf3C dashboard hierarchy

Question: which information hierarchy, density, orientation, freshness treatment, and refresh-region shape makes
CodexBar Ink readable as a 7-inch Leaf3C glance dashboard?

This is disposable, fixture-only HTML. It is not Android product code and performs no network requests.

## Run

From this directory:

```bash
python3 -m http.server 4179
```

Open:

- `http://127.0.0.1:4179/?variant=A` — portrait priority stack;
- `http://127.0.0.1:4179/?variant=B` — portrait quota ledger;
- `http://127.0.0.1:4179/?variant=C` — landscape provider focus.

Use the floating arrows or keyboard Left/Right to switch. Add `&zones=1` to reveal proposed semantic partial-refresh
regions. Add `&capture=1` to hide the prototype switcher and render at the exact 1264 x 1680 portrait or 1680 x
1264 landscape panel-address resolution.

## Constraints represented

- grayscale-only meaning, high contrast, no animation, gradients, transparency, or required colour;
- Codex and Claude first, with generic rows for all other enabled providers;
- visible freshness, per-provider stale/error state, and last-good behavior;
- 5-minute foreground polling without per-second clocks;
- semantic provider/row refresh regions and an explicit full-refresh budget;
- synthetic schema-v1 values only; no provider, account, token, cookie, or Keychain access.

## Review status

Rendered at the Leaf3C panel-address baseline and reviewed in Chromium.

Recommended default: **A — portrait priority stack**.

- It matches the fixed-stand portrait context and keeps Codex plus Claude readable without navigation.
- Other providers remain visible in compact generic rows; stale and error states do not replace last-good values.
- Header, each priority provider, and the grouped secondary list are stable semantic partial-refresh regions.
- Typography remains materially larger than the ledger while showing more system state than focus mode.

Keep from B: the compact risk summary can become a later optional overflow surface when many providers are enabled.
Reject B as the default because table density shrinks text and creates many high-contrast borders. Reject C for MVP
because landscape conflicts with the chosen mount/orientation, hides cross-provider comparison behind navigation, and
uses a large solid-black region that is less friendly to e-paper cleanup.

Implementation decision:

- portrait, supporting both portrait rotations;
- no scroll on the default snapshot screen;
- Codex and Claude priority cards, then generic compact rows in host order;
- global freshness in the header; provider freshness/error beside retained provider data;
- manual sync is secondary and never promises a provider-cache bypass;
- partial refresh only for semantically changed regions; full refresh on cold start, explicit “clear ghosting”, or a
  semantic-update threshold calibrated by Leaf3C waveform A/B testing. Do not hard-code the count or a timer first.

The full variant set stays on this throwaway branch; only this decision enters the implementation plan.
