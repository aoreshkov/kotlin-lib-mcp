# Contributing to kotlin-lib-mcp

Thanks for your interest! Bug reports, feature requests and pull requests are all welcome.
For significant changes, please open an issue first so we can discuss the direction before
you invest time.

## Prerequisites

- **JDK 25** (a Gradle toolchain: an installed JDK 25 is detected, otherwise Gradle downloads
  one).
- No other setup: `./gradlew` downloads everything else.

## Build & test

```sh
./gradlew build                                    # compiles all modules, runs tests and API checks
./gradlew test                                     # unit tests only
./gradlew :server:run --args="--transport stdio"   # local MCP over stdio (default)
./gradlew :server:run --args="--transport http --port 3000"   # Streamable HTTP at /mcp
./gradlew :dashboard:run                           # Compose Desktop UI
./gradlew :server:installDist                      # standalone launcher in server/build/install/server/bin
```

| Module | What it is |
|---|---|
| `core/` | KMP library: domain model + ports (`commonMain`); Maven fetcher, zip extractor, Analysis API analyzer, on-disk cache (`jvmMain`) |
| `server/` | JVM app: MCP tools/resources/prompts + stdio and Streamable HTTP transports |
| `dashboard/` | Compose Desktop control panel embedding the server (optional) |
| `tools/` | Generator for the SEP-973 icon PNGs the server advertises and the brand SVG. The module never ships; its output does |

CI also runs the official [MCP conformance suite](https://github.com/modelcontextprotocol/conformance)
against the server over Streamable HTTP. To run it the same way locally (needs Node):

```sh
./gradlew :server:installDist
npm ci --prefix .github/conformance --ignore-scripts
server/build/install/server/bin/server --transport http --port 3000 &
.github/conformance/node_modules/.bin/conformance server --url http://127.0.0.1:3000/mcp \
  --expected-failures .github/conformance/baseline.yml
```

A failing scenario that is not in `.github/conformance/baseline.yml` is a real conformance bug; the
file explains what does belong there.

## Project conventions

- **Dependency versions live only in `gradle/libs.versions.toml`.** Never hard-code a version
  in a build script.
- **Kotlin and the Analysis API artifacts are version-locked** — they must share the exact
  same version. Always bump `kotlin` and the `analysisApi-*` entries together.
- **Public API of `core` is validated** by the Kotlin Gradle plugin's ABI validation. If you
  intentionally change it, run `./gradlew updateKotlinAbi` and commit the updated
  `core/api/core.api`; `./gradlew build` fails otherwise.
- **stdio transport: never write to stdout.** Only MCP protocol frames may go to stdout —
  all logging goes to stderr/file (Kermit → SLF4J → Logback). A stray `println` corrupts
  the protocol stream.
- Keep the Analysis API isolated behind the `SourceAnalyzer` interface and degrade
  gracefully (best-effort PSI signatures) when type resolution fails.
- When adding an MCP tool, provide all four metadata pieces: `title`, behavior annotations
  (reuse the constants in `ToolSupport.kt`), an `outputSchema` (see `OutputSchemas.kt`), and an
  icon (a `Glyph` in `Icons.kt` and in `GenerateIcons.kt`, then `./gradlew :tools:generateIcons`).
- **Changes under `plugin/` need a version bump** in `plugin/.claude-plugin/plugin.json`, or
  installed plugins never receive them. CI enforces this; its `README.md` and `evals/` are exempt.

## Pull requests

1. Fork, create a topic branch from `main`.
2. Add tests for new behavior; make sure `./gradlew build` passes locally.
3. Use a clear title in imperative mood (Conventional Commits style — `feat:`, `fix:`,
   `docs:`, … — is appreciated but not enforced).
4. Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.

CI runs the full build on Linux and Windows; both must pass before merge.

## Reporting issues

Use the issue forms. For security vulnerabilities, **do not open a public issue** — see
[SECURITY.md](SECURITY.md).
