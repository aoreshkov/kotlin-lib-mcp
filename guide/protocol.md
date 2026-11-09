# Protocol features

## Tool metadata

Every tool ships the metadata the MCP spec encourages clients to use: a display `title`,
**behavior annotations** (`readOnlyHint: true` everywhere except `fetch_library`, which is
additive-only — `destructiveHint: false`, `idempotentHint: true`; tools that reach Maven
repositories set `openWorldHint: true`, cache-only tools `false`), a typed **`outputSchema`**
derived from the response DTO's serializer, and an **icon**. Results carry both compact JSON text
and the matching `structuredContent` object, so structured-output clients and plain-text clients
see the same payload.

## Progress and logs

`fetch_library` also reports **progress notifications** (download → analyze → cache) when the
client sends a `progressToken`. Logs go to **stderr** by default (which the spec blesses for all
stdio logging); the deprecated MCP **logging capability** — mirroring logs to clients as
`notifications/message` (respecting `logging/setLevel`) — is **opt-in** via `--forward-logs-to-client`,
for stdio clients that surface MCP log messages but drop stderr. It is refused with `--transport http`,
where every session would receive every other session's logs.

## Elicitation

When `fetch_library` is called without a version (`io.ktor:ktor-client-core`, or `…:latest`) it has
to guess. If the client advertised the **`elicitation`** capability, it asks instead: an
`elicitation/create` **form-mode** request carrying a single-select version picker — the titled
`oneOf` shape from SEP-1330, with the latest stable release pre-selected as the schema `default`.

| The user | The server |
|---|---|
| **accepts** a version | fetches exactly that one |
| **declines** | fetches the latest stable release, as it always did |
| **cancels** (dismissed the dialog) | downloads nothing and returns a tool error saying to call `fetch_library` again with an explicit `group:artifact:version` |

There is no flag: capability negotiation *is* the opt-in. A client that advertises nothing — or
advertises **url-mode only**, which servers must not answer with a form — keeps the previous silent
latest-stable behavior exactly. Only public Maven version numbers are ever requested, so form mode
is appropriate; URL mode exists for credentials and third-party authorization, and is deliberately
unused here. Accepted values are validated against the offered list before they reach a repository
URL, and a client that errors mid-question falls back to the default rather than failing the fetch.

Under `--tasks`, a task-augmented `fetch_library` parks in the **`input_required`** status while the
question is outstanding and returns to `working` once answered; the `elicitation/create` carries the
`io.modelcontextprotocol/related-task` `_meta` tying it to the task.

## Tasks

Pass **`--tasks`** to accept task-augmented `tools/call` for `fetch_library` (SEP-1686) and answer
`tasks/get` / `tasks/result` / `tasks/list` / `tasks/cancel`. Works on both transports.

Task records are **persisted** under `<cache-dir>/tasks`, so a completed task and its result are
still retrievable after the server restarts. A task that was still running when the server stopped
comes back as `failed` — its work did not survive, only the record did. Records are dropped once
their TTL elapses (10 minutes by default, 1 hour maximum). Servers sharing a cache directory, such
as one per Claude Code session, keep their tasks apart: a server picks up only the records of a
server that has stopped, never those of one still running.

> [!WARNING]
> **Task IDs are bearer tokens for tasks that outlive their session.** A task belongs to the MCP
> session that created it, and while that session is connected no other session can read, list or
> cancel it. But a session ID is per-connection: after a restart your client reconnects with a new
> one, so a recovered task is instead reachable by **anyone presenting its exact task ID**. That is
> the model the MCP spec prescribes for servers with no authorization context — which this one is,
> being loopback-first with no auth — and task IDs are 122-bit `SecureRandom` UUIDs accordingly.
> `tasks/list` never returns recovered tasks, only the calling session's own. If you expose this
> server beyond loopback, put authentication in front of it.

## Concurrency

A server-initiated request from inside a tool call only works because the
SDK dispatches inbound requests concurrently once the session is initialized — otherwise the
client's reply would be stuck behind the very handler waiting for it. Besides making elicitation
possible, this means `ping`, `tasks/get` and `notifications/cancelled` are answered promptly
during a long `fetch_library` instead of queueing behind it, and a `fetch_library` the client
cancels actually stops.

## Resources

Each cached library is readable at
`kotlinlib://{group}/{artifact}/{version}/index` — a summary as JSON: KMP targets, declaration, file
and package counts, and its packages (up to 200). Each package's public API (signature and KDoc
summary per declaration, up to 200) is readable at
`kotlinlib://{group}/{artifact}/{version}/package/{package}`; the tools page through anything past
those caps. The resource list updates as libraries are fetched, both URI shapes are published as
**resource templates** so any cached coordinate is directly addressable, and template variables —
including `package` — autocomplete from the cache. Tools point at them too: `fetch_library` returns
a `resource_link` to the library's index, and `list_packages` links the first 20 packages of each page,
so a client can attach them without another lookup.

## Prompt

`explain_public_api(coordinate, package?)`
renders an explanation request grounded in the cached signatures and KDoc.

## Icons

The server, every tool, the prompt and the library-index resource/template each declare
an [SEP-973][sep-973] icon, so a client can show the surface visually instead of as a wall of
snake\_case. They are inlined as **`data:` URIs** rather than hosted URLs — a stdio server has no
origin, and the spec asks consumers to prefer same-origin icons and fetch them without credentials,
so inlining removes the third-party fetch entirely and keeps the icons working offline and inside
the container image. The payload is **PNG**, the one format icon-rendering clients *must* support
(`image/svg+xml` is only a SHOULD, and the spec warns it may carry executable content). The glyphs
are drawn by [`tools/src/main/kotlin/GenerateIcons.kt`](../tools/src/main/kotlin/GenerateIcons.kt)
(`./gradlew :tools:generateIcons`) and kept small —
about 800 bytes encoded each, since they ride in every `tools/list`. The same geometry also
writes the vector brand mark, `assets/icon.svg`, and a test fails if any committed icon stops
matching it.

[sep-973]: https://modelcontextprotocol.io/specification/2025-11-25/basic#icons
