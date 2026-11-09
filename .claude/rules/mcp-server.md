---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring and server-wide gotchas

Every tool declares a `title`, behavior annotations (`readOnlyHint`/`openWorldHint`;
`fetch_library` additionally `destructiveHint: false`, `idempotentHint: true`), an
`outputSchema` derived from its response DTO's serial descriptor
(`server/.../tools/OutputSchemas.kt`), and an SEP-973 `icon`; `toolResult` returns JSON text **and**
matching `structuredContent`. When adding a tool, pass all four to `addTool` — use the shared
`LOCAL_READ_ONLY`/`REPOSITORY_READ_ONLY` annotation constants in `ToolSupport.kt`, and add a new
`Glyph` entry in `server/.../icons/Icons.kt`, drawn by a matching `Glyph` in
`tools/.../GenerateIcons.kt` (then `./gradlew :tools:generateIcons` writes its PNG).

**Arguments are validated, never quietly defaulted.** The SDK checks nothing against `inputSchema`,
so the server does: `addTool` (the `ToolSupport.kt` extension) rejects any argument the schema does
not declare — its `ToolSchema` cannot say `additionalProperties: false` (tracked upstream as
`kotlin-sdk#300`, milestone 0.24.0), so this is the only place that check can live, and it means a
new argument must be in the schema to be accepted at all. Read
arguments with the typed readers (`stringArg`/`intArg`/`booleanArg`), which throw on a wrong type
rather than returning null into a `?: default`. Declare integers with `intProp(description,
minimum, maximum, default)` fed from `LibraryService`'s limit constants, so the advertised bounds are
the enforced ones; keep the range and default in the prose too, for clients that drop schema
keywords — `ToolRegistrationTest` fails if the two disagree.

**Failures reach the client through `guarded`, filtered.** Every tool calls
`guarded(request, revealInternalErrors = service.exposeLocalPaths)`. Only exceptions listed in
`isWrittenForTheCaller` (`ToolSupport.kt`) are returned verbatim; anything else is reduced to its
type unless the client shares the machine (stdio), and logged to stderr. A new exception whose
message is meant for the model belongs on that list — but only if the message names no absolute
path: `ZipExtractionException` is kept off it because it names the archive by its cache path.

**Every tool result must be bounded.** The rule: if a result's size is a function of the *library*
rather than of the *arguments*, it needs a cap — and the cap has to be the tool's own, because
nothing downstream will impose one. This was learned three times: `list_declarations` shipped
unpaged and overflowed at 272 KB; `get_source` returned whole files, and the sources this server
fetches include a single generated file of 1.7 MB; `list_packages`, `list_versions` and
`get_dependencies` were then capped together. Remember that `toolResult` emits the payload **twice**
— as text and as `structuredContent` — so the wire cost is double whatever the page measures.

Follow the established shape rather than inventing one: `maxResults` + `offset` arguments, both
clamped, and `totalCount` + `truncated` in the result so the caller can see what it did not get and
page for the rest. Where rows are the wrong unit, bound the real one instead and say so —
`get_source` pages by line *and* clips by characters (a line cap is not a size cap when a generated
line runs to thousands of characters), `diff_versions` caps hunk text per file and per page in
characters on top of its line budget (a file over either stays on the page as `diffOmitted`, so
`offset` paging is unchanged), and `get_dependencies` prunes by node count breadth-first (`depth`
bounds resolution cost, not result size). A `totalCount` that would need a full scan to compute may
stop at a ceiling and say so: `search_source` counts to `MAX_SEARCH_MATCHES_COUNTED` and then
reports `totalCountCapped: true`. Name such flags so `false` is the normal case — `toolResult`
omits default values and the output schema carries no defaults, so a missing boolean reads as
`false` — and keep `truncated` meaning strictly "advancing `offset` returns more", never "more
exist somewhere", or a client paging until it is false never stops.

**Resources fall under the same rule**, with less room: a resource has no arguments to page with,
and a client may attach one to a conversation wholesale. So `…/index` is a summary (counts plus the
first 200 packages), not the parsed index — which runs to megabytes for a large library — and each
`…/package/{package}` caps its declarations, keeping only the KDoc summary. Anything past a cap is
reachable through the tools.

**Resource links count against the result too.** `toolResult(value, links)` appends
`resource_link` blocks after the text; only `fetch_library` (its index) and `list_packages` (the
first `MAX_PACKAGE_LINKS` of the page) send them. Links on every tool would be noise, and one link
per row would outweigh a page of 1 000 packages — so cap them like rows. Never put an icon on a link
(~1.1 KB of base64 each), and never link what no template can resolve: the root package's URI ends
in `package/`, so `libraryPackageLink` returns `null` for it. `ResourceLinksTest` reads every link
back through `resources/read`.

