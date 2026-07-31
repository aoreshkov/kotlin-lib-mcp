# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - 2026-07-31

This release brings the server up to the MCP **2025-11-25** specification: tasks,
elicitation, icons, completions and the 2020-12 schema dialect, plus opt-in OpenTelemetry
tracing. Everything new that changes how a client talks to the server is either negotiated
(elicitation) or behind a flag (`--tasks`, `--otel`, `--forward-logs-to-client`), so a
0.3.0 client sees the same server until it asks for more.

### Added
- **Tasks (SEP-1686), opt-in behind `--tasks`, on both transports.** `fetch_library` declares
  `execution.taskSupport: "optional"`, so a client may send a task-augmented `tools/call` and get a
  handle back immediately instead of holding a request open for the seconds-to-tens-of-seconds a
  download → analyze → cache cycle takes; `tasks/get`, `tasks/result`, `tasks/list`, `tasks/cancel`
  and `notifications/tasks/status` are served, and progress notifications still fire for clients
  that prefer to watch them. The Kotlin SDK ships the full wire surface but **no execution engine**
  — `Server.handleCallTool` ignores `params.task` — so the `tools/call` handler is replaced via
  `Protocol.setRequestHandler`, with its non-task branch reproducing the SDK's semantics exactly.

  Properties worth knowing:
  - **Records are durable.** Each task is written to `<cache-dir>/tasks/<taskId>.json`
    (temp-file-then-move, so a crash cannot leave a half-written record), and a completed task and
    its result are still retrievable after a restart. Anything still `working` when the server
    stopped is restored as `failed` — the record survived, the work did not — which also keeps a
    zombie `working` record from blocking `tasks/result` until its TTL expires. This is restart
    survival on a single node; multi-replica would additionally need shared storage and a real
    authorization context.
  - **Records are owned by the session that created them**, so over HTTP — where the SDK creates a
    session per connection — each client sees only its own tasks, and a `taskId` belonging to
    another session raises the same error as one that never existed rather than confirming it
    exists. The exception is a record recovered after a restart: its session is gone, so it is
    reachable by exact `taskId` from any caller but never listed by `tasks/list`. That is the model
    the spec prescribes for a receiver with no authorization context — as here, loopback-first with
    no auth — and task ids are 122-bit `SecureRandom` UUIDs accordingly. The limitation is
    documented in the README, as the spec asks.
  - **Spec-exact terminal states**: a tool result carrying `isError: true` fails the task (the
    result is still returned by `tasks/result`); `tasks/cancel` on an already-terminal task returns
    `-32602` rather than pretending the cancel took effect; and `tasks/result` on a `working` or
    `input_required` task **blocks until terminal** instead of erroring — which is what makes the
    elicitation flow below work through tasks.
- **Elicitation for a version-less `fetch_library` (SEP-1330).** A coordinate with no version (or
  `:latest`) used to silently resolve the latest stable release. When the client advertises the
  `elicitation` capability the server now asks instead, with a form-mode `elicitation/create`
  carrying a single-select version picker in SEP-1330's titled `oneOf` shape and the latest stable
  release as the schema default: `accept` fetches the chosen version, `decline` fetches latest
  stable (the previous behavior), `cancel` returns `isError` having downloaded nothing. There is no
  flag — capability negotiation is the opt-in, so a client that advertises nothing behaves exactly
  as before, and a client advertising url-mode only is never sent a form. Accepted values are
  validated against the offered list before they reach a repository URL. Under `--tasks` the call
  parks in `input_required` while the question is outstanding and returns to `working` on the
  answer.
- **Icons (SEP-973)** on every MCP object the server exposes: `serverInfo`, all ten tools, the
  `explain_public_api` prompt, and the library-index resource and resource template. `serverInfo`
  also gained the `title` and `websiteUrl` branding fields it already declares in `server.json`.
  Icons are inlined as `data:` PNG URIs — PNG because it is the one format icon-rendering clients
  MUST support, inline because a stdio server has no origin for the spec's same-origin guidance and
  inlining needs no fetch, works offline, and ships in the container image. The glyphs are drawn by
  `assets/icons/GenerateIcons.java` and kept to ~800 bytes encoded, since they ride in every
  `tools/list`.
- **OpenTelemetry traces over OTLP/HTTP, opt-in behind `--otel`.** One `SERVER` span per MCP
  request (`tools/call`, `resources/read`, `prompts/get`, `completion/complete`), named
  `{method} {target}` and carrying `mcp.method.name`, `gen_ai.tool.name`, `mcp.session.id` and
  `network.transport`; a tool returning `isError` is marked `error.type=tool_error`. Inbound trace
  context is read from `params._meta` per SEP-414, so a tracing client gets one connected trace.
  Configuration is purely the standard `OTEL_*` environment variables via
  `AutoConfiguredOpenTelemetrySdk` — no bespoke flags — and off means genuinely inert: a no-op
  tracer, no SDK, no exporter threads, no network. The MCP `mcp.*`/`gen_ai.*` attribute names are
  still Development status upstream, which is a second reason the feature is opt-in.
