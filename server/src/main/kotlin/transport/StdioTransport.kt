package app.oreshkov.kotlinlibmcp.server.transport

import app.oreshkov.kotlinlibmcp.server.tasks.TaskStore
import app.oreshkov.kotlinlibmcp.server.tasks.registerTaskHandlers
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Runs [server] over stdio and suspends until the client disconnects (EOF on stdin). stdout is
 * the protocol channel — nothing else may write to it (logging goes to stderr via `logback.xml`).
 *
 * When [taskStore] is non-null (`--tasks`), task-augmented `tools/call` is installed on the session
 * before it starts serving. This is the transport that can do so: the session object is right here,
 * whereas the HTTP transport delegates session creation to the SDK.
 *
 * The raw SDK transport is used directly: since kotlin-sdk 0.15.0 `Protocol` dispatches inbound
 * requests concurrently once `notifications/initialized` has arrived, so a handler that makes a
 * server-to-client request (an `elicitation/create`) no longer blocks the read loop that has to
 * deliver the answer. Before 0.15.0 that required a local decorator around this transport — see
 * `.claude/rules/mcp-server.md`.
 */
suspend fun runStdioServer(server: Server, taskStore: TaskStore? = null) {
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    val session = server.createSession(transport)
    // After createSession (it installs the SDK's own tools/call handler, which this replaces) but
    // before the first request can be served.
    taskStore?.let { session.registerTaskHandlers(server, it) }
    val closed = CompletableDeferred<Unit>()
    session.onClose { closed.complete(Unit) }
    closed.await()
}