**Icons gotcha:** `icons` exists only on the `Tool`/`Prompt`/`Resource`/`ResourceTemplate` types,
never on the SDK's `addTool(name, …)`/`addPrompt(name, …)`/`addResource(uri, …)` convenience
overloads — and there is no `addResource(Resource, handler)` at all, so a resource with icons has
to go through `addResources(listOf(RegisteredResource(…)))` (`addAll` fires the same `listChanged`
listeners, so `resources/list` stays live). The trap is `ToolSupport.kt`'s `addTool` extension:
drop its `icon` argument and the call silently resolves back to the SDK's iconless *member*
overload and still compiles. `ToolRegistrationTest.everyToolDeclaresADistinctIcon` pins that.

**Per-session registration goes through `onEachSession` (`SessionSetup.kt`).** Anything the SDK does
not wire into sessions itself — the `completion/complete` handler, the `tasks/…` surface — has to be
installed by us, and the only hook is `Server.onConnect`, which takes no argument saying *which*
session connected (and `createSession` is not `open`). `onEachSession` **sweeps** `server.sessions`
on each callback, claiming each through a concurrent set. **Never reach for
`sessions.values.last()`**: the session is registered before `onConnect` fires, so two concurrent
`createSession` calls can interleave such that "the last session" is already configured while the
other is left with no handler for its whole lifetime. Ordering is safe either way — `createSession`
fires `onConnect` before the Streamable HTTP route feeds the POST body to the transport.
`SessionSetupTest.configuresEverySessionNotJustTheNewest` is the test that actually discriminates
the sweep from the shortcut; keep it green.

**Dispatch concurrency is the SDK's job now (0.15.0):** `Protocol` launches inbound requests and
notifications on a per-connection handler scope once `notifications/initialized` has arrived,
keeping responses inline and letting `ping`/`cancelled`/`progress`/`initialized` bypass its
concurrency bounds. Up to 0.14.0 the stdio pipeline was strictly one-frame-at-a-time, so any
server-to-client request from inside a tool handler (an `elicitation/create`, sampling, roots)
deadlocked forever; `transport/ConcurrentDispatchTransport.kt` existed solely to break that and was
**deleted** in the 0.15.0 bump. Do not reintroduce a decorator like it — launching frames before
`Protocol` sees them defeats its `CoroutineStart.UNDISPATCHED` arrival-order guarantee and races
`notifications/cancelled` against the in-flight registration it depends on.

Two obligations this puts on our code, both already satisfied — keep them that way:
always re-throw `CancellationException` from handlers (that is what makes a withdrawn
`tools/call` actually stop the download) — which in practice means **never `runCatching` in server
code**: it catches `Throwable`, cancellation included, and was how the rule got broken twice (the
completion handler, `fetch_library`'s source-root lookup). Use `runCatchingCancellable`
(`RunCatching.kt`) or a `try` with a re-throwing `catch (e: CancellationException)` arm; CI's
`build` job greps `server/src/main` for a bare `runCatching` and fails. And never replace a control-method handler
(`ping`, `notifications/cancelled`, `notifications/progress`, `notifications/initialized`) with slow
code — replacing `cancelled` disables inbound cancellation outright. `ConcurrentDispatchTest` pins
the anti-deadlock property, the serial handshake, and cancellation.

**OTel traces** over OTLP/HTTP are **opt-in** behind `--otel` (`telemetry/Telemetry.kt`): one
`SERVER` span per MCP request, opened in `guarded`/`resourceSpan`/`promptSpan`/`completionSpan`,
configured purely from the standard `OTEL_*` env vars via `AutoConfiguredOpenTelemetrySdk`. Off
means inert (no-op tracer, no threads, no network). Attribute names follow the MCP semantic
conventions and are all **Development** status — they live as `AttributeKey` constants in one
place so a rename is a single edit.

**Telemetry authoring:** every MCP entry point opens its span through the helpers in
`server/.../telemetry/Telemetry.kt` (`guarded` for tools — it is a `ClientConnection` extension so
`mcp.session.id` comes free — plus `resourceSpan`/`promptSpan`/`completionSpan`). Attribute names
are `AttributeKey` constants at the top of that file; do not inline `mcp.*`/`gen_ai.*` strings at
call sites, since the upstream conventions are still Development status. Nothing in the exporter
path may write to stdout.
