package app.oreshkov.kotlinlibmcp.server.tasks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TaskMetadata
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * Task records surviving a restart, and the access rule that makes surviving them worth anything.
 *
 * A task's owner is a per-connection session id, so after a restart no live caller can match a
 * restored record. Binding strictly would leave every persisted task permanently unreachable; these
 * tests pin the split that avoids that — an orphan is reachable **by exact id** and is never
 * enumerated by `tasks/list`.
 *
 * These use [runBlocking] and real dispatchers rather than `runTest`, deliberately: the store writes
 * real files, and `runTest`'s virtual clock would fast-forward `payload`'s TTL-bounded wait past a
 * task that is genuinely still running on `Dispatchers.Default`.
 *
 * Completion is awaited through `payload()`, which blocks until terminal — so there is no sleeping
 * or polling anywhere here.
 */
class TaskPersistenceTest {

    private val dirs = mutableListOf<Path>()
    private val stores = mutableListOf<TaskStore>()

    private val alice = "session-alice"
    private val bob = "session-bob"

    @AfterTest
    fun cleanUp() {
        stores.forEach { runCatching { it.close() } }
        dirs.forEach { dir -> runCatching { dir.toFile().deleteRecursively() } }
    }

    private fun tempDir(): Path = createTempDirectory("task-store-test").also { dirs.add(it) }

    /** A store writing into [dir], as `McpServerFactory` builds it. */
    private fun store(
        dir: Path,
        recordStore: TaskRecordStore = TaskRecordStore(dir),
        now: () -> Instant = Instant::now,
    ): TaskStore =
        TaskStore(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = now,
            recordStore = recordStore,
        ).also { stores.add(it) }

    private fun ok(text: String = "done") = CallToolResult(content = listOf(TextContent(text)))

    private fun job(ttlMs: Long = 600_000, block: suspend () -> CallToolResult) =
        TaskRun(label = "fetch_library", requested = TaskMetadata(ttl = ttlMs), block = block)

    @Test
    fun aCompletedTaskAndItsPayloadSurviveARestart(): Unit = runBlocking {
        val dir = tempDir()
        val first = store(dir)
        val started = first.start(alice, job { ok("cached sources") })
        first.payload(alice, started.taskId) // blocks until terminal
        assertEquals(TaskStatus.Completed, first.get(alice, started.taskId).status)
        first.close()

        // A brand-new store over the same directory: the restarted process.
        val second = store(dir)
        val restored = second.get("session-after-restart", started.taskId)
        assertEquals(TaskStatus.Completed, restored.status)
        assertEquals(started.taskId, restored.taskId)
        // The payload round-trips through the same McpJson path that puts it on the wire.
        assertEquals(
            "cached sources",
            (second.payload("session-after-restart", started.taskId).content.single() as TextContent).text,
        )
    }

    @Test
    fun aPersistedPayloadKeepsItsResourceLink(): Unit = runBlocking {
        // fetch_library's result is text plus a resource_link; the store keeps the whole
        // CallToolResult, so a task-augmented fetch must hand back the link after a restart too.
        val link = ResourceLink(name = "demo index", uri = "kotlinlib://com.example/demo/1.0.0/index")
        val dir = tempDir()
        val first = store(dir)
        val started = first.start(alice, job { CallToolResult(content = listOf(TextContent("{}"), link)) })
        first.payload(alice, started.taskId)
        first.close()

        val restored = store(dir).payload("session-after-restart", started.taskId)
        assertEquals(link, restored.content.filterIsInstance<ResourceLink>().single())
    }

    @Test
    fun aTaskInterruptedByACrashComesBackFailed(): Unit = runBlocking {
        val dir = tempDir()
        val never = CompletableDeferred<Unit>()
        val firstDisk = TaskRecordStore(dir)
        val first = store(dir, recordStore = firstDisk)
        // `start` persists before the body runs, so the record is on disk while still `working`.
        val started = first.start(alice, job { never.await(); ok() })
        assertEquals(TaskStatus.Working, first.get(alice, started.taskId).status)

        // A hard crash: the process is gone without `close` running, so the record on disk is still
        // `working` and nothing will ever finish it. Leaving it that way would make tasks/result
        // block until the TTL expired on a task that cannot complete. `first` is deliberately left
        // open — closing it is what a *graceful* shutdown does, and it writes a terminal state
        // (see [aGracefulShutdownFailsInFlightTasksOnDisk]); `cleanUp` closes it after the asserts.
        // Only its lock goes, as the OS drops a dead process's locks: a held one marks a live server
        // (see [aServerInAnotherProcessKeepsItsTasksUntilItDies]).
        firstDisk.close()
        val second = store(dir)
        val restored = second.get(alice, started.taskId)
        assertEquals(TaskStatus.Failed, restored.status)
        assertContains(restored.statusMessage.orEmpty(), "restart")

        // Terminal on restore, so tasks/result answers instead of parking.
        assertFailsWith<TaskNotReadyException> { second.payload(alice, started.taskId) }
    }

