# CodexBar

CodexBar presents AI provider limits, resets, credits, costs, and status while keeping provider access under the
user's control. CodexBar Ink extends that information surface to e-ink reader devices.

## Language

**CodexBar Ink**:
The e-ink dashboard product, initially optimized for BOOX readers.
_Avoid_: Ink CodexBar, e-ink mode

**Usage Host**:
The trusted computer that owns provider sessions and produces display data for reader devices.
_Avoid_: Cloud backend, credential server

**Reader Client**:
The reader-side interface that consumes display data without holding provider credentials.
_Avoid_: Provider client, BOOX backend

**Reader Core**:
The platform-neutral part of a Reader Client that turns Dashboard Snapshots and saved last-good data into a stable
presentation state.
_Avoid_: BOOX core, API client

**Platform Shell**:
The device-family boundary around Reader Core that owns transport, secret storage, lifecycle, persistence, and UI
hosting for one platform.
_Avoid_: Reader backend, device core

**Display Adapter**:
The optional device-specific boundary that applies a rendered state to a physical display without interpreting
provider data.
_Avoid_: Provider renderer, screen backend

**Dashboard Snapshot**:
A versioned, display-ready view of provider usage, reset, credit, cost, status, and freshness data with sensitive
identity reduced.
_Avoid_: Raw usage response, account dump

**Usage Provider**:
An external AI service whose limits, credits, costs, or service status CodexBar presents.
_Avoid_: Account, model
