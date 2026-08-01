# kotlin-lib-mcp

MCP server (Kotlin Multiplatform) that, on request, downloads the **sources** of a
Maven-published Kotlin/Java library (e.g. `io.ktor:ktor-client-core:3.5.1`) and exposes
structured information about it — public API surface, KDoc, dependencies/metadata, and raw
source + search — to MCP clients (Claude Code, Claude Desktop, …). An optional Compose
Desktop dashboard runs the same server in-process for control, logs, and cache browsing.

## Modules

- `core/` — **KMP library**: domain model + use cases, no MCP and no UI.
  - `commonMain`: model (`LibraryCoordinate`, `ApiSymbol`, `KDoc`, `DependencyNode`, …) and
    interfaces (`MavenSourceFetcher`, `SourceAnalyzer`, `LibraryCache`).
  - `jvmMain`: JVM implementations — `MavenSourceFetcher` (Ktor client + `.module`/`.pom`
    parsing), `ZipExtractor`, `AnalysisApiSourceAnalyzer`, on-disk cache.
- `server/` — **JVM app**: registers MCP tools and runs the transports. Runnable headless.
- `dashboard/` — **Compose Desktop** control panel (optional). Embeds `server`.
- `tools/` — **asset generators**, never shipped: the SEP-973 icon PNGs and the repository's
  social preview card. No dependencies, nothing depends on it; built by `./gradlew build` only so
  it cannot rot. `./gradlew :tools:generateIcons` / `:tools:generateSocialPreview`.

`server` and `dashboard` both depend on `core`. `server` runs without Compose.

## Build & run

```
./gradlew build                                   # build all modules
./gradlew test                                    # unit tests
./gradlew :server:run --args="--transport stdio"             # local MCP (default)
./gradlew :server:run --args="--transport http --port 3000"  # Streamable HTTP
./gradlew :dashboard:run                          # Compose Desktop UI
```

**Verify a change:** compile fast with `./gradlew :server:compileKotlin` (pulls in `core`);
run the affected module's tests, e.g. `./gradlew :core:build`. A `Stop` hook runs the fast
compile automatically when Kotlin sources changed (`.claude/hooks/stop-verify.sh`).

## Tech stack (versions live ONLY in `gradle/libs.versions.toml`)

- Kotlin **2.4.x** · MCP `io.modelcontextprotocol:kotlin-sdk-server:0.15.0` · Ktor **3.5.x**
- kotlinx-serialization-json · kotlinx-coroutines
- Source parsing: Kotlin **Analysis API (standalone mode)** — must be version-matched to Kotlin
- Compose Multiplatform (Desktop)
- Logging: Kermit (common) → SLF4J/Logback (JVM sink) · Tests: kotlin-test, coroutines-test, Ktor `MockEngine`

## Conventions & gotchas

- **Versions are centralized** in `gradle/libs.versions.toml`. Kotlin and the Analysis API
  artifacts must share the **exact same version** — always bump them together (use the
  `/analysis-api-bump` skill; cut releases with `/release`). The catalog follows Gradle's
  naming guidance (1–3 dash-separated segments, camelCase inside a segment) and carries **no
  entry a build script doesn't consume** — in particular `[plugins]` holds only the one alias
  a module applies with `alias(...)`; everything else is applied by id from `build-logic`.
- **`core`'s public ABI is locked** by the Kotlin Gradle plugin's built-in `abiValidation`
  (`kmp-library.gradle.kts`; the standalone binary-compatibility-validator it replaces is
  frozen). `./gradlew build` runs `checkKotlinAbi`; intentional API changes need
  `./gradlew updateKotlinAbi` and a committed `core/api/core.api`. The DSL is still
  `@ExperimentalAbiValidation`, so treat a Kotlin bump as able to break it.
- **stdio transport: NEVER write to stdout** except MCP protocol frames. All logging goes to
  stderr or a file, or it corrupts the protocol stream.
- **Core parsing/fetch gotchas** (Analysis API isolation, per-target KMP source jars) live in
  `.claude/rules/analysis-api.md` — loaded automatically when you edit `core/` sources.
- **Cache first.** Downloads + parsed index are cached on disk keyed by
  `group/artifact/version`; tools read the cache. Call `fetch_library` to warm it.

## MCP tools

`fetch_library` · `list_packages` · `list_declarations` · `get_api_signature` · `get_kdoc` ·
`get_source` · `search_source` · `get_dependencies` · `list_versions` · `get_latest_version`

`fetch_library` also accepts a version-less `group:artifact` or `group:artifact:latest` and
resolves the latest stable release (canonical `<release>`/`<latest>` from `maven-metadata.xml`,
with a semantic-version fallback in `core/util/MavenVersions.kt`).

Cached library indexes are also exposed as MCP **resources** (one static resource per cached
library plus a `kotlinlib://{group}/{artifact}/{version}/index` **resource template**), and an
"explain the public API" **prompt** — exercising all three MCP primitives.