    @Test
    fun aGracefulShutdownFailsInFlightTasksOnDisk(): Unit = runBlocking {
        val dir = tempDir()
        val never = CompletableDeferred<Unit>()
        val first = store(dir)
        val started = first.start(alice, job { never.await(); ok() })
        assertEquals(TaskStatus.Working, first.get(alice, started.taskId).status)

        // `close` must write the terminal state itself, before cancelling the scope. Left to the
        // job's own cancellation path it would land from another thread — after this returns, and
        // possibly after the JVM has exited — so the same task would come back `cancelled` or
        // `failed` depending on thread timing.
        first.close()

        val second = store(dir)
        val restored = second.get(alice, started.taskId)
        assertEquals(TaskStatus.Failed, restored.status)
        assertContains(restored.statusMessage.orEmpty(), "shut down")
        assertFailsWith<TaskNotReadyException> { second.payload(alice, started.taskId) }
    }

    @Test
    fun aServerSharingTheDirectoryLeavesALiveServersTasksAlone(): Unit = runBlocking {
        // Every stdio session launches its own server on the default cache dir, so a second one
        // starting must not take the first one's running task for a crash survivor.
        val dir = tempDir()
        val never = CompletableDeferred<Unit>()
        val firstDisk = TaskRecordStore(dir)
        val first = store(dir, recordStore = firstDisk)
        val running = first.start(alice, job { never.await(); ok("finished later") })
        val inFlight = dir.resolve("${firstDisk.instance}.${running.taskId}.1.json.tmp").apply { writeText("{") }
        val abandoned = dir.resolve("${UUID.randomUUID()}.${running.taskId}.2.json.tmp").apply { writeText("{") }

        val second = store(dir)
        assertFailsWith<UnknownTaskException> { second.get(alice, running.taskId) }
        assertEquals(TaskStatus.Working, TaskRecordStore(dir).loadAll().single().status)
        assertTrue(inFlight.exists(), "a live server's save in flight was swept")
        assertFalse(abandoned.exists(), "a gone server's temp file was left behind")

        // And the first server's own saves still land.
        never.complete(Unit)
        first.payload(alice, running.taskId)
        assertEquals(TaskStatus.Completed, TaskRecordStore(dir).loadAll().single().status)
    }

    /**
     * The same, across a real process boundary, where the lock answers differently than inside
     * one JVM (`tryLock` returns `null` instead of throwing) and a crash is a kill, not a call: the
     * child server holds a running task until it is killed, then a new server must recover it.
     */
    @Test
    fun aServerInAnotherProcessKeepsItsTasksUntilItDies(): Unit = runBlocking {
        val dir = tempDir()
        val java = ProcessHandle.current().info().command().orElseThrow()
        val other = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), SharedDirectoryServer::class.java.name, "$dir",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val taskId = other.inputReader().lineSequence().firstOrNull { UUID_LINE.matches(it) }
                ?: fail("the other server exited without starting a task")
            val instance = TaskRecordStore(dir).loadAll().single().instance
            val inFlight = dir.resolve("$instance.$taskId.1.json.tmp").apply { writeText("{") }

            val alongside = store(dir)
            assertFailsWith<UnknownTaskException> { alongside.get(alice, taskId) }
            assertEquals(TaskStatus.Working, TaskRecordStore(dir).loadAll().single().status)
            assertTrue(inFlight.exists(), "a live server's save in flight was swept")

