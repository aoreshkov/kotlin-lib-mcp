---
paths:
  - "plugin/**"
  - ".claude-plugin/**"
  - ".github/launcher-test/**"
---

# The Claude Code plugin

Not a Gradle module: **`plugin/`** — the Claude Code plugin packaging (`.mcp.json` running
`launcher/KotlinLibMcpLauncher.java` on the user's JDK 25+, plus four skills), listed by the
repo-root `.claude-plugin/marketplace.json` so `/plugin marketplace add aoreshkov/kotlin-lib-mcp`
works. The launcher downloads the release zip into `${CLAUDE_PLUGIN_DATA}`, checks it against
GitHub's asset digest and runs it in-process, taking the jar order from the zip's `bin/server`
(the kotlin-compiler hoist — get it wrong and the server dies with `NoSuchMethodError`). It must
compile with `javac --release 21` (CI checks) so an old JDK can still say what's wrong. Its
security branches (zip-slip, digest matching, checksum) and error reporting are covered by
`.github/launcher-test/`, an offline harness CI runs beside the compile. Docker is
deliberately *not* the plugin's runtime: it stays the route for `claude mcp add`, the VS Code
buttons and the MCP registry. Validate with `claude plugin validate ./plugin --strict`; bump the
plugin's own `version` whenever `plugin/` changes (CI enforces it).