- **Argument completion (`completion/complete`).** Completion-aware clients can now autocomplete
  the `explain_public_api` prompt arguments (`coordinate`, `package`) and the
  `kotlinlib://{group}/{artifact}/{version}/index` resource-template variables — all served from
  the on-disk cache with no network. Completions narrow on the context already supplied (artifact
  by group, version by group + artifact, package by coordinate), are prefix-matched
  case-insensitively, de-duplicated, sorted and capped at 100 with an accurate `hasMore`. The
  handler never throws; any failure yields an empty completion. It is registered on **every**
  session as it connects, not just the newest one — `Server.onConnect` says nothing about *which*
  session connected, and picking `sessions.values.last()` loses a session when two connections
  interleave, leaving it answering `-32601` for its whole lifetime.
- **Registry discoverability metadata**: `title`, `websiteUrl` and `icons` (new `assets/icon.svg`)
  in `server.json`, so registry UIs — including github.com/mcp — can surface them. This is the
  first release in which they go live.

### Changed
- **MCP Kotlin SDK 0.14.0 → 0.15.0.** The wire types are unchanged; the substance is that
  `Protocol` now dispatches inbound handlers concurrently itself (bounded, order-preserving, gated
  on `notifications/initialized`) and tracks in-flight requests. Consequences:
  - **A long `fetch_library` can now be cancelled by the client** and the download actually stops,
    rather than running on unobserved.
  - A server→client request issued from inside a tool handler — elicitation, and sampling or roots
    should they ever be used — no longer deadlocks the single-frame-at-a-time stdio pipeline, and
    works over Streamable HTTP too. `ConcurrentDispatchTest` pins the behaviour.
- **Logging capability retired to opt-in** (MCP 2025-11-25 blesses stderr for all stdio
  logging): the server no longer advertises the deprecated `logging` capability by default,
  and log-forwarding to clients (`notifications/message`) is now gated behind the new
  `--forward-logs-to-client` flag. Stderr (via `logback.xml`) is the primary channel; the
  forwarder remains available for stdio clients that surface MCP log messages but drop stderr.
- **`fetch_library` progress notifications now carry `relatedRequestId`**, so Streamable HTTP
  routes them onto the originating `tools/call`'s SSE stream instead of the standalone one.
- **The HTTP transport now sends an SSE heartbeat every 30s.** The SDK's default is none, and a
  `fetch_library` that downloads and parses a large library can idle past a proxy's timeout.
- **JSON Schema 2020-12 declared explicitly on every tool input and output schema (SEP-1613).**
  2020-12 is already the MCP default when `$schema` is absent, so this is interop hardening for
  strict or legacy clients, not a behavioral change — the schemas were already 2020-12-clean.
- **The advertised MCP server version is now build-derived from `gradle.properties`**
  (baked into a classpath resource read by `ServerVersion`) instead of a hand-maintained
  `SERVER_VERSION` constant, so it can never drift from the release version. The release
  tag guard drops its third-file check accordingly.
- **Dependency refresh**: logback 1.5.38 → 1.6.0, Kover 0.9.8 → 0.9.9, a fresh
  `eclipse-temurin:25-jre` base-image digest, grouped GitHub Actions bumps, and the pinned
  `mcp-publisher` v1.7.9 → v1.8.0. Kotlin stays at 2.4.0. OpenTelemetry 1.64.0 is a new runtime
  dependency of the server, BOM-governed and using the JDK sender rather than OkHttp so it adds no
  transitive dependencies.
- **Build only — ABI validation moved into the Kotlin Gradle plugin.** The standalone
  `binary-compatibility-validator` plugin is frozen (JetBrains folded it into KGP), so
  `kmp-library.gradle.kts` now enables `abiValidation {}` instead. The dump format and
  `core/api/core.api` are byte-identical; the task names changed: `apiCheck` → `checkKotlinAbi`
  (still run by `./gradlew build`) and `apiDump` → `updateKotlinAbi`. The DSL is experimental,
  so a Kotlin bump can break it.
- **Build only — version catalog cleaned up** against Gradle's published guidance: entries no
  build script consumed are gone (`junit`, `slf4j-android`, `kotlin-metadata-jvm`, two unused
  Ktor artifacts, and every `[plugins]` alias except the one a module applies with `alias(…)`),
  and aliases now follow the documented naming convention (1–3 dash-separated segments,
  camelCase inside a segment) — e.g. `analysis-api-symbol-light-classes` →
  `analysisApi-symbolLightClasses`. No dependency of the published modules changed — the
  removed entries were declared nowhere and the renames only move aliases.

### Fixed
- **`kotlinx.collections.immutable` classpath shadowing.** `kotlin-compiler` (pulled in by `:core`
  for the Analysis API) bundles 127 classes of an unrelocated pre-0.5 copy, which wins on the
  runtime classpath; kotlin-sdk 0.15.0 builds `Protocol` and `FeatureRegistry` on the newer
  `PersistentMap.putting`/`removing`, so even `Server.addTool` threw `NoSuchMethodError` at
  startup. `server/build.gradle.kts` now hoists the genuine jar ahead of `kotlin-compiler` on the
  `test`, `run` and `startScripts` classpaths, alongside the OpenTelemetry jars that need the same
  treatment against an old bundled `io.opentelemetry.api`. This affects the shipped distribution,
  not just tests.