**Icons (SEP-973)** are on `serverInfo`, every tool, the prompt and the resource/template
(`icons/Icons.kt`, PNGs in `server/src/main/resources/icons/`, drawn by `:tools`). Inline `data:`
PNG URIs, deliberately — see the file header for
why PNG and why not `https://`; the `icons` traps in the SDK's `add*` overloads are in
`.claude/rules/mcp-server.md`.

Logs go to **stderr** by default (the 2025-11-25-blessed channel; `logback.xml`); the deprecated MCP **logging
capability** (Kermit logs mirror to clients via `attachMcpLogForwarder`; `Logging.kt`) is **opt-in**
behind `--forward-logs-to-client`. `fetch_library` emits **progress notifications** when the request
carries a `progressToken`.

**Elicitation** (`elicitation/VersionElicitation.kt`): a version-less `fetch_library` coordinate asks the
user to pick a version via a **form-mode** `elicitation/create` — SEP-1330's titled `oneOf` single-select,
default = latest stable. Gated purely on the client advertising `elicitation` (no flag); `accept` → that
version, `decline` → latest stable, `cancel` → `isError`. **Everything elicitation-related lives in that one
file on purpose**: the draft 2026-07-28 spec replaces this nested-request shape with MRTR
(`InputRequiredResult` + a client retry), so the migration should be a single-file rewrite. Two traps:
`ClientConnection` (the tool handler's receiver) exposes **only `sessionId`** — capabilities come from
`server.sessions[sessionId]?.clientCapabilities`; and a client may advertise **url-mode only**, which must
not be answered with a form (see `supportsForm`, the counterpart of the SDK's `supportsUrl`).

Elicitation only works at all because **`Protocol` dispatches inbound requests concurrently** once
`notifications/initialized` has arrived — otherwise a server→client request issued *inside* a tool
handler would deadlock on its own answer, since the reply sits unread behind the handler waiting for
it. That is the SDK's own behaviour as of 0.15.0; up to 0.14.0 it took a local decorator
(`transport/ConcurrentDispatchTransport.kt`, now deleted). `ConcurrentDispatchTest` pins it and
`.claude/rules/mcp-server.md` explains what must not be reintroduced.

**Tasks (SEP-1686)** are **opt-in** behind `--tasks`, on **both transports** (`tasks/TaskStore.kt`,
`tasks/TaskHandlers.kt`): `fetch_library` declares `execution.taskSupport: "optional"`, a
task-augmented `tools/call` returns a handle immediately, and `tasks/get`/`result`/`list`/`cancel`
plus `notifications/tasks/status` are served. The SDK ships the wire types but **no execution
engine** — `Server.handleCallTool` ignores `params.task` — so we replace the `tools/call` handler
via `Protocol.setRequestHandler`; the non-task path must stay identical to the SDK's, which
`TaskDispatchTest` pins. Task records are **persisted** to `<cacheDir>/tasks` (`tasks/TaskRecordStore.kt`)
and **owned by the session that created them**, so on HTTP each connection sees only its own. A
record recovered after a restart is *orphaned* — its session is gone, so it is reachable by exact
`taskId` from any caller but never returned by `tasks/list`; that split is the spec's model for a
server with no authorization context, and the reasoning is in the `TaskStore` KDoc. A task still
running at shutdown ends up `failed` either way: `close()` marks and persists it on the calling
thread before cancelling the scope, and `restore()` maps a record a *crash* left `working` (so
`close()` never ran) the same way — leaving that to the cancelled job would race both the restart
and JVM exit. A task whose body needs client
input parks in `TaskStatus.InputRequired` and back: `TaskStore.start` puts a `TaskContext` in the
**coroutine context** (same trick as the OTel span) so a tool body can call `awaitingInput { }` without
any layer between it and the store knowing about tasks.

Registration differs per transport because the SDK only sometimes gives us the session: stdio calls
`registerTaskHandlers` on the `ServerSession` it owns, while HTTP goes through
`installTaskHandlersOnEverySession`. Both that and the completion handler build on
**`onEachSession` (`SessionSetup.kt`)**, which sweeps `server.sessions` from `Server.onConnect` —
that callback takes no argument saying *which* session connected, and picking the newest loses a
session when two connections are accepted concurrently.

**OTel traces** over OTLP/HTTP are **opt-in** behind `--otel` (`telemetry/Telemetry.kt`): one
`SERVER` span per MCP request, opened in `guarded`/`resourceSpan`/`promptSpan`/`completionSpan`,
configured purely from the standard `OTEL_*` env vars via `AutoConfiguredOpenTelemetrySdk`. Off
means inert (no-op tracer, no threads, no network). Attribute names follow the MCP semantic
conventions and are all **Development** status — they live as `AttributeKey` constants in one
place so a rename is a single edit.

**Tool-authoring convention and the resource-template `NoSuchMethodError` gotcha** live in
`.claude/rules/mcp-server.md` (loaded automatically when you edit `server/` sources).