            other.destroyForcibly().waitFor() // a crash: the OS drops the dead server's lock
            val restarted = store(dir).get(alice, taskId)
            assertEquals(TaskStatus.Failed, restarted.status)
            assertContains(restarted.statusMessage.orEmpty(), "restart")
            assertFalse(inFlight.exists(), "a crashed server's temp file was left behind")
            assertFalse(dir.resolve("$instance.lock").exists(), "a crashed server's lock file was left behind")
        } finally {
            other.destroyForcibly().waitFor()
        }
    }

    @Test
    fun aShutdownWaitsForACompletionStillOnItsWayToDisk(): Unit = runBlocking {
        val dir = tempDir()
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Holds the job's save of its terminal state open: the task is `completed` in memory but not
        // yet on disk, the window close() can otherwise land in.
        val slowDisk = object : TaskRecordStore(dir) {
            override fun save(record: PersistedTask) {
                if (record.status == TaskStatus.Completed) {
                    saving.countDown()
                    release.await()
                }
                super.save(record)
            }
        }
        val first = TaskStore(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recordStore = slowDisk,
        ).also { stores.add(it) }
        val started = first.start(alice, job { ok() })
        saving.await()

        // Already terminal, so close() leaves the status alone. But it must not return before that
        // status is on disk either, or a restart in between recovers the task as `working` and
        // fails it.
        val closer = thread { first.close() }
        try {
            closer.join(500)
            assertTrue(closer.isAlive, "close() returned while the task's final state was still being written")
        } finally {
            release.countDown()
            closer.join()
        }

        assertEquals(TaskStatus.Completed, store(dir).get(alice, started.taskId).status)
    }

    @Test
    fun anOrphanIsReachableByExactIdFromAnySession(): Unit = runBlocking {
        val dir = tempDir()
        val first = store(dir)
        val started = first.start(alice, job { ok() })
        first.payload(alice, started.taskId)
        first.close()

        // The restarted client reconnects with a new session id. Strict owner binding would make
        // its own task permanently unreachable, so an orphan is addressable by exact id.
        val second = store(dir)
        assertEquals(TaskStatus.Completed, second.get(bob, started.taskId).status)
    }

    @Test
    fun orphansAreNeverEnumeratedByList(): Unit = runBlocking {
        val dir = tempDir()
        val first = store(dir)
        val old = first.start(alice, job { ok() })
        first.payload(alice, old.taskId)
        first.close()

        val second = store(dir)
        // Reachable by exact id...
        assertEquals(TaskStatus.Completed, second.get(alice, old.taskId).status)
        // ...but listing stays strictly owner-scoped. It is the operation the spec singles out as
        // unsafe without a way to identify the requestor, and an orphan cannot be attributed.
        assertEquals(emptyList(), second.list(alice), "an orphan must not be enumerated")
        assertEquals(emptyList(), second.list(bob))

        // A task this process started is listed for its owner as usual.
        val fresh = second.start(alice, job { ok() })
        second.payload(alice, fresh.taskId)
        assertEquals(listOf(fresh.taskId), second.list(alice).map { it.taskId })
    }

    @Test
    fun aLiveSessionStillCannotSeeAnotherLiveSessionsTask(): Unit = runBlocking {
        val dir = tempDir()
        val store = store(dir)
        val alices = store.start(alice, job { ok() })
        store.payload(alice, alices.taskId)

        // The orphan bypass must not weaken the guarantee while the owner is still connected.
        assertFailsWith<UnknownTaskException> { store.get(bob, alices.taskId) }
        assertFailsWith<UnknownTaskException> { store.payload(bob, alices.taskId) }
        assertEquals(emptyList(), store.list(bob))
    }

    @Test
    fun anExpiredRecordIsNotRecoveredAndItsFileIsRemoved(): Unit = runBlocking {
        val dir = tempDir()
        var clock = Instant.parse("2026-07-31T10:00:00Z")
        val first = store(dir) { clock }
        val started = first.start(alice, job(ttlMs = 1_000) { ok() })
        first.payload(alice, started.taskId)
        first.close()

        clock = clock.plusSeconds(60)
        val second = store(dir) { clock }
        assertFailsWith<UnknownTaskException> { second.get(alice, started.taskId) }
        assertTrue(
            dir.listDirectoryEntries("*.json").isEmpty(),
            "an expired record's file must be deleted, not left to be re-read on every start",
        )
    }

    @Test
    fun aCorruptRecordIsDiscardedRatherThanFailingStartup(): Unit = runBlocking {
        val dir = tempDir()
        val first = store(dir)
        val good = first.start(alice, job { ok("intact") })
        first.payload(alice, good.taskId)
        first.close()

        dir.resolve("11111111-2222-3333-4444-555555555555.json").writeText("{ this is not json")

        // A damaged file must not fail the start, and the intact record must still load.
        val second = store(dir)
        assertEquals(TaskStatus.Completed, second.get(alice, good.taskId).status)
    }

    @Test
    fun aStoreWithoutARecordStoreTouchesNoDisk(): Unit = runBlocking {
        val dir = tempDir()
        val memoryOnly = TaskStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
            .also { stores.add(it) }

        val started = memoryOnly.start(alice, job { ok() })
        memoryOnly.payload(alice, started.taskId)

        // The dashboard and every pre-existing test build the store this way; it must stay purely
        // in memory rather than quietly writing to the default cache root.
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    private companion object {
        val UUID_LINE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/**
 * The other server in [TaskPersistenceTest.aServerInAnotherProcessKeepsItsTasksUntilItDies]: run in
 * its own JVM over the directory in `args[0]`, it starts one task that never finishes, prints its
 * id, and waits to be killed.
 */
internal object SharedDirectoryServer {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = TaskStore(recordStore = TaskRecordStore(Path.of(args[0])))
        val started = runBlocking {
            store.start("other-server", TaskRun("fetch_library", TaskMetadata(ttl = 600_000)) { awaitCancellation() })
        }
        println(started.taskId)
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
