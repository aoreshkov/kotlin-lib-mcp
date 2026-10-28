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

The tools are live in the next session, or right after `/reload-plugins`. Verify with `/mcp`: the
server appears as `plugin:kotlin-lib:kotlin-lib`.

## Requirements

- **A JDK 25+**, on your `PATH` (check with `javac -version`) or anywhere else, named by the `java`
  option below — an IDE-downloaded JDK under `~/.jdks` works. The launcher runs in Java's
  single-file source mode, which a bare JRE cannot compile.
- **No JDK 25, or you'd rather use Docker?** Skip the plugin and register the
  [Docker image](https://github.com/aoreshkov/kotlin-lib-mcp#quick-start-no-build-required) with
  `claude mcp add` instead. You lose the skills; the tools are the same.

No account, no credentials.

## Configuration

Set these in `/plugin` → **Kotlin & Java Library Sources** → configure; they are stored in your user
`settings.json` under `pluginConfigs`, so a plugin update keeps them.

| Option | Default | What it does |
|---|---|---|
| `java` | `java` | The `java` executable that runs the launcher and the server — a JDK 25+. The default is the one on `PATH`. To use a JDK that isn't on `PATH`, or a newer one than `PATH` has, give the full path to `<JDK home>/bin/java` (`C:\Program Files\Java\jdk-25\bin\java.exe`, `/usr/lib/jvm/jdk-25/bin/java`), not the JDK home itself. |
| `server_version` | `latest` | Which released server build to run. Set a version such as `0.9.0` to pin. |

## How the server gets here

A plugin is distributed as a git checkout, so it cannot carry the server's jars. `.mcp.json` starts
`launcher/KotlinLibMcpLauncher.java`, which on first run:

1. resolves `latest` to the newest GitHub release (remembered for 24 hours, so a session start is
   normally not a network call) or uses the version you pinned;
2. downloads `kotlin-lib-mcp-server-<version>.zip` from that release and checks it against the
   SHA-256 digest GitHub recorded for the asset, **refusing to install anything that doesn't
   match**;
3. unpacks it into the plugin's persistent data directory, moving it into place only once complete;
4. loads it into the launcher's own JVM, in the classpath order of the distribution's own start
   script, so the stdio pipes Claude Code opened are the pipes the MCP protocol speaks over — one
   process, nothing left orphaned when a session ends.

Later sessions find the unpacked distribution and skip straight to step 4. With `latest`, a session
that cannot reach GitHub, or cannot install a newer release it found there, runs the newest version
already installed and says so on stderr; the next session tries again. A pinned version has no such
fallback: if it cannot be installed, the server fails to start with the reason. Everything the
launcher prints goes to stderr; stdout is the protocol channel.

Each release is a full server, so once `latest` installs a new one the launcher removes the older
installs, keeping one: the newest previous version, as the offline fallback and for any session
still running it. A version that a running server still has open on Windows is left for a later
install to remove.

Both the archive and the plugin come from the same repository over HTTPS, so the checksum protects
against a corrupted or truncated download rather than against a compromised GitHub account. Release
artifacts also carry [build provenance](https://github.com/aoreshkov/kotlin-lib-mcp/attestations),
verifiable with `gh attestation verify <zip> --repo aoreshkov/kotlin-lib-mcp`.

### Escape hatches

| Variable | Effect |
|---|---|
| `KOTLIN_LIB_MCP_SERVER_HOME` | Path to an existing server distribution (a `gradlew :server:installDist` tree or an unpacked release zip). Nothing is downloaded. |
| `KOTLIN_LIB_MCP_SERVER_VERSION` | Same as the `server_version` option; the plugin sets it from your configuration. |

To wipe the downloaded distributions, delete the plugin's data directory
(`~/.claude/plugins/data/kotlin-lib-kotlin-lib-mcp/`); the next session downloads again. The
library cache is separate and shared with every other way of running the server.

## What you get

| Component | What it does |
|---|---|
| `kotlin-lib` MCP server | 12 tools over the published sources jar, parsed with the Kotlin Analysis API (K2/FIR standalone). Cached on disk, offline after the first fetch |
| `library-ground-truth` skill | Model-invoked. Makes Claude pin the coordinate from your build files and look the API up instead of guessing |
| `/kotlin-lib:api` | Public-API summary of a library or one of its packages, with real signatures and KDoc |
| `/kotlin-lib:migrate` | Diffs a library's public API across two versions and finds what actually breaks in *your* codebase |
| `/kotlin-lib:setup` | Verifies the server, or repairs it when it does not start |

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
- Privacy: read-only. The launcher downloads the server from this repository's GitHub releases; the
  server reaches public Maven repositories only; no telemetry unless you start the server with
  `--otel`. Full statement in the repository README.
- Apache-2.0.
