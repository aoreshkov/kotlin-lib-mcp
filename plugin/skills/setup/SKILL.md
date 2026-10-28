---
description: Set up or repair the kotlin-lib MCP server bundled with this plugin. Use right after installing the plugin, when its tools are missing from the toolset, when the server shows as failed in /plugin or /mcp, or when the machine has no JDK 25.
---

# Set up kotlin-lib

The plugin ships one MCP server, started for you when the plugin is enabled. It runs a small
launcher on the user's JDK:

```
<java> -Xlog:disable -Xlog:all=warning:stderr --enable-native-access=ALL-UNNAMED <plugin>/launcher/KotlinLibMcpLauncher.java
```

The launcher downloads the server release named by the `server_version` option into the plugin's
data directory once, verifies its SHA-256, and runs it in the same JVM. This installation is
configured as:

- **Java executable** (`java` option): `${user_config.java}` — `java` means the one on the `PATH`
  Claude Code was started with; anything else is a full path to `<JDK home>/bin/java`.
- **Server version** (`server_version` option): `${user_config.server_version}`.
- **Data directory** (downloaded servers): `${CLAUDE_PLUGIN_DATA}`.

**Requirement: a JDK 25 or later** — not a JRE, because the launcher runs in single-file source
mode, which compiles it. Nothing else: no Docker, no Gradle, no credentials, no account. Use the
configured Java executable, quoted if its path has spaces, for every `java` / `javac` check below.

## Verify

1. Check the tools are present — the toolset should include `fetch_library`, `list_packages`,
   `list_declarations`, `get_api_signature`, `get_kdoc`, `get_source`, `search_source`,
   `diff_versions`, `diff_api`, `get_dependencies`, `list_versions`, `get_latest_version`.
2. Smoke-test with a small library: `fetch_library` on `org.jetbrains:annotations:26.0.2`, then
   `list_packages` on the same coordinate. Both succeeding means the pipeline works end to end.
3. Report the result plainly. Do not proceed to a large library if the smoke test failed.

## When it fails

`/mcp` shows whether the server connected. The launcher writes its reason to stderr, which lands in
the debug log: start Claude Code with `claude --debug`, then read `~/.claude/debug/<session-id>.txt`.
A plugin server that failed less than 15 minutes earlier is not started again on its own — after a
fix, choose **Reconnect** on it in `/mcp`.

| Symptom | Cause | Fix |
|---|---|---|
| The command was not found (`ENOENT`) | No `java` on the `PATH` Claude Code was started with — typical when it was launched from the Dock or Start menu rather than a shell — or the configured path is wrong | Set **Java executable** in `/plugin` → configure to the full path of a JDK 25+ `<JDK home>/bin/java` (`C:\Program Files\Java\jdk-25\bin\java.exe`, `/usr/lib/jvm/jdk-25/bin/java`); saving reloads the plugin |
| `needs Java 25 or later, but the plugin is running on Java 21` | The configured `java` is older than the server | Point **Java executable** at a JDK 25+, or pin **Server version** to `0.7.0`, the last release that runs on Java 21 |
| `java` fails before the launcher prints anything, with an error about compiling the source file | The configured `java` belongs to a JRE, which has no compiler | Point **Java executable** at a JDK's `java` — check with `javac -version` from the same `bin` directory |
| `Could not reach the GitHub releases API` | First start with no network route to `api.github.com` / `github.com`, or a proxy | Fix the network, or download the release zip by hand, unzip it, and set the `KOTLIN_LIB_MCP_SERVER_HOME` environment variable to it before starting Claude Code |
| `No release v… of kotlin-lib-mcp` | A pinned version that was never released | Pin a version listed at https://github.com/aoreshkov/kotlin-lib-mcp/releases, or set it back to `latest` |
| Tools present, every `fetch_library` fails | No network route to Maven Central, or a corporate proxy | The server reaches Maven repositories only; pass a mirror with `--repo <url> --no-maven-central` through a manual registration (below) |
| First fetch is very slow | Expected — download plus full source analysis | It is cached afterwards, keyed by `group/artifact/version` |

To force a fresh server download, delete `${CLAUDE_PLUGIN_DATA}/dist` and choose **Reconnect** on
the server in `/mcp`.

## No JDK 25 on this machine

Keep the plugin for its skills, turn its own server off in `/mcp`, and register the same server
another way — the skills work against any registration of it:

- **Docker:**
  `claude mcp add kotlin-lib -- docker run -i --rm -v kotlin-lib-mcp-cache:/home/mcp/.cache ghcr.io/aoreshkov/kotlin-lib-mcp`
- **A Java 25 runtime (JRE is enough):** download the latest zip from
  https://github.com/aoreshkov/kotlin-lib-mcp/releases/latest, unzip it, and run
  `claude mcp add kotlin-lib -- /path/to/kotlin-lib-mcp-server-<version>/bin/server --transport stdio`
  (on Windows, `bin\server.bat`).

Do not edit the plugin's `.mcp.json` in place: a plugin update overwrites it. The `java` and
`server_version` options are the supported way to change what it runs.

## What it touches

Read-only. The launcher downloads the server from the repository's GitHub releases; the server
reaches public Maven repositories only (Maven Central by default), downloading sources jars and
metadata for coordinates it is asked about into its on-disk cache. No credentials, no telemetry —
tracing exists but is off unless the server is started with `--otel`. See the repository's Privacy
section for the full statement.
