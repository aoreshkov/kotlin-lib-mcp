---
paths:
  - "server/src/**/Cli*.kt"
  - "server/src/**/Main.kt"
  - "server.json"
---

# The command line

**CLI (`Cli.kt`, cli-kit):** every option is declared once in `Cli`, and `CliTest` pins the
`--help` page as a golden file (`./gradlew :server:test -Dgolden.update=true`, then read the diff).
The handler (`serve` in `Main.kt`) **never writes through `CommandContext.output` or `io.out*`** —
stdout belongs to the transport; `CliTest.interruptReturnsOnlyOnceTheServerHasClosed` fails if it
does. Its interrupt action must keep waiting for the latch counted down *after* `close()`, not for
the serving job: the JVM halts the moment the action returns. `server.json`'s `packageArguments`
are published command-line surface too: registry clients build the container's arguments from them,
replacing the Dockerfile's `CMD`, so stdio must stay the parser's default. `RegistryManifestTest`
runs them through the parser. Changing a default the manifest advertises is a breaking change for
installed clients, and so is renaming an option outright: keep the old spelling in the option's
`formerNames`, which the parser still accepts with a warning, and retire an option with
`deprecated = "…"` rather than deleting it. Both warnings go to stderr, so they are safe on stdio,
but they print on every launch that uses the option — so don't deprecate one the server still needs
(`--forward-logs-to-client` and `--tasks` wait on the SDK's 2026-07-28 work; see the `mcp-sdk-bump` skill). Check edits
with `mcp-publisher validate server.json`.

**No option declares `env` or `config`.** cli-kit counts a value from an environment variable or a
configuration file as *given*, so `Constraint.RequiresValue` refuses it like a typed one: a `port`
key in someone's config file would turn every stdio launch into an error, and a `transport` key would
override the stdio default the registry relies on. A launch is configured in one place, the client's
(`server.json`, the plugin's `.mcp.json`) — and the `--rm` container could not see a host config file
anyway.
