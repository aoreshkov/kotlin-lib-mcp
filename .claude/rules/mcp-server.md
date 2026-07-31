---
paths:
  - "server/src/**/*.kt"
---

# MCP tool authoring & resource-template gotcha

Every tool declares a `title`, behavior annotations (`readOnlyHint`/`openWorldHint`;
`fetch_library` additionally `destructiveHint: false`, `idempotentHint: true`), an
`outputSchema` derived from its response DTO's serial descriptor
(`server/.../tools/OutputSchemas.kt`), and an SEP-973 `icon`; `toolResult` returns JSON text **and**
matching `structuredContent`. When adding a tool, pass all four to `addTool` — use the shared
`LOCAL_READ_ONLY`/`REPOSITORY_READ_ONLY` annotation constants in `ToolSupport.kt`, and add a new
`Glyph` entry (plus its PNG, via `assets/icons/GenerateIcons.java`) in `server/.../icons/Icons.kt`.

**Icons gotcha:** `icons` exists only on the `Tool`/`Prompt`/`Resource`/`ResourceTemplate` types,
never on the SDK's `addTool(name, …)`/`addPrompt(name, …)`/`addResource(uri, …)` convenience
overloads — and there is no `addResource(Resource, handler)` at all, so a resource with icons has
to go through `addResources(listOf(RegisteredResource(…)))` (`addAll` fires the same `listChanged`
listeners, so `resources/list` stays live). The trap is `ToolSupport.kt`'s `addTool` extension:
drop its `icon` argument and the call silently resolves back to the SDK's iconless *member*
overload and still compiles. `ToolRegistrationTest.everyToolDeclaresADistinctIcon` pins that.

**Classpath-shadowing gotcha:** `kotlin-compiler` (via `core`) is a fat jar bundling old,
*unrelocated* copies of libraries we also depend on for real, and the first copy on the classpath
wins. Gradle sorts the genuine artifacts well behind it (shared transitives sort late, so
declaration order does **not** fix it), so `server/build.gradle.kts` hoists the real jars to the
front of the `test`, `run` and `startScripts` classpaths (`hoistOverKotlinCompiler`). Two families
are known:

- `kotlinx.collections.immutable` — 127 classes of a pre-0.5 release. Since kotlin-sdk 0.15.0 built
  `Protocol` and `FeatureRegistry` on the newer `PersistentMap.putting`/`removing`, this took out
  `Server.addTool` itself; before that it only surfaced inside the SDK's `PathSegmentTemplateMatcher`,
  which is why `resources/SegmentTemplateMatcher.kt` exists and why `ServerOptions` passes
  `segmentTemplateMatcherFactory`. The hoist likely makes the SDK's own matcher viable again, but the
  custom one is tested and in use — don't swap it without re-verifying at runtime, not just in tests.
- `io.opentelemetry.api` 1.41.0 — see below.

When adding any dependency `kotlin-compiler` might also bundle, check with
`unzip -l <kotlin-compiler.jar> | grep <package-path>` before trusting the classpath.

**Same trap, OpenTelemetry:** `kotlin-compiler` also bundles ~83 *stripped stubs* of an old
unrelocated `io.opentelemetry.api` (1.41.0 — `Attributes`, `OpenTelemetry`, `Tracer`,
`TracerProvider` are empty shells), so without the hoist above `--otel` dies with
`NoSuchMethodError: Attributes.empty()`. Keep both prefixes in `hoistOverKotlinCompiler`'s list, and
keep that list *local to the function* — a script-level `val` captured in the filter is a Gradle
script object reference and the configuration cache refuses to serialize it.

**Tasks gotcha:** `tasks/TaskHandlers.kt` **replaces** the SDK's `tools/call` handler (that is the
only way to answer with a `CreateTaskResult` — `Server.handleCallTool` is hard-typed to
`CallToolResult` and ignores `params.task`). The replacement therefore applies to *every* tool, so
its non-task branch must reproduce the SDK's semantics exactly: unknown tool → `isError` result,
`CancellationException` and `UrlElicitationRequiredException` re-thrown, anything else flattened to
`isError`. `TaskDispatchTest` pins this — if you touch `dispatchToolCall`, keep those tests green.
The `tasks` capability and the handlers are both driven by `ServerConfig.tasks` so they can never
disagree; advertising the capability without handlers makes the SDK route methods that answer
nothing.

**Registering them differs per transport**, because the SDK only sometimes hands us the session.
stdio calls `registerTaskHandlers` on the `ServerSession` `runStdioServer` owns. HTTP has no such
object — `mcpStreamableHttp` creates a session per connection internally — so it goes through
`installTaskHandlersOnEverySession`, which delegates to `onEachSession` (below).

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

**Tasks are owned by a session.** One `TaskStore` serves every session, so every client-facing
operation takes the caller's `sessionId` as `owner` and filters by it — `dispatchToolCall` takes it
from `ClientConnection.sessionId`, the `tasks/…` handlers from `ServerSession.sessionId`, and they
are the same id by construction. A `taskId` belonging to another session raises the *same*
`UnknownTaskException` as one that never existed; keep it that way, or the error becomes an oracle
for other sessions' ids. This is invisible with a single stdio client and load-bearing on HTTP,
where `mcpStreamableHttp` creates a session per connection and `tasks/result` returns whole tool
results. Never add an unscoped `list()`/`get()` back to `TaskStore`.

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
`tools/call` actually stop the download), and never replace a control-method handler
(`ping`, `notifications/cancelled`, `notifications/progress`, `notifications/initialized`) with slow
code — replacing `cancelled` disables inbound cancellation outright. `ConcurrentDispatchTest` pins
the anti-deadlock property, the serial handshake, and cancellation.

**Telemetry authoring:** every MCP entry point opens its span through the helpers in
`server/.../telemetry/Telemetry.kt` (`guarded` for tools — it is a `ClientConnection` extension so
`mcp.session.id` comes free — plus `resourceSpan`/`promptSpan`/`completionSpan`). Attribute names
are `AttributeKey` constants at the top of that file; do not inline `mcp.*`/`gen_ai.*` strings at
call sites, since the upstream conventions are still Development status. Nothing in the exporter
path may write to stdout.
