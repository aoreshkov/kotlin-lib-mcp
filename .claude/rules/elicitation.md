---
paths:
  - "server/src/**/elicitation/**/*.kt"
---

# Elicitation

**Elicitation** (`elicitation/VersionElicitation.kt`): a version-less `fetch_library` coordinate asks the
user to pick a version via a **form-mode** `elicitation/create` — SEP-1330's titled `oneOf` single-select,
default = latest stable. Gated purely on the client advertising `elicitation` (no flag); `accept` → that
version, `decline` → latest stable, `cancel` → `isError`. **Everything elicitation-related lives in that one
file on purpose**: the draft 2026-07-28 spec replaces this nested-request shape with MRTR
(`InputRequiredResult` + a client retry), so the migration should be a single-file rewrite. Two traps:
`ClientConnection` (the tool handler's receiver) exposes **only `sessionId`** — capabilities come from
`server.sessions[sessionId]?.clientCapabilities`; and a client may advertise **url-mode only**, which must
not be answered with a form (see `supportsForm`, the counterpart of the SDK's `supportsUrl`).
