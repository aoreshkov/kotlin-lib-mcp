package app.oreshkov.kotlinlibmcp.server.transport

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Runs [server] over stdio and suspends until the client disconnects (EOF on stdin). stdout is
 * the protocol channel — nothing else may write to it (logging goes to stderr via `logback.xml`).
 */
suspend fun runStdioServer(server: Server) {
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    val session = server.createSession(transport)
    val closed = CompletableDeferred<Unit>()
    session.onClose { closed.complete(Unit) }
    closed.await()
}
