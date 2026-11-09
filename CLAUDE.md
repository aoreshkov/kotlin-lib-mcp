# kotlin-lib-mcp

MCP server (Kotlin Multiplatform) that, on request, downloads the **sources** of a
Maven-published Kotlin/Java library (e.g. `io.ktor:ktor-client-core:3.5.1`) and exposes
structured information about it — public API surface, KDoc, dependencies/metadata, and raw
source + search — to MCP clients (Claude Code, Claude Desktop, …). An optional Compose
Desktop dashboard runs the same server in-process for control, logs, and cache browsing.

## Modules

- `core/` — **KMP library**: domain model and interfaces in `commonMain`, JVM implementations
  (Maven fetcher, zip extractor, Analysis API analyzer, on-disk cache) in `jvmMain`. No MCP, no UI.
- `server/` — **JVM app**: registers MCP tools and runs the transports. Runnable headless.
- `dashboard/` — **Compose Desktop** control panel (optional). Embeds `server`, and takes its HTTP
  engine from `createHttpEngine` (`transport/HttpTransport.kt`) so `mcpStreamableHttp` is wired in
  exactly one file. Keep it that way, or the SDK's 0.20.0 Ktor split breaks a file the
  `/mcp-sdk-bump` map misses.
- `tools/` — **asset generator**, never shipped, but its output is. The glyph geometry in
  `GenerateIcons.kt` is product source: edit it, never the PNGs or `assets/icon.svg`, then run
  `./gradlew :tools:generateIcons`. `GenerateIconsTest` (in `./gradlew build`) fails on any mismatch.
- `plugin/` — the Claude Code plugin packaging; not a Gradle module. Bump the plugin's own `version`
  whenever anything but its README or `evals/` changes (CI enforces it).

`server` and `dashboard` both depend on `core`. `server` runs without Compose. User documentation
is the README plus `guide/`.

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
compile automatically when Kotlin sources changed (`.claude/hooks/stop-verify.sh`). Wire-level
changes are also checked by CI's `conformance` job — the official MCP conformance suite, pinned in
`.github/conformance/` with a baseline of fixture-only scenarios (how to run it: `CONTRIBUTING.md`).

## Tech stack (versions live ONLY in `gradle/libs.versions.toml`)

- JDK via a Gradle toolchain (`jdk` in the catalog; CI `setup-java` and the Docker base image match)
- Kotlin · MCP Kotlin SDK (`kotlin-sdk-server`) · Ktor · kotlinx-serialization-json · kotlinx-coroutines
- Source parsing: Kotlin **Analysis API (standalone mode)** — must be version-matched to Kotlin
- Compose Multiplatform (Desktop)
- Logging: Kermit (common) → SLF4J/Logback (JVM sink) · Tests: kotlin-test, coroutines-test, Ktor `MockEngine`

## Conventions & gotchas

- **Versions are centralized** in `gradle/libs.versions.toml`. Kotlin and the Analysis API
  artifacts must share the **exact same version** — always bump them together (use the
  `/analysis-api-bump` skill; `/mcp-sdk-bump` for the MCP SDK; cut releases with `/release`). The
  catalog follows Gradle's naming guidance (1–3 dash-separated segments, camelCase inside a
  segment) and carries **no entry a build script doesn't consume** — in particular `[plugins]` holds
  only the one alias a module applies with `alias(...)`; everything else is applied by id from
  `build-logic`.
- **`core`'s public ABI is locked** by the Kotlin Gradle plugin's built-in `abiValidation`
  (`kmp-library.gradle.kts`; the standalone binary-compatibility-validator it replaces is
  frozen). `./gradlew build` runs `checkKotlinAbi`; intentional API changes need
  `./gradlew updateKotlinAbi` and a committed `core/api/core.api`. The DSL is still
  `@ExperimentalAbiValidation`, so treat a Kotlin bump as able to break it.
- **stdio transport: NEVER write to stdout** except MCP protocol frames. All logging goes to
  stderr or a file, or it corrupts the protocol stream. That includes the JVM's own unified logging,
  which defaults to stdout (an unusable AOT cache prints `[error][aot]` there before `main` runs):
  `applicationDefaultJvmArgs` in `server/build.gradle.kts` routes it to stderr — keep it.
- **Cache first.** Downloads + parsed index are cached on disk keyed by
  `group/artifact/version`; tools read the cache. Call `fetch_library` to warm it.
- **Area-specific rules** load automatically from `.claude/rules/` when you touch matching files.

## MCP surface

- Tools, cached-library **resources** and an "explain the public API" **prompt** are all served;
  README has the tool table.
- `fetch_library` also accepts a version-less `group:artifact` or `group:artifact:latest`, resolved
  from `maven-metadata.xml` with a semantic-version fallback in `core/util/MavenVersions.kt`.
- Logs go to **stderr** by default (`logback.xml`). The deprecated MCP logging capability
  (`Logging.kt`) is opt-in behind `--forward-logs-to-client`, stdio only: on HTTP it would broadcast
  every session's logs to all.
- **Elicitation** has no flag: it is gated on the client advertising the capability.
- **`--tasks`** (SEP-1686, both transports) and **`--otel`** (OTLP/HTTP traces) are opt-in; off means
  inert.

## The 2026-07-28 horizon

We target **2025-11-25**, because that is what the Kotlin SDK declares
(`LATEST_PROTOCOL_VERSION`). **Do not adopt 2026-07-28 ahead of the SDK**: that would mean inventing
wire surface. `/mcp-sdk-bump` has the milestone map. Three decisions are load-bearing for that
migration, so keep them:

- `elicitation/VersionElicitation.kt` stays one file.
- `--tasks` stays opt-in.
- `logging` stays behind `--forward-logs-to-client`, and stderr stays primary.
