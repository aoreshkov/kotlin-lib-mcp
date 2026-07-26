package app.oreshkov.kotlinlibmcp.server.transport

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Makes request handling concurrent, so the server can answer a client while a request of its own
 * is still in flight.
 *
 * **Why this exists.** `StdioServerTransport.processorPump` awaits `onMessage` for one frame before
 * reading the next, and `Protocol.onRequest` awaits the handler inline. Together that makes the
 * whole stdio pipeline strictly serial: while a `tools/call` handler is suspended, *no* further
 * frame is read — including the client's answer to a request the handler itself is waiting on. A
 * server-initiated `elicitation/create` from inside a tool therefore deadlocks permanently
 * (verified against kotlin-sdk 0.14.0 sources, and reproduced end-to-end before this was added).
 * The same serialization also means `ping`, `tasks/get` and `notifications/cancelled` go unanswered
 * for the duration of a long tool call.
 *
 * **What it changes.** Only non-`initialize` *requests* are dispatched concurrently. Everything else
 * — the `initialize` handshake, notifications, and responses — stays inline and therefore ordered.
 * Keeping **responses** inline is the entire point: `Protocol.onResponse` merely completes the
 * waiting deferred, so it must never queue behind the handler that is waiting for it.
 *
 * Concurrent requests may complete out of order. JSON-RPC allows exactly that — clients correlate
 * by `id`, never by arrival order.
 */
class ConcurrentDispatchTransport(
    private val delegate: Transport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Transport {

    private var handler: suspend (JSONRPCMessage) -> Unit = {}

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        handler = block
    }

    override suspend fun start() {
        delegate.onMessage { message ->
            if (message.dispatchesConcurrently()) {
                scope.launch { handler(message) }
            } else {
                handler(message)
            }
        }
        delegate.start()
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        delegate.send(message, options)
    }

    override suspend fun close() {
        // In-flight handlers belong to a connection that is going away; unwinding them through
        // cancellation is what lets an interrupted fetch stop rather than run on unobserved.
        scope.cancel()
        delegate.close()
    }

    override fun onClose(block: () -> Unit) {
        delegate.onClose(block)
    }

    override fun onError(block: (Throwable) -> Unit) {
        delegate.onError(block)
    }

    /**
     * Requests run concurrently — except `initialize`, which every later message depends on: the
     * SDK asserts negotiated capabilities per session, so letting anything overtake the handshake
     * would trade one race for another.
     */
    private fun JSONRPCMessage.dispatchesConcurrently(): Boolean =
        this is JSONRPCRequest && method != Method.Defined.Initialize.value
}
