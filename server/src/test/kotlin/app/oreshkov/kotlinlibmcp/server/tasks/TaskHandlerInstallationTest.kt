package app.oreshkov.kotlinlibmcp.server.tasks

import app.oreshkov.kotlinlibmcp.server.FakeTransport
import app.oreshkov.kotlinlibmcp.server.handshake
import app.oreshkov.kotlinlibmcp.server.mcpInitialize
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateTaskResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.GetTaskRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetTaskRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializedNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListTasksRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListTasksResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TaskMetadata
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `installTaskHandlersOnEverySession` — how task support reaches a transport that never hands us a
 * `ServerSession`.
 *
 * The HTTP transport creates a session per connection inside `mcpStreamableHttp`, so the only hook
 * is `Server.onConnect`, which does not say *which* session connected. These tests pin the two
 * properties that makes usable: every session gets handlers (not just the most recent one), and
 * each session's handlers are scoped to itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskHandlerInstallationTest {

    private val slow = Tool(
        name = "slow",
        inputSchema = ToolSchema(),
        execution = ToolExecution(taskSupport = TaskSupport.Optional),
    )

    private fun TestScope.server(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                tasks = ServerCapabilities.Tasks(
                    list = EmptyJsonObject,
                    cancel = EmptyJsonObject,
                    requests = ServerCapabilities.Tasks.Requests(
                        tools = ServerCapabilities.Tasks.Requests.Tools(call = EmptyJsonObject),
                    ),
                ),
            ),
            handlerCoroutineContext = StandardTestDispatcher(testScheduler),
        ),
    ) {
        addTool(slow) { CallToolResult(content = listOf(TextContent("done"))) }
    }

    private fun TestScope.store() =
        TaskStore(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    private fun taskCall(id: Long): JSONRPCRequest = JSONRPCRequest(
        id = RequestId(id),
        method = Method.Defined.ToolsCall.value,
        params = CallToolRequest(CallToolRequestParams(name = "slow", task = TaskMetadata())).toJSON().params,
    )

    private fun tasksList(id: Long): JSONRPCRequest =
        ListTasksRequest().toJSON().copy(id = RequestId(id))

    private fun tasksGet(id: Long, taskId: String): JSONRPCRequest =
        GetTaskRequest(GetTaskRequestParams(taskId = taskId)).toJSON().copy(id = RequestId(id))

    private fun createdTaskId(transport: FakeTransport, id: Long): String {
        val result = assertNotNull(transport.responseTo(id), "no response to tools/call id=$id").result
        return assertIs<CreateTaskResult>(result, "a task-augmented tools/call must answer with a handle")
            .task.taskId
    }

    private fun listedTaskIds(transport: FakeTransport, id: Long): List<String> {
        val result = assertNotNull(transport.responseTo(id), "no response to tasks/list id=$id").result
        return assertIs<ListTasksResult>(result).tasks.map { it.taskId }
    }

    @Test
    fun aSessionConnectingAfterInstallationGetsTaskHandlers() = runTest {
        val server = server()
        val store = store()
        // Installed before any connection, exactly as `runHttpServer` does it.
        server.installTaskHandlersOnEverySession(store)

        val transport = FakeTransport()
        val session = server.createSession(transport)
        transport.handshake()

        transport.deliver(taskCall(id = 2))
        runCurrent()

        // Without the hook this would be the SDK's own tools/call handler, which ignores
        // params.task and answers with a plain CallToolResult.
        val taskId = createdTaskId(transport, 2)
        assertTrue(taskId.isNotBlank())

        transport.deliver(tasksList(id = 3))
        runCurrent()
        // ...and tasks/list is answered at all, which the SDK never registers a handler for.
        assertEquals(listOf(taskId), listedTaskIds(transport, 3))

        session.close()
        store.close()
    }

    @Test
    fun multipleSessionsEachGetTheirOwnHandlers() = runTest {
        val server = server()
        val store = store()
        server.installTaskHandlersOnEverySession(store)

        // Two connections accepted back to back — the shape the HTTP transport produces.
        val alice = FakeTransport()
        val aliceSession = server.createSession(alice)
        val bob = FakeTransport()
        val bobSession = server.createSession(bob)
        alice.handshake()
        bob.handshake()

        alice.deliver(taskCall(id = 2))
        bob.deliver(taskCall(id = 2))
        runCurrent()

        assertTrue(createdTaskId(alice, 2).isNotBlank(), "alice's session was not configured")
        assertTrue(createdTaskId(bob, 2).isNotBlank(), "bob's session was not configured")

        aliceSession.close()
        bobSession.close()
        store.close()
    }

    @Test
    fun everySessionIsConfiguredNotJustTheNewestOne() = runTest {
        val server = server()
        val store = store()

        // Both sessions exist *before* installation, so a single sweep has to reach both. This is
        // the case that separates sweeping from picking `sessions.values.last()`, which is all
        // `Server.onConnect` would otherwise let you do — it never says which session connected.
        //
        // The production ordering (install, then accept) cannot produce this state, but the same
        // one-sweep-must-cover-many-sessions requirement is what protects the reachable race: two
        // concurrent `createSession` calls where one's onConnect runs after the other was added,
        // leaving "the last session" already configured and the first with no handlers at all.
        val alice = FakeTransport()
        val aliceSession = server.createSession(alice)
        val bob = FakeTransport()
        val bobSession = server.createSession(bob)

        server.installTaskHandlersOnEverySession(store)

        alice.handshake()
        alice.deliver(taskCall(id = 2))
        runCurrent()

        assertTrue(
            createdTaskId(alice, 2).isNotBlank(),
            "the older session was skipped — installation configured only the newest one",
        )

        aliceSession.close()
        bobSession.close()
        store.close()
    }

    @Test
    fun oneSessionsTasksAreInvisibleToAnother() = runTest {
        val server = server()
        val store = store()
        server.installTaskHandlersOnEverySession(store)

        val alice = FakeTransport()
        val aliceSession = server.createSession(alice)
        val bob = FakeTransport()
        val bobSession = server.createSession(bob)
        alice.handshake()
        bob.handshake()

        alice.deliver(taskCall(id = 2))
        runCurrent()
        val aliceTask = createdTaskId(alice, 2)

        bob.deliver(tasksList(id = 3))
        bob.deliver(tasksGet(id = 4, taskId = aliceTask))
        runCurrent()

        // The whole point of doing this on HTTP: sessions share one store and one process.
        assertEquals(emptyList(), listedTaskIds(bob, 3), "bob must not see alice's task")
        assertNull(bob.responseTo(4), "bob must not be able to read alice's task")
        assertEquals(RPCError.ErrorCode.INVALID_PARAMS, bob.errorTo(4)?.error?.code)

        // Alice still sees her own.
        alice.deliver(tasksList(id = 5))
        runCurrent()
        assertEquals(listOf(aliceTask), listedTaskIds(alice, 5))

        aliceSession.close()
        bobSession.close()
        store.close()
    }

    @Test
    fun handlersAreInPlaceBeforeTheSessionsFirstFrameIsDispatched() = runTest {
        val server = server()
        val store = store()
        server.installTaskHandlersOnEverySession(store)

        val transport = FakeTransport()
        val session = server.createSession(transport)

        // `Server.createSession` fires onConnect before the Streamable HTTP route feeds the POST
        // body to the transport, so even a client that pipelines everything cannot outrun setup.
        transport.deliver(mcpInitialize(id = 1))
        transport.deliver(InitializedNotification().toJSON())
        transport.deliver(taskCall(id = 2))
        runCurrent()

        assertTrue(createdTaskId(transport, 2).isNotBlank())
        session.close()
        store.close()
    }
}
