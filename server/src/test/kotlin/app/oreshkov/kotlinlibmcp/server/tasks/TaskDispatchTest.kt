package app.oreshkov.kotlinlibmcp.server.tasks

import app.oreshkov.kotlinlibmcp.server.FakeConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateTaskResult
import io.modelcontextprotocol.kotlin.sdk.types.TaskMetadata
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatusNotification
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * `dispatchToolCall` replaces the SDK's own `tools/call` handler for **every** tool, so most of
 * this file is a regression guard: without a `task` field, dispatch must behave exactly as
 * `Server.handleCallTool` did before task support existed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDispatchTest {

    private val taskable = registered("taskable", taskSupport = TaskSupport.Optional) {
        CallToolResult(content = listOf(TextContent("ran")))
    }
    private val plain = registered("plain", taskSupport = null) {
        CallToolResult(content = listOf(TextContent("ran")))
    }
    private val tools = listOf(taskable, plain).associateBy { it.tool.name }

    private fun registered(
        name: String,
        taskSupport: TaskSupport?,
        handler: suspend () -> CallToolResult,
    ) = RegisteredTool(
        tool = Tool(
            name = name,
            inputSchema = ToolSchema(),
            execution = taskSupport?.let { ToolExecution(taskSupport = it) },
        ),
        handler = { handler() },
    )

    private fun call(name: String, task: TaskMetadata? = null) =
        CallToolRequest(CallToolRequestParams(name = name, task = task))

    private fun store(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        TaskStore(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)))

    // --- passthrough: the behavior that must not drift from the SDK ---

    @Test
    fun aCallWithoutTaskRunsSynchronouslyEvenForATaskableTool() = runTest {
        val store = store(testScheduler)

        val result = dispatchToolCall(tools, FakeConnection(), store, call("taskable"))

        // `taskSupport: optional` is an offer, not a redirect: a client that does not ask for a
        // task keeps getting a plain CallToolResult.
        val callResult = assertIs<CallToolResult>(result)
        assertEquals("ran", (callResult.content.single() as TextContent).text)
        assertTrue(store.list().isEmpty(), "a non-task call must not create a task")
        store.close()
    }

    @Test
    fun aTaskOnANonTaskableToolDegradesToASynchronousCall() = runTest {
        val store = store(testScheduler)

        val result = dispatchToolCall(tools, FakeConnection(), store, call("plain", TaskMetadata()))

        // Absent `execution` means Forbidden per the spec. Serving the call normally is friendlier
        // than failing, and the client still gets its answer.
        assertIs<CallToolResult>(result)
        assertTrue(store.list().isEmpty())
        store.close()
    }

    @Test
    fun anUnknownToolIsAnErrorResultNotAProtocolError() = runTest {
        val store = store(testScheduler)

        val result = dispatchToolCall(tools, FakeConnection(), store, call("nope"))

        val callResult = assertIs<CallToolResult>(result)
        assertEquals(true, callResult.isError)
        assertContains((callResult.content.single() as TextContent).text, "not found")
        store.close()
    }

    @Test
    fun aThrowingToolIsFlattenedIntoAnErrorResult() = runTest {
        val store = store(testScheduler)
        val boom = registered("boom", taskSupport = null) { throw IllegalStateException("kaboom") }

        val result = dispatchToolCall(mapOf("boom" to boom), FakeConnection(), store, call("boom"))

        val callResult = assertIs<CallToolResult>(result)
        assertEquals(true, callResult.isError)
        assertContains((callResult.content.single() as TextContent).text, "kaboom")
        store.close()
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAnErrorResult() = runTest {
        val store = store(testScheduler)
        val cancelled = registered("cancelled", taskSupport = null) {
            throw CancellationException("client went away")
        }

        // A cancelled call is not a tool failure — swallowing it into an isError result would tell
        // the model to retry work the client no longer wants.
        assertFailsWith<CancellationException> {
            dispatchToolCall(mapOf("cancelled" to cancelled), FakeConnection(), store, call("cancelled"))
        }
        store.close()
    }

    // --- the task branch ---

    @Test
    fun aTaskAugmentedCallReturnsAHandleImmediatelyThenCompletes() = runTest {
        val store = store(testScheduler)
        val connection = FakeConnection()

        val result = dispatchToolCall(tools, connection, store, call("taskable", TaskMetadata(ttl = 30_000)))

        // The response is a handle, not a result: the tool has not run yet.
        val created = assertIs<CreateTaskResult>(result)
        assertEquals(TaskStatus.Working, created.task.status)
        assertEquals(30_000L, created.task.ttl)

        advanceUntilIdle()

        assertEquals(TaskStatus.Completed, store.get(created.task.taskId).status)
        assertEquals("ran", (store.payload(created.task.taskId).content.single() as TextContent).text)
        store.close()
    }

    @Test
    fun taskStatusNotificationsCarryTheRelatedTaskMeta() = runTest {
        val store = store(testScheduler)
        val connection = FakeConnection()

        val created = assertIs<CreateTaskResult>(
            dispatchToolCall(tools, connection, store, call("taskable", TaskMetadata()))
        )
        advanceUntilIdle()

        val statuses = connection.notifications.filterIsInstance<TaskStatusNotification>()
        assertEquals(
            listOf(TaskStatus.Working, TaskStatus.Completed),
            statuses.map { it.params?.status },
        )
        // The spec's one unprefixed-key carve-out, so a client can correlate out-of-band messages
        // with the task they belong to.
        val meta = statuses.last().params?.meta
        assertContains(meta.toString(), "io.modelcontextprotocol/related-task")
        assertContains(meta.toString(), created.task.taskId)
        store.close()
    }

    @Test
    fun aToolThatNeedsClientInputParksTheTaskInInputRequired() = runTest {
        val store = store(testScheduler)
        val connection = FakeConnection()
        val answered = CompletableDeferred<Unit>()
        var seenTaskId: String? = null

        // Stands in for the version picker: a tool body that has to ask the client something. It
        // reaches the running task purely through the coroutine context — no task-shaped argument.
        val asking = registered("asking", taskSupport = TaskSupport.Optional) {
            val task = requireNotNull(currentCoroutineContext()[TaskContext]) { "no TaskContext in scope" }
            seenTaskId = task.taskId
            task.awaitingInput("Waiting for an answer") { answered.await() }
            CallToolResult(content = listOf(TextContent("ran")))
        }

        val created = assertIs<CreateTaskResult>(
            dispatchToolCall(mapOf("asking" to asking), connection, store, call("asking", TaskMetadata()))
        )
        advanceUntilIdle()

        // The client polling tasks/get now sees input_required, its cue to open tasks/result and
        // pick up the question the server is holding.
        val parked = store.get(created.task.taskId)
        assertEquals(TaskStatus.InputRequired, parked.status)
        assertEquals("Waiting for an answer", parked.statusMessage)
        assertEquals(created.task.taskId, seenTaskId)

        answered.complete(Unit)
        advanceUntilIdle()

        // …and back to working before the terminal transition, per the spec's state machine.
        assertEquals(
            listOf(TaskStatus.Working, TaskStatus.InputRequired, TaskStatus.Working, TaskStatus.Completed),
            connection.notifications.filterIsInstance<TaskStatusNotification>().map { it.params?.status },
        )
        assertEquals(TaskStatus.Completed, store.get(created.task.taskId).status)
        store.close()
    }

    @Test
    fun aTaskParkedOnInputCanStillBeCancelled() = runTest {
        val store = store(testScheduler)
        val never = CompletableDeferred<Unit>()
        val asking = registered("asking", taskSupport = TaskSupport.Optional) {
            currentCoroutineContext()[TaskContext]!!.awaitingInput("Waiting") { never.await() }
            CallToolResult(content = listOf(TextContent("ran")))
        }

        val created = assertIs<CreateTaskResult>(
            dispatchToolCall(mapOf("asking" to asking), FakeConnection(), store, call("asking", TaskMetadata()))
        )
        advanceUntilIdle()
        assertEquals(TaskStatus.InputRequired, store.get(created.task.taskId).status)

        // A user who abandons the prompt must not strand the task waiting forever.
        assertEquals(TaskStatus.Cancelled, store.cancel(created.task.taskId).status)
        advanceUntilIdle()
        assertEquals(TaskStatus.Cancelled, store.get(created.task.taskId).status)
        store.close()
    }
}
