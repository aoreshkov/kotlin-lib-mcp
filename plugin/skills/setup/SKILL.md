---
description: Set up or repair the kotlin-lib MCP server bundled with this plugin. Use right after installing the plugin, when its tools are missing from the toolset, when the server shows as failed in /plugin, or when the machine has no Docker.
---

# Set up kotlin-lib

The plugin ships one MCP server, started for you when the plugin is enabled:

```json
{ "command": "docker",
  "args": ["run", "-i", "--rm", "-v", "kotlin-lib-mcp-cache:/home/mcp/.cache",
           "ghcr.io/aoreshkov/kotlin-lib-mcp:0.4"] }
```

**Requirement: Docker must be installed and running.** Nothing else — no JDK, no Gradle, no
credentials, no account. The named volume `kotlin-lib-mcp-cache` keeps downloaded sources and the
parsed index across runs; without it every session re-downloads and re-analyzes, since the
container is `--rm`.

## Verify

1. Check the tools are present — the toolset should include `fetch_library`, `list_packages`,
   `list_declarations`, `get_api_signature`, `get_kdoc`, `get_source`, `search_source`,
   `get_dependencies`, `list_versions`, `get_latest_version`.
2. Smoke-test with a small library: `fetch_library` on `org.jetbrains:annotations:26.0.2`, then
   `list_packages` on the same coordinate. Both succeeding means the pipeline works end to end.
3. Report the result plainly. Do not proceed to a large library if the smoke test failed.

## When it fails

| Symptom | Cause | Fix |
|---|---|---|
| Server never starts; `/plugin` Errors shows the docker command | Docker not installed or daemon not running | Start Docker Desktop / the daemon, then `/reload-plugins` |
| `manifest unknown` / pull error | Registry unreachable, or an air-gapped machine | `docker pull ghcr.io/aoreshkov/kotlin-lib-mcp:0.4` by hand and read the error |
| Tools present, every `fetch_library` fails | No network route to Maven Central from the container, or a corporate proxy | Test with `docker run --rm ghcr.io/aoreshkov/kotlin-lib-mcp:0.4 --help`; pass a mirror with `--repo <url>` |
| First fetch is very slow | Expected — download plus full source analysis | It is cached afterwards, keyed by `group/artifact/version` |
| Fetch works, later calls say the library is not fetched | The cache volume is missing from the args | Confirm `-v kotlin-lib-mcp-cache:/home/mcp/.cache` is in the command |

## No Docker on this machine

Use the standalone release instead — it needs only a Java 21+ runtime:

1. Download the latest zip from https://github.com/aoreshkov/kotlin-lib-mcp/releases/latest and
   unzip it.
2. Register it as a normal user-scoped server:
   `claude mcp add kotlin-lib -- /path/to/kotlin-lib-mcp-server-<version>/bin/server --transport stdio`
   (on Windows, `bin\server.bat`).
3. The plugin's own Docker server will keep reporting a start failure. Either live with the entry
   in the `/plugin` Errors tab, or disable the plugin and keep the manually registered server —
   the skills are the only thing you lose, and everything in them works against any registration
   of the same server.

Do not edit the plugin's `.mcp.json` in place: a plugin update overwrites it.

## What it touches

Read-only, outbound to public Maven repositories only (Maven Central by default). It downloads
sources jars and metadata for coordinates it is asked about, and writes them to the cache volume.
No credentials, no telemetry — tracing exists but is off unless the server is started with
`--otel`. See the repository's Privacy section for the full statement.
