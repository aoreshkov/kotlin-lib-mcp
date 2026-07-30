package app.oreshkov.kotlinlibmcp.server.transport

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotification
import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeRequest
import io.modelcontextprotocol.kotlin.sdk.types.InitializeRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.InitializedNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Pins the inbound-dispatch behaviour this server depends on, which since kotlin-sdk 0.15.0 belongs
 * to `shared.Protocol` rather than to a decorator of ours.
 *
 * Until 0.14.0 the stdio pipeline was strictly one frame at a time, so a server-to-client request
 * made *inside* a request handler could never be answered — the client's reply sat unread in the
 * pipe behind the handler waiting for it. `transport/ConcurrentDispatchTransport.kt` existed solely
 * to break that cycle. 0.15.0 absorbed the fix (`handlerCoroutineContext`, an in-flight registry and
 * a post-`initialized` concurrency gate), the decorator was deleted, and these tests are what stops
 * that deletion from silently regressing:
 *
 *  - a nested server-to-client request is answered while its handler is parked (the deadlock);
 *  - a slow `tools/call` does not stall unrelated traffic;
 *  - the handshake is still handled serially;
 *  - `notifications/cancelled` cancels the running handler and suppresses its response — a
 *    guarantee 0.14.0 did not have at all, and the one our long `fetch_library` relies on.
 *
 * Note the deliberate use of [runCurrent] over `advanceUntilIdle`: the latter advances virtual time
 * far enough to trip `Protocol`'s own 60-second request timeout, which would fire the very
 * deadlocks these tests are meant to disprove.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentDispatchTest {

    /** Stands in for `StdioServerTransport`: lets a test push frames the way the read loop would. */
    private class FakeTransport : Transport {
        private var handler: suspend (JSONRPCMessage) -> Unit = {}
        private var closeBlock: () -> Unit = {}
        val sent = mutableListOf<JSONRPCMessage>()

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) { handler = block }
        override suspend fun start() = Unit
        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) { sent += message }
        override suspend fun close() = closeBlock()
        override fun onClose(block: () -> Unit) { closeBlock = block }
        override fun onError(block: (Throwable) -> Unit) = Unit

        /** Feeds a frame exactly as the SDK's processor pump does: awaiting the handler. */
        suspend fun deliver(message: JSONRPCMessage) = handler(message)

        fun responseTo(id: Long): JSONRPCResponse? =
            sent.filterIsInstance<JSONRPCResponse>().firstOrNull { it.id == RequestId(id) }

        fun outgoingRequest(method: String): JSONRPCRequest? =
            sent.filterIsInstance<JSONRPCRequest>().firstOrNull { it.method == method }
    }

    private fun TestScope.server(
        tool: Tool,
        handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult,
    ): Server =
        Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                // The handler scope must be a *dispatching* interceptor the test drives; the SDK
                // warns and degrades on unconfined ones, since handler resumptions would then run
                // on the transport read loop and reintroduce head-of-line blocking.
                handlerCoroutineContext = StandardTestDispatcher(testScheduler),
            ),
        ) {
            addTool(tool) { request -> handler(request) }
        }

    private fun tool(name: String) = Tool(name = name, inputSchema = ToolSchema())

    private fun toolsCall(id: Long, name: String): JSONRPCRequest = JSONRPCRequest(
        id = RequestId(id),
        method = Method.Defined.ToolsCall.value,
        params = CallToolRequest(CallToolRequestParams(name = name)).toJSON().params,
    )

    private fun initialize(id: Long): JSONRPCRequest = InitializeRequest(
        InitializeRequestParams(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = ClientCapabilities(),
            clientInfo = Implementation(name = "client", version = "0"),
        )
    ).toJSON().copy(id = RequestId(id))

    private fun cancelled(id: Long): JSONRPCNotification =
        CancelledNotification(CancelledNotificationParams(requestId = RequestId(id), reason = "user")).toJSON()

    /** Drives the handshake, after which the SDK switches the connection to concurrent dispatch. */
    private suspend fun FakeTransport.handshake() {
        deliver(initialize(id = 1))
        deliver(InitializedNotification().toJSON())
    }

    @Test
    fun aServerToClientRequestFromInsideAToolIsAnswered() = runTest {
        val transport = FakeTransport()
        // The shape of `elicitation/create` from inside `fetch_library`, minus the elicitation
        // capability: the handler asks the client something and suspends until the client replies.
        val server = server(tool("asks")) {
            ping()
            CallToolResult(content = listOf(TextContent("answered")))
        }
        val session = server.createSession(transport)
        transport.handshake()

        transport.deliver(toolsCall(id = 2, name = "asks"))
        runCurrent()

        // The question reached the client even though the handler that asked it is still parked...
        val ping = assertNotNull(transport.outgoingRequest(Method.Defined.Ping.value), "ping never went out")
        assertNull(transport.responseTo(2), "the tool must still be waiting for its answer")

        // ...and the answer is routed back to it. Before 0.15.0 this frame was never even read.
        transport.deliver(JSONRPCResponse(id = ping.id, result = EmptyResult()))
        runCurrent()

        assertNotNull(transport.responseTo(2), "the tool call deadlocked waiting for its own answer")
        session.close()
    }

    @Test
    fun aSlowToolCallDoesNotStallUnrelatedTraffic() = runTest {
        val transport = FakeTransport()
        val gate = CompletableDeferred<Unit>()
        val server = server(tool("slow")) {
            gate.await()
            CallToolResult(content = listOf(TextContent("done")))
        }
        val session = server.createSession(transport)
        transport.handshake()

        transport.deliver(toolsCall(id = 2, name = "slow"))
        runCurrent()
        assertNull(transport.responseTo(2), "the slow tool should still be running")

        // `ping` is one of the SDK's control methods: it bypasses the concurrency bounds entirely so
        // a saturated server stays reachable.
        transport.deliver(JSONRPCRequest(id = RequestId(3L), method = Method.Defined.Ping.value))
        runCurrent()
        assertNotNull(transport.responseTo(3), "a ping must be answerable while a tool call is in flight")

        gate.complete(Unit)
        runCurrent()
        assertNotNull(transport.responseTo(2))
        session.close()
    }

    @Test
    fun theHandshakeIsStillHandledSerially() = runTest {
        val transport = FakeTransport()
        val server = server(tool("noop")) { CallToolResult(content = emptyList()) }
        val session = server.createSession(transport)

        // Nothing may overtake `initialize`: the session negotiates capabilities there, and every
        // later assertion reads them. Before `notifications/initialized` the SDK therefore keeps
        // dispatch inline, so the answer is already sent by the time delivery returns.
        transport.deliver(initialize(id = 1))
        assertNotNull(transport.responseTo(1), "initialize must be handled inline, not launched")

        session.close()
    }

    @Test
    fun aCancelledNotificationCancelsTheRunningHandlerAndSuppressesItsResponse() = runTest {
        val transport = FakeTransport()
        val started = CompletableDeferred<Unit>()
        var cancelledInsideTool = false
        val server = server(tool("long")) {
            try {
                started.complete(Unit)
                CompletableDeferred<Unit>().await() // never completes; only cancellation ends this
                CallToolResult(content = emptyList())
            } catch (e: CancellationException) {
                cancelledInsideTool = true
                throw e
            }
        }
        val session = server.createSession(transport)
        transport.handshake()

        transport.deliver(toolsCall(id = 2, name = "long"))
        runCurrent()
        assertTrue(started.isCompleted, "the tool should have started")

        transport.deliver(cancelled(id = 2))
        runCurrent()

        // 0.14.0 had no in-flight registry, so this notification did nothing and the download ran on
        // unobserved. Now the handler unwinds through the CancellationException every layer of ours
        // re-throws, and the peer gets no response for a request it withdrew.
        assertTrue(cancelledInsideTool, "notifications/cancelled must cancel the running tool handler")
        assertNull(transport.responseTo(2), "a cancelled request must not be answered")
        session.close()
    }

    @Test
    fun anUnknownMethodIsRejectedWithoutDisturbingTheConnection() = runTest {
        val transport = FakeTransport()
        val server = server(tool("noop")) { CallToolResult(content = emptyList()) }
        val session = server.createSession(transport)
        transport.handshake()

        transport.deliver(JSONRPCRequest(id = RequestId(2L), method = "does/not/exist"))
        runCurrent()
        transport.deliver(JSONRPCRequest(id = RequestId(3L), method = Method.Defined.Ping.value))
        runCurrent()

        // The unknown method is answered with a JSON-RPC error, not a result...
        val error = transport.sent.filterIsInstance<JSONRPCError>().singleOrNull()
        assertEquals(RequestId(2L), error?.id)
        assertEquals(RPCError.ErrorCode.METHOD_NOT_FOUND, error?.error?.code)
        assertNull(transport.responseTo(2))
        // ...and the connection carries on serving.
        assertNotNull(transport.responseTo(3), "the connection must survive an unknown method")
        session.close()
    }
}
