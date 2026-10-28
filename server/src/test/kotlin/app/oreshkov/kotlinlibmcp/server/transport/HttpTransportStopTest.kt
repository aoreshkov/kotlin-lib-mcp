package app.oreshkov.kotlinlibmcp.server.transport

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `runHttpServer` stops its engine however early it is cancelled. The window that matters opens
 * when the socket binds and closes when Ktor's `startSuspend` returns: a cancel landing inside it
 * makes `startSuspend` throw although the engine is up, and an engine left running past a SIGTERM
 * outlives `close()`. Polling without a pause and cancelling on the first accepted connection lands
 * there nearly every time; a few rounds make a miss on every one unlikely.
 */
class HttpTransportStopTest {

    @Test
    fun cancellingAsSoonAsThePortListensStopsTheEngine() = repeat(ROUNDS) { round ->
        val port = ServerSocket(0).use { it.localPort }
        runBlocking {
            val serving = launch(Dispatchers.IO) { runHttpServer(server(), port) }
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!listening(port)) {
                if (System.nanoTime() > deadline) fail("round $round: the server never listened on $port")
            }
            serving.cancelAndJoin()
        }
        assertFalse(listening(port), "round $round: the engine was still listening after runHttpServer returned")
    }

    /**
     * CIO reports a busy port as a cancellation. Unless `runHttpServer` hands back the bind error,
     * `serve` mistakes it for Ctrl-C: exit status 130, and nothing logged.
     */
    @Test
    fun aBusyPortFailsWithTheBindErrorNotACancellation() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            runBlocking {
                assertFailsWith<BindException> { runHttpServer(server(), taken.localPort) }
            }
        }
    }

    private fun server() = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(capabilities = ServerCapabilities()),
    )

    private fun listening(port: Int): Boolean = try {
        Socket("127.0.0.1", port).close()
        true
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val ROUNDS = 5
    }
}
