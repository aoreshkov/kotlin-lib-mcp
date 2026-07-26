package app.oreshkov.kotlinlibmcp.server.transport

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/**
 * The decorator exists for one reason: the SDK's stdio pipeline handles frames strictly one at a
 * time, so a server-to-client request made *inside* a request handler can never be answered.
 * These tests pin the exact property that fixes it — a response is delivered while a request
 * handler is still suspended — and the ordering guarantees kept around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentDispatchTransportTest {

    /** Stands in for `StdioServerTransport`: lets a test push frames the way the read loop would. */
    private class FakeInner : Transport {
        var handler: suspend (JSONRPCMessage) -> Unit = {}
        var started = false
        var closed = false
        val sent = mutableListOf<JSONRPCMessage>()

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) { handler = block }
        override suspend fun start() { started = true }
        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) { sent += message }
        override suspend fun close() { closed = true }
        override fun onClose(block: () -> Unit) = Unit
        override fun onError(block: (Throwable) -> Unit) = Unit

        /** Feeds a frame exactly as the SDK's processor pump does: awaiting the handler. */
        suspend fun deliver(message: JSONRPCMessage) = handler(message)
    }

    private fun request(id: Int, method: String) =
        JSONRPCRequest(id = RequestId(id.toLong()), method = method, params = null)

    private fun scope(t: kotlinx.coroutines.test.TestCoroutineScheduler) =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(t))

    @Test
    fun aResponseIsDeliveredWhileARequestHandlerIsStillWaiting() = runTest {
        val inner = FakeInner()
        val transport = ConcurrentDispatchTransport(inner, scope(testScheduler))
        val answer = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()

        transport.onMessage { message ->
            when (message) {
                is JSONRPCRequest -> {
                    seen += "request-start"
                    answer.await() // the tool asking the client something and waiting
                    seen += "request-done"
                }
                is JSONRPCResponse -> {
                    seen += "response"
                    answer.complete(Unit)
                }
                else -> Unit
            }
        }
        transport.start()

        // The pump delivers the tools/call and moves on -- this must NOT block.
        inner.deliver(request(1, Method.Defined.ToolsCall.value))
        advanceUntilIdle()
        assertEquals(listOf("request-start"), seen)

        // ...so the client's answer can still be read and routed. Without the decorator this frame
        // would never be reached and the handler above would hang forever.
        inner.deliver(JSONRPCResponse(id = RequestId(99L)))
        advanceUntilIdle()

        assertEquals(listOf("request-start", "response", "request-done"), seen)
    }

    @Test
    fun initializeIsHandledInlineButEveryOtherRequestIsLaunched() = runTest {
        val inner = FakeInner()
        val transport = ConcurrentDispatchTransport(inner, scope(testScheduler))
        val handled = mutableListOf<String>()

        transport.onMessage { m -> handled += (m as JSONRPCRequest).method }
        transport.start()

        // The SDK asserts negotiated capabilities per session, so the handshake must keep blocking
        // the pump: it has already run by the time delivery returns.
        inner.deliver(request(1, Method.Defined.Initialize.value))
        assertEquals(listOf("initialize"), handled)

        // Anything else is launched instead, which is what frees the pump to keep reading.
        inner.deliver(request(2, Method.Defined.ToolsCall.value))
        assertEquals(listOf("initialize"), handled, "a tools/call must not be handled inline")

        advanceUntilIdle()
        assertEquals(listOf("initialize", "tools/call"), handled)
    }

    @Test
    fun notificationsStayInlineAndOrdered() = runTest {
        val inner = FakeInner()
        val transport = ConcurrentDispatchTransport(inner, scope(testScheduler))
        val seen = mutableListOf<String>()

        transport.onMessage { m -> if (m is JSONRPCNotification) seen += m.method }
        transport.start()

        inner.deliver(JSONRPCNotification(method = "notifications/initialized", params = JsonObject(emptyMap())))
        inner.deliver(JSONRPCNotification(method = "notifications/cancelled", params = JsonObject(emptyMap())))

        // No launch involved, so they are already recorded in arrival order.
        assertEquals(listOf("notifications/initialized", "notifications/cancelled"), seen)
    }

    @Test
    fun startAndCloseAndSendReachTheDelegate() = runTest {
        val inner = FakeInner()
        val transport = ConcurrentDispatchTransport(inner, scope(testScheduler))

        transport.start()
        assertTrue(inner.started)

        transport.send(JSONRPCResponse(id = RequestId(1L)))
        assertEquals(1, inner.sent.size)

        transport.close()
        assertTrue(inner.closed)
    }
}
