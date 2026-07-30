package app.oreshkov.kotlinlibmcp.server.tasks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Task
import io.modelcontextprotocol.kotlin.sdk.types.TaskMetadata
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Lifecycle of [TaskStore]: the state machine a client observes through `tasks/get`, `tasks/result`
 * and `tasks/cancel`.
 *
 * The store is given the test's own scheduler so the launched work is deterministic —
 * [advanceUntilIdle] stands in for "the task has had a chance to run".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskStoreTest {

    /** The owning session for tests that only care about the lifecycle, not about isolation. */
    private val S1 = "session-1"
    private val S2 = "session-2"

    private fun ok(text: String = "done") = CallToolResult(content = listOf(TextContent(text)))

    private fun TestScopeStore(scope: CoroutineScope, now: () -> Instant = Instant::now) =
        TaskStore(scope = scope, now = now)

    private fun taskRun(
        label: String = "fetch_library",
        requested: TaskMetadata? = null,
        onStatus: suspend (Task) -> Unit = {},
        block: suspend () -> CallToolResult,
    ) = TaskRun(label = label, requested = requested, onStatus = onStatus, block = block)

    @Test
    fun completedTaskExposesItsPayload() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val started = store.start(S1, taskRun { ok("hello") })
        // The handle comes back before the work runs — that is the whole point of a task.
        assertEquals(TaskStatus.Working, started.status)
        assertTrue(started.taskId.isNotBlank())

        advanceUntilIdle()

        assertEquals(TaskStatus.Completed, store.get(S1, started.taskId).status)
        val payload = store.payload(S1, started.taskId)
        assertEquals("hello", (payload.content.single() as TextContent).text)
        store.close()
    }

    @Test
    fun payloadIsRefusedUntilTheTaskFinishes() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val gate = CompletableDeferred<Unit>()

        val started = store.start(S1, taskRun { gate.await(); ok() })
        advanceUntilIdle()

        // Still Working: tasks/result must say "poll tasks/get", not hand back a half-result.
        assertFailsWith<TaskNotReadyException> { store.payload(S1, started.taskId) }

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TaskStatus.Completed, store.get(S1, started.taskId).status)
        store.close()
    }

    @Test
    fun aToolErrorStillCountsAsCompleted() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val started = store.start(
            S1,
            taskRun { CallToolResult(content = listOf(TextContent("boom")), isError = true) },
        )
        advanceUntilIdle()

        // SEP-1303: a tool that reports isError delivered a result. The task succeeded at running
        // it; the error belongs in the payload, where the model can read and act on it.
        val task = store.get(S1, started.taskId)
        assertEquals(TaskStatus.Completed, task.status)
        assertNotNull(task.statusMessage)
        assertEquals(true, store.payload(S1, started.taskId).isError)
        store.close()
    }

    @Test
    fun aThrowingToolFailsTheTask() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val started = store.start(S1, taskRun { throw IllegalStateException("analysis exploded") })
        advanceUntilIdle()

        val task = store.get(S1, started.taskId)
        assertEquals(TaskStatus.Failed, task.status)
        assertContains(task.statusMessage.orEmpty(), "analysis exploded")
        // No payload was ever produced, so tasks/result has nothing to hand back.
        assertFailsWith<TaskNotReadyException> { store.payload(S1, started.taskId) }
        store.close()
    }

    @Test
    fun cancelStopsTheWorkAndReportsCancelled() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val gate = CompletableDeferred<Unit>()
        var finished = false

        val started = store.start(S1, taskRun { gate.await(); finished = true; ok() })
        advanceUntilIdle()

        assertEquals(TaskStatus.Cancelled, store.cancel(S1, started.taskId).status)

        // Releasing the gate must not resurrect the cancelled body.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(!finished, "cancelled task body kept running")
        assertEquals(TaskStatus.Cancelled, store.get(S1, started.taskId).status)
        store.close()
    }

    @Test
    fun cancellingAFinishedTaskIsANoOp() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val started = store.start(S1, taskRun { ok() })
        advanceUntilIdle()

        // Cancelling after the fact is a race a well-behaved client can lose; it must not be an
        // error, and it must not rewrite a delivered result.
        assertEquals(TaskStatus.Completed, store.cancel(S1, started.taskId).status)
        assertNotNull(store.payload(S1, started.taskId))
        store.close()
    }

    @Test
    fun unknownTaskIdIsRejectedEverywhere() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        assertFailsWith<UnknownTaskException> { store.get(S1, "nope") }
        assertFailsWith<UnknownTaskException> { store.payload(S1, "nope") }
        assertFailsWith<UnknownTaskException> { store.cancel(S1, "nope") }
        store.close()
    }

    @Test
    fun ttlIsClampedToTheServerCeiling() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val default = store.start(S1, taskRun { ok() })
        val huge = store.start(S1, taskRun(requested = TaskMetadata(ttl = Long.MAX_VALUE)) { ok() })
        val modest = store.start(S1, taskRun(requested = TaskMetadata(ttl = 5_000)) { ok() })
        advanceUntilIdle()

        // Every record is held in memory, so an unbounded retention would just be a leak.
        assertEquals(60 * 60 * 1000L, huge.ttl)
        assertEquals(5_000L, modest.ttl)
        assertNotNull(default.ttl)
        assertTrue(default.ttl!! <= 60 * 60 * 1000L)
        store.close()
    }

    @Test
    fun finishedTasksAreSweptAfterTheirTtlButRunningOnesAreNot() = runTest {
        var clock = Instant.parse("2026-07-25T10:00:00Z")
        val store = TestScopeStore(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            now = { clock },
        )
        val gate = CompletableDeferred<Unit>()

        val done = store.start(S1, taskRun(requested = TaskMetadata(ttl = 1_000)) { ok() })
        val running = store.start(S1, taskRun(requested = TaskMetadata(ttl = 1_000)) { gate.await(); ok() })
        advanceUntilIdle()

        clock = clock.plusSeconds(60)

        // The finished record is past its retention; the in-flight one is still being awaited by a
        // client, so evicting it would strand genuine work.
        assertFailsWith<UnknownTaskException> { store.get(S1, done.taskId) }
        assertEquals(TaskStatus.Working, store.get(S1, running.taskId).status)

        gate.complete(Unit)
        store.close()
    }

    @Test
    fun statusCallbackSeesEveryTransition() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val seen = mutableListOf<TaskStatus>()

        store.start(S1, taskRun(onStatus = { seen += it.status }) { ok() })
        advanceUntilIdle()

        // The first status is published from inside the job, so a client watching
        // notifications/tasks/status cannot miss the `working` edge.
        assertEquals(listOf(TaskStatus.Working, TaskStatus.Completed), seen)
        store.close()
    }

    @Test
    fun inputRequiredIsANonTerminalRoundTripAndIsNotSwept() = runTest {
        var clock = Instant.parse("2026-07-25T10:00:00Z")
        val store = TestScopeStore(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            now = { clock },
        )
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<TaskStatus>()

        val started = store.start(
            S1,
            taskRun(requested = TaskMetadata(ttl = 1_000), onStatus = { seen += it.status }) {
                currentCoroutineContext()[TaskContext]!!.awaitingInput("Need a version") { gate.await() }
                ok()
            },
        )
        advanceUntilIdle()

        assertEquals(TaskStatus.InputRequired, store.get(S1, started.taskId).status)
        assertEquals("Need a version", store.get(S1, started.taskId).statusMessage)

        // A task waiting on a human is emphatically not finished, so the TTL sweep must leave it be.
        clock = clock.plusSeconds(60)
        assertEquals(TaskStatus.InputRequired, store.get(S1, started.taskId).status)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(TaskStatus.Working, TaskStatus.InputRequired, TaskStatus.Working, TaskStatus.Completed),
            seen,
        )
        store.close()
    }

    @Test
    fun aFailedElicitationStillReturnsTheTaskToWorking() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val started = store.start(
            S1,
            taskRun {
                val task = currentCoroutineContext()[TaskContext]!!
                runCatching { task.awaitingInput("Need a version") { error("client exploded") } }
                ok()
            },
        )
        advanceUntilIdle()

        // The restore is in a finally: a question that errors must not strand the task in
        // input_required, where a client would poll forever waiting for a prompt that never comes.
        assertEquals(TaskStatus.Completed, store.get(S1, started.taskId).status)
        store.close()
    }

    @Test
    fun aTerminalTaskNeverGoesBackToInputRequired() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val started = store.start(S1, taskRun { ok() })
        advanceUntilIdle()
        assertEquals(TaskStatus.Completed, store.get(S1, started.taskId).status)

        // completed/failed/cancelled MUST NOT transition to any other status — including by a
        // straggling coroutine from a body that raced the store.
        store.markInputRequired(started.taskId, "too late")
        assertEquals(TaskStatus.Completed, store.get(S1, started.taskId).status)
        store.close()
    }

    @Test
    fun listReportsLiveTasksNewestFirst() = runTest {
        var clock = Instant.parse("2026-07-25T10:00:00Z")
        val store = TestScopeStore(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            now = { clock },
        )

        val first = store.start(S1, taskRun { ok() })
        clock = clock.plusSeconds(1)
        val second = store.start(S1, taskRun { ok() })
        advanceUntilIdle()

        assertEquals(listOf(second.taskId, first.taskId), store.list(S1).map { it.taskId })
        assertNull(store.list(S1).firstOrNull { it.status != TaskStatus.Completed })
        store.close()
    }

    // --- session isolation ---
    //
    // One store serves every session. With a single stdio client that is invisible, but the HTTP
    // transport creates a session per connection, and `tasks/result` hands back whole tool results.

    @Test
    fun listOnlyReportsTheCallersOwnTasks() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val mine = store.start(S1, taskRun { ok("mine") })
        val theirs = store.start(S2, taskRun { ok("theirs") })
        advanceUntilIdle()

        assertEquals(listOf(mine.taskId), store.list(S1).map { it.taskId })
        assertEquals(listOf(theirs.taskId), store.list(S2).map { it.taskId })
        store.close()
    }

    @Test
    fun anotherSessionsTaskIsIndistinguishableFromOneThatNeverExisted() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val theirs = store.start(S2, taskRun { ok("secret") })
        advanceUntilIdle()

        // Same exception, same message as a made-up id — reporting "not yours" would turn tasks/get
        // into an oracle for other sessions' task ids.
        val leaked = assertFailsWith<UnknownTaskException> { store.get(S1, theirs.taskId) }
        val absent = assertFailsWith<UnknownTaskException> { store.get(S1, "no-such-task") }
        assertEquals(
            absent.message?.replace("no-such-task", theirs.taskId),
            leaked.message,
        )
        store.close()
    }

    @Test
    fun aSessionCannotReadOrCancelAnotherSessionsTask() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val gate = CompletableDeferred<Unit>()

        val theirs = store.start(S2, taskRun { gate.await(); ok("secret") })
        advanceUntilIdle()

        // tasks/result would otherwise hand a whole CallToolResult to the wrong client, and
        // tasks/cancel would let any session kill any other's in-flight work.
        assertFailsWith<UnknownTaskException> { store.payload(S1, theirs.taskId) }
        assertFailsWith<UnknownTaskException> { store.cancel(S1, theirs.taskId) }

        // ...and the owner is unaffected by the attempts.
        assertEquals(TaskStatus.Working, store.get(S2, theirs.taskId).status)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("secret", (store.payload(S2, theirs.taskId).content.single() as TextContent).text)
        store.close()
    }

    @Test
    fun sessionsWithNoTasksSeeAnEmptyList() = runTest {
        val store = TestScopeStore(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        store.start(S1, taskRun { ok() })
        advanceUntilIdle()

        assertEquals(emptyList(), store.list("session-that-never-started-anything"))
        store.close()
    }
}
