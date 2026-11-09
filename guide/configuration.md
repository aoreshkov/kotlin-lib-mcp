# Configuration

## Streamable HTTP

Run `--transport http --port 3000` and point the client at
`http://127.0.0.1:3000/mcp`. Out of the box it is reachable from this machine only: the server
binds to loopback, and DNS-rebinding protection rejects any `Host` (or `Origin`) header other than
`localhost`, `127.0.0.1` or `[::1]`. There is **no built-in authentication**, so to serve other
machines put an authenticating reverse proxy in front, then bind wider with `--host` (e.g.
`0.0.0.0`) and name the public host with `--allowed-host` (plus `--allowed-origin` for browser
clients).

In Docker, loopback is the container's own, which a published port cannot reach. Bind every
interface inside the container and publish the port on the host's loopback instead — the endpoint
is still `http://127.0.0.1:3000/mcp`, and still unreachable from other machines:

```sh
docker run --rm -p 127.0.0.1:3000:3000 -v kotlin-lib-mcp-cache:/home/mcp/.cache \
  ghcr.io/aoreshkov/kotlin-lib-mcp --transport http --host 0.0.0.0 --port 3000
```

## Command-line flags

CLI flags — `server --help` lists them all with their defaults, the `OTEL_*` variables and
examples: `--transport stdio|http`; `--port`, `--host`, `--allowed-host` and `--allowed-origin`,
which apply to the http transport only, so passing one with stdio is a usage error; `--cache-dir`;
`--repo` (repeatable, searched after Maven Central) and `--no-maven-central` (only the `--repo`
repositories, for a mirror that proxies Central); `--max-concurrent-fetches`; and the opt-ins
`--forward-logs-to-client` (stdio only), `--otel` (see [Telemetry](telemetry.md)) and `--tasks` (see
[Tasks](protocol.md#tasks)). `--version` prints the server's version. A command-line mistake exits 2 and an
internal error 70 (`sysexits.h`). SIGTERM or Ctrl-C stops the server cleanly within 5 seconds:
task records are saved and buffered traces flushed before the process ends.

## Cache

Downloads and the parsed index live under the OS cache dir + `kotlin-lib-mcp`
(`%LOCALAPPDATA%\kotlin-lib-mcp` on Windows, `~/Library/Caches/kotlin-lib-mcp` on macOS,
`$XDG_CACHE_HOME/kotlin-lib-mcp` elsewhere), keyed by `group/artifact/version` — browsable and
safe to delete. `--cache-dir` overrides it. Under `--tasks`, task records live in a `tasks/`
subdirectory of the same root.
