package app.oreshkov.kotlinlibmcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [onEachSession] — how anything we register per session reaches sessions the SDK creates for us.
 *
 * `Server.onConnect` says nothing about *which* session connected, so the tempting shortcut is
 * `sessions.values.last()`. These tests pin the property that shortcut breaks: **one callback must
 * configure every session it can see**, not only the newest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSetupTest {

    private fun TestScope.server(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            handlerCoroutineContext = StandardTestDispatcher(testScheduler),
        ),
    )

    @Test
    fun configuresSessionsThatConnectAfterwards() = runTest {
        val server = server()
        val configured = mutableListOf<ServerSession>()
        server.onEachSession { configured += it }

        val first = server.createSession(FakeTransport())
        val second = server.createSession(FakeTransport())

        assertEquals(listOf(first.sessionId, second.sessionId), configured.map { it.sessionId })
        first.close()
        second.close()
    }

    @Test
    fun configuresEverySessionNotJustTheNewest() = runTest {
        val server = server()
        // Both exist before the hook is installed, so a single sweep has to reach both. This is the
        // case that separates sweeping from `sessions.values.last()` — and the same requirement is
        // what protects the reachable race, where two concurrent createSession calls interleave so
        // that "the last session" is already configured while the other has nothing.
        val older = server.createSession(FakeTransport())
        val newer = server.createSession(FakeTransport())

        val configured = mutableListOf<ServerSession>()
        server.onEachSession { configured += it }

        assertEquals(
            setOf(older.sessionId, newer.sessionId),
            configured.map { it.sessionId }.toSet(),
            "the older session was skipped — only the newest one was configured",
        )
        older.close()
        newer.close()
    }

    @Test
    fun configuresEachSessionExactlyOnce() = runTest {
        val server = server()
        val configured = mutableListOf<ServerSession>()
        server.onEachSession { configured += it }

        // Every connect re-sweeps, so without the claim the earlier sessions would be reconfigured
        // once per subsequent connection — replacing live handlers for no reason.
        val sessions = List(3) { server.createSession(FakeTransport()) }

        assertEquals(3, configured.size)
        assertEquals(sessions.map { it.sessionId }.toSet(), configured.map { it.sessionId }.toSet())
        sessions.forEach { it.close() }
    }

    @Test
    fun aSessionThatClosesDoesNotBlockLaterOnesFromBeingConfigured() = runTest {
        val server = server()
        val configured = mutableListOf<ServerSession>()
        server.onEachSession { configured += it }

        val first = server.createSession(FakeTransport())
        first.close()
        val second = server.createSession(FakeTransport())

        assertEquals(listOf(first.sessionId, second.sessionId), configured.map { it.sessionId })
        second.close()
    }
}
