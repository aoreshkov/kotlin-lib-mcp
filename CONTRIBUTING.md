# Contributing to kotlin-lib-mcp

Thanks for your interest! Bug reports, feature requests and pull requests are all welcome.
For significant changes, please open an issue first so we can discuss the direction before
you invest time.

## Prerequisites

- **JDK 21** (the build uses Gradle toolchains, so any JDK that can run Gradle works —
  the toolchain resolves 21 automatically).
- No other setup: `./gradlew` downloads everything else.

## Build & test

```sh
./gradlew build     # compiles all modules, runs tests and API checks
./gradlew test      # unit tests only
./gradlew :server:run --args="--transport stdio"   # run the MCP server locally
```

## Project conventions

- **Dependency versions live only in `gradle/libs.versions.toml`.** Never hard-code a version
  in a build script.
- **Kotlin and the Analysis API artifacts are version-locked** — they must share the exact
  same version. Always bump `kotlin` and the `analysis-api-*` entries together.
- **Public API of `core` is validated.** If you intentionally change it, run
  `./gradlew apiDump` and commit the updated `core/api/core.api`; `./gradlew build` fails
  otherwise.
- **stdio transport: never write to stdout.** Only MCP protocol frames may go to stdout —
  all logging goes to stderr/file (Kermit → SLF4J → Logback). A stray `println` corrupts
  the protocol stream.
- Keep the Analysis API isolated behind the `SourceAnalyzer` interface and degrade
  gracefully (best-effort PSI signatures) when type resolution fails.
- When adding an MCP tool, provide all three metadata pieces: `title`, behavior annotations
  (reuse the constants in `ToolSupport.kt`), and an `outputSchema` (see `OutputSchemas.kt`).

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