## [0.3.0] - 2026-07-14

### Added
- **`list_declarations` paging**: `maxResults` (default 100, capped at 500) and `offset`
  inputs, with `totalCount` and `truncated` in the response, so listing a large library
  returns a bounded page instead of flooding the client's context. (#23)
- **`--host <addr>` flag** for the HTTP transport, to opt into a non-loopback bind address
  (intended for use behind an authenticating reverse proxy). (#23)

### Changed
- The Streamable HTTP transport now binds **`127.0.0.1` by default** instead of `0.0.0.0`,
  so the endpoint is not reachable from other hosts unless `--host` widens it. The SDK's
  Host/Origin allowlist only filters request headers, not the listening interface. (#23)

### Security
- **Coordinate validation**: Maven coordinate segments are now restricted to
  `[A-Za-z0-9._+-]` and reject `.`, `..`, and path separators, closing a path-traversal
  where a crafted `group:artifact:version` could resolve outside the on-disk cache root. (#23)
- **Zip-bomb guard**: the extractor enforces its uncompressed-size budget *during* each
  entry copy rather than after, so a single highly-compressed entry can no longer be written
  to disk in full before the limit trips. (#23)
- **Download size cap**: artifact downloads are limited to 200 MiB — an over-large declared
  `Content-Length` is rejected up front and the stream is aborted if it exceeds the cap —
  preventing out-of-memory from a hostile or oversized artifact. (#23)

## [0.2.0] - 2026-07-11

### Added
- **MCP resource template**: `kotlinlib://{group}/{artifact}/{version}/index` is published
  via `resources/templates/list`, so clients can address any cached library index directly;
  reading an uncached coordinate returns a friendly "call `fetch_library` first" error. (#17)
- **MCP logging capability**: server logs are mirrored to connected clients as
  `notifications/message`, honoring each session's `logging/setLevel` — useful on stdio,
  where clients routinely discard stderr. (#17)
- **`fetch_library` progress notifications** at each phase boundary
  (download → analyze → cache) when the request carries a `progressToken`. (#17)
- **HTTP hardening flags**: repeatable `--allowed-host` / `--allowed-origin` extend the
  localhost-only DNS-rebinding defaults for non-localhost deployments. (#17)
- README demo animation and dashboard screenshot. (#15)

### Changed
- Supply-chain hardening: all GitHub Actions pinned to full commit SHAs, `mcp-publisher`
  pinned to v1.7.9 with SHA-256 verification, Docker base image digest-pinned (with a
  Dependabot `docker` ecosystem to keep it fresh), Gradle wrapper distribution checksum
  added, and JetBrains repositories content-filtered to `org.jetbrains.*`. (#18)
- Release pipeline now runs the full test suite before packaging and publishes SLSA build
  provenance attestations for the release zip and the GHCR image
  (verifiable with `gh attestation verify`).
- Docker base image bumped from `eclipse-temurin:21-jre` to `eclipse-temurin:25-jre`. (#19)
- CI/build dependency refresh: GitHub Actions majors (checkout v7, CodeQL v4, Docker
  actions, `gradle/actions` v6) and logback 1.5.38. (#14, #16)

### Removed
- Unused `ksp` plugin entry from the version catalog.

## [0.1.0] - 2026-07-10

Initial public release.

### Added
- **10 MCP tools**: `fetch_library`, `list_packages`, `list_declarations`,
  `get_api_signature`, `get_kdoc`, `get_source`, `search_source`, `get_dependencies`,
  `list_versions`, `get_latest_version`.
- Every tool ships a display `title`, MCP behavior annotations
  (`readOnlyHint`/`openWorldHint`/`destructiveHint`/`idempotentHint`) and a typed
  `outputSchema`; results carry both JSON text and matching `structuredContent`.
- `fetch_library` accepts `group:artifact`, `group:artifact:latest` or a full coordinate
  and resolves the latest stable release from `maven-metadata.xml`.
- **MCP resources**: every cached library index readable at
  `kotlinlib://{group}/{artifact}/{version}/index`.
- **MCP prompt**: `explain_public_api(coordinate, package?)`.
- **Transports**: stdio (default) and Streamable HTTP (`--transport http --port <n>`).
- Source analysis with the Kotlin **Analysis API** (standalone K2/FIR), with graceful
  best-effort PSI fallback when type resolution fails.
- KMP-aware source resolution: per-target sources jars resolved via `.module` Gradle
  metadata with filename heuristics as fallback.
- On-disk cache keyed by `group/artifact/version` under the OS cache directory.
- Optional **Compose Desktop dashboard** embedding the server (control, logs, cache browser).

[Unreleased]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/aoreshkov/kotlin-lib-mcp/releases/tag/v0.1.0
