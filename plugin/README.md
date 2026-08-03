# kotlin-lib — Claude Code plugin

Gives Claude the **real sources** of any Maven-published Kotlin/Java library instead of its
recollection of them: type-resolved signatures, KDoc, dependency tree and source search, for the
exact version your project depends on.

This is the Claude Code packaging of
[kotlin-lib-mcp](https://github.com/aoreshkov/kotlin-lib-mcp) — the MCP server plus the skills
that make Claude reach for it at the right moment.

## Install

```
/plugin marketplace add aoreshkov/kotlin-lib-mcp
/plugin install kotlin-lib@kotlin-lib-mcp
```

**Requires Docker** (running). No JDK, no account, no credentials. On a machine without Docker,
run `/kotlin-lib:setup` — it walks through the standalone release-zip alternative.

## What you get

| Component | What it does |
|---|---|
| `kotlin-lib` MCP server | 10 tools over the published sources jar, parsed with the Kotlin Analysis API (K2/FIR standalone). Cached on disk, offline after the first fetch |
| `library-ground-truth` skill | Model-invoked. Makes Claude pin the coordinate from your build files and look the API up instead of guessing |
| `/kotlin-lib:api` | Public-API summary of a library or one of its packages, with real signatures and KDoc |
| `/kotlin-lib:migrate` | Diffs a library's public API across two versions and finds what actually breaks in *your* codebase |
| `/kotlin-lib:setup` | Verifies the server, or repairs/replaces it when Docker isn't an option |

## Examples

```
/kotlin-lib:api io.ktor:ktor-client-core:3.5.1 io.ktor.client.plugins
/kotlin-lib:migrate io.ktor:ktor-client-core 3.4.0 3.5.1
```

Or just ask — the `library-ground-truth` skill fires on its own:

> Does `HttpClient` expose a way to set a per-request timeout in the version we're on?

## Links

- Repository, full tool documentation and the Compose Desktop dashboard:
  https://github.com/aoreshkov/kotlin-lib-mcp
- Listed on the [GitHub MCP Registry](https://github.com/mcp/aoreshkov/kotlin-lib-mcp) and the
  [official MCP registry](https://registry.modelcontextprotocol.io) as
  `io.github.aoreshkov/kotlin-lib-mcp`
- Privacy: read-only, outbound to public Maven repositories only, no telemetry unless you start
  the server with `--otel`. Full statement in the repository README.
- Apache-2.0.
