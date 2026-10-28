package app.oreshkov.kotlinlibmcp.server.tasks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.IOException
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException

/**
 * [TaskStore] under real concurrency: a task body finishing or changing state on
 * [Dispatchers.Default] while the caller's thread cancels the task or shuts the store down.
 *
 * The windows these races open are narrow, so each round races [TASKS] tasks at once, and is run
 * [ROUNDS] times over a fresh store and directory; one race proves nothing either way. Every task
 * is checked for the same two properties:
 *
 * - **The first terminal state wins.** The spec: terminal statuses "MUST NOT transition to any
 *   other status", and a cancelled task "MUST remain in cancelled status even if execution
 *   continues to completion or fails".
 * - **Disk agrees with memory once everything has settled**, so a restart recovers what clients
 *   were told rather than an older state that happened to be written last.
 *
 * `TaskStoreTest` pins the same rules deterministically wherever a test dispatcher can reach the
 * interleaving; these cover the ones only real threads can.
 */
class TaskStoreRaceTest {

    private val alice = "session-alice"

    private fun ok() = CallToolResult(content = listOf(TextContent("done")))

    private fun run(block: suspend () -> CallToolResult) =
        TaskRun(label = "fetch_library", requested = null, block = block)

    /** Runs [round] [ROUNDS] times, each over its own store, scope and directory. */
    private fun race(round: suspend (dir: Path, scope: CoroutineScope, store: TaskStore) -> Unit) = runBlocking {
        repeat(ROUNDS) {
            val dir = createTempDirectory("task-race")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = TaskStore(scope = scope, recordStore = TaskRecordStore(dir))
            try {
                round(dir, scope, store)
            } finally {
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    /** Waits until the task's job, and anything the store launched beside it, has finished. */
    private suspend fun CoroutineScope.settle() = coroutineContext.job.children.toList().joinAll()

    /** Every record on disk, by task id. */
    private fun onDisk(dir: Path): Map<String, PersistedTask> =
        TaskRecordStore(dir).loadAll().associateBy { it.taskId }

    @Test
    fun cancelRacingCompletionKeepsWhicheverLandedFirst() = race { dir, scope, store ->
        val started = List(TASKS) { store.start(alice, run { ok() }) }

        val answered = started.associate { task ->
            task.taskId to try {
                store.cancel(alice, task.taskId).status
            } catch (_: TaskAlreadyTerminalException) {
                TaskStatus.Completed
            }
        }
        scope.settle()

        val disk = onDisk(dir)
        for ((taskId, status) in answered) {
            // Whatever tasks/cancel told the client is what tasks/get says from then on...
            val final = store.get(alice, taskId)
            assertEquals(status, final.status)
            // ...and what a restart would recover.
            assertEquals(final.status, disk.getValue(taskId).status)
            assertEquals(final.statusMessage, disk.getValue(taskId).statusMessage)
        }
    }

    @Test
    fun closeRacingCompletionNeverRelabelsACompletedTask() = race { dir, scope, store ->
        repeat(TASKS) { store.start(alice, run { ok() }) }

        store.close()
        val atClose = onDisk(dir)
        scope.settle()

        // close() is what a restart reads back, so nothing may change a record after it returns.
        assertEquals(atClose, onDisk(dir))
        // And each is one consistent state: either the task finished first and keeps its payload,
        // or the shutdown did and there is none — never "killed by the shutdown" with a result.
        for (record in atClose.values) when (record.status) {
            TaskStatus.Completed -> assertNotNull(record.result)
            TaskStatus.Failed -> assertNull(record.result, "a completed task was relabelled as failed by close()")
            else -> error("unexpected ${record.status} after close()")
        }
    }

    @Test
    fun cancelRacingInputRequiredLeavesCancelledOnDisk() = race { dir, scope, store ->
        val asking = CompletableDeferred<Unit>()
        // A body flipping between working and input_required as fast as it can, so its writes keep
        // overlapping the one tasks/cancel makes from this thread.
        val started = store.start(
            alice,
            run {
                val task = currentCoroutineContext()[TaskContext]!!
                while (currentCoroutineContext().isActive) {
                    task.awaitingInput("Need a version") { asking.complete(Unit); yield() }
                }
                ok()
            },
        )
        asking.await()

        assertEquals(TaskStatus.Cancelled, store.cancel(alice, started.taskId).status)
        scope.settle()

        assertEquals(TaskStatus.Cancelled, store.get(alice, started.taskId).status)
        // A stale `working`/`input_required` snapshot saved after the cancel would resurrect the
        // task on restart — as `failed`, since restore() fails anything it finds still running.
        assertEquals(TaskStatus.Cancelled, onDisk(dir).getValue(started.taskId).status)
    }

    @Test
    fun concurrentSavesOfOneTaskNeverExposeATornRecord() {
        val dir = createTempDirectory("task-race")
        try {
            val records = TaskRecordStore(dir)
            val taskId = "6f1c2b9e-0d4a-4c47-9a8e-3b2f5d7c1e00"
            val file = dir.resolve("$taskId.json")
            records.save(PersistedTask(taskId = taskId))

            // Messages of different lengths, so bytes from two writes landing in one file cannot
            // add up to valid JSON by accident.
            val writers = (1..8).map { writer ->
                thread {
                    repeat(200) { i ->
                        records.save(PersistedTask(taskId = taskId, statusMessage = "x".repeat(writer * i % 997)))
                    }
                }
            }
            // What a restart (or a second server on the same cache) would read at any moment.
            val torn = mutableListOf<String>()
            while (writers.any { it.isAlive }) {
                val text = try {
                    file.readText()
                } catch (_: IOException) {
                    continue // Windows refuses a read during the replace itself; that is not a torn read
                }
                try {
                    McpJson.decodeFromString<PersistedTask>(text)
                } catch (_: SerializationException) {
                    torn += text.take(80)
                }
            }
            writers.forEach { it.join() }

            assertEquals(emptyList(), torn, "a reader saw a half-written record")
            assertEquals(listOf(taskId), records.loadAll().map { it.taskId })
            assertTrue(dir.listDirectoryEntries("*.tmp").isEmpty(), "a save left its temp file behind")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val ROUNDS = 200
        const val TASKS = 16
    }
}
