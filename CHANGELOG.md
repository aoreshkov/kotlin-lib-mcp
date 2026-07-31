# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Task records survive a server restart.** They are written to `<cache-dir>/tasks/<taskId>.json`
  (one file each, temp-file-then-move so a crash cannot leave a half-written record), so a completed
  task and its result are still retrievable afterwards. A task that was still running when the
  server stopped is restored as `failed` — its work did not survive, only the record — which also
  keeps a zombie `working` record from blocking `tasks/result` until its TTL expires.

  A task's owner is a per-connection session id, so after a restart no live caller can match a
  restored record and strict binding would leave every persisted task unreachable. Restored records
  are therefore reachable by **exact task id** from any caller, while `tasks/list` still returns only
  the calling session's own tasks. This is the model the 2025-11-25 spec prescribes for a receiver
  with no authorization context — as here, loopback-first with no auth — and task ids are 122-bit
  `SecureRandom` UUIDs accordingly. The limitation is documented in the README, as the spec asks.

  This is restart survival on a single node; multi-replica would additionally need shared storage
  and a real authorization context.
- **`--tasks` now works on the HTTP transport**, not just stdio. It was restricted because
  `mcpStreamableHttp` creates a session per connection inside the SDK and hands back no
  `ServerSession` to register the `tasks/…` handlers on. `installTaskHandlersOnEverySession` hooks
  `Server.onConnect` and sweeps `server.sessions`, configuring any it has not seen — sweeping rather
  than taking `sessions.values.last()`, since that callback says nothing about *which* session
  connected and two concurrent connections can interleave. Task records are owned by the session
  that created them, so each HTTP connection sees only its own. The stdio-only warning `--tasks`
  printed under `--transport http` is gone.
- **Icons (SEP-973)** on every MCP object the server exposes: `serverInfo`, all ten tools, the
  `explain_public_api` prompt, and the library-index resource and resource template. `serverInfo`
  also gained the `title` and `websiteUrl` branding fields it already declares in `server.json`.
  Icons are inlined as `data:` PNG URIs — PNG because it is the one format icon-rendering clients
  MUST support, inline because a stdio server has no origin for the spec's same-origin guidance and
  inlining needs no fetch, works offline, and ships in the container image. The glyphs are drawn by
  `assets/icons/GenerateIcons.java` and kept to ~800 bytes encoded, since they ride in every
  `tools/list`.

### Changed
- **MCP Kotlin SDK 0.14.0 → 0.15.0.** The wire types are unchanged; the substance is that
  `Protocol` now dispatches inbound handlers concurrently itself (bounded, order-preserving, gated
  on `notifications/initialized`) and tracks in-flight requests so `notifications/cancelled`
  genuinely cancels the running handler. Consequences:
  - `transport/ConcurrentDispatchTransport.kt`, which existed only to stop a nested
    `elicitation/create` deadlocking the single-threaded stdio pipeline, was **deleted**. The
    behaviour it provided is now the SDK's, and is exercised by `ConcurrentDispatchTest`.
  - **A long `fetch_library` can now be cancelled by the client** and the download actually stops,
    rather than running on unobserved.
  - Concurrent dispatch was previously stdio-only; it is now a `Protocol` property, so elicitation
    from inside a tool works over Streamable HTTP too.
- `fetch_library` progress notifications now carry `relatedRequestId`, so Streamable HTTP routes
  them onto the originating `tools/call`'s SSE stream instead of the standalone one.
- The HTTP transport now sends an **SSE heartbeat** every 30s. The SDK's default is none, and a
  `fetch_library` that downloads and parses a large library can idle past a proxy's timeout.

- **Logging capability retired to opt-in** (MCP 2025-11-25 blesses stderr for all stdio
  logging): the server no longer advertises the deprecated `logging` capability by default,
  and log-forwarding to clients (`notifications/message`) is now gated behind the new
  `--forward-logs-to-client` flag. Stderr (via `logback.xml`) is the primary channel; the
  forwarder remains available for stdio clients that surface MCP log messages but drop stderr.
- The advertised MCP server version is now **build-derived from `gradle.properties`**
  (baked into a classpath resource read by `ServerVersion`) instead of a hand-maintained
  `SERVER_VERSION` constant, so it can never drift from the release version. The release
  tag guard drops its third-file check accordingly.

### Fixed
- **Three deviations from the 2025-11-25 tasks spec**, all found while designing task persistence:
  - A `tools/call` whose result carried `isError: true` completed the task as `completed`; the spec
    says such a task `failed` ("for tool calls specifically, this includes cases where the tool call
    result has `isError` set to true"). The result is still returned by `tasks/result`. This settles
    the SEP-1303-vs-spec question left open when tasks first shipped.
  - `tasks/cancel` on an already-terminal task returned it unchanged; the spec requires `-32602`.
    Answering success told a client that raced the completion its task was cancelled when the result
    had in fact been delivered.
  - `tasks/result` on a `working` or `input_required` task returned `-32602`; the spec requires it to
    **block until terminal**. This is load-bearing for elicitation: the documented flow is that a
    client seeing `input_required` calls `tasks/result` and receives the server's question there.
- **`completion/complete` could go missing on a session.** Registration picked
  `sessions.values.last()` from `Server.onConnect`, which says nothing about *which* session
  connected; two concurrent connections can interleave so that the newest session is already
  configured while the other never gets the handler and answers `-32601 Method not found` for its
  whole lifetime. The sweep introduced for tasks is now shared as `onEachSession`
  (`SessionSetup.kt`) and used by both.
- **Tasks are now scoped to the session that created them.** `TaskStore` held a flat registry and
  `tasks/list` returned every record, so any client could enumerate — and `tasks/get`,
  `tasks/result` and `tasks/cancel` could read, retrieve and kill — another client's tasks and
  their full tool results. Unreachable while `--tasks` was stdio-only (one session per process),
  and a prerequisite for enabling it on the HTTP transport above, where `mcpStreamableHttp` creates
  a session per connection. A `taskId` owned by another session now
  raises the same `UnknownTaskException` as one that never existed, so the error cannot be used to
  probe for other sessions' ids.
- **`kotlinx.collections.immutable` classpath shadowing.** `kotlin-compiler` (pulled in by `:core`
  for the Analysis API) bundles 127 classes of an unrelocated pre-0.5 copy, which wins on the
  runtime classpath. kotlin-sdk 0.15.0 rewrote `Protocol` and `FeatureRegistry` around the newer
  `PersistentMap.putting`/`removing`, so even `Server.addTool` threw `NoSuchMethodError` at startup.
  `server/build.gradle.kts` now hoists the genuine jar ahead of `kotlin-compiler` on the `test`,
  `run` and `startScripts` classpaths, alongside the OpenTelemetry jars that already needed it.

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

[Unreleased]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/aoreshkov/kotlin-lib-mcp/releases/tag/v0.1.0
