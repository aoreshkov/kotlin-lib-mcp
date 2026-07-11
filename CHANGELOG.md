# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Supply-chain hardening: all GitHub Actions pinned to full commit SHAs, `mcp-publisher`
  pinned to v1.7.9 with SHA-256 verification, Docker base image digest-pinned (with a
  Dependabot `docker` ecosystem to keep it fresh), Gradle wrapper distribution checksum
  added, and JetBrains repositories content-filtered to `org.jetbrains.*`.

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

[Unreleased]: https://github.com/aoreshkov/kotlin-lib-mcp/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aoreshkov/kotlin-lib-mcp/releases/tag/v0.1.0
