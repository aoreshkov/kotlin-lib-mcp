package app.oreshkov.kotlinlibmcp.server.tasks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TaskMetadata
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private fun store(dir: Path, now: () -> Instant = Instant::now): TaskStore =
        TaskStore(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = now,
            recordStore = TaskRecordStore(dir),
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
    fun aTaskInterruptedByTheRestartComesBackFailed(): Unit = runBlocking {
        val dir = tempDir()
        val never = CompletableDeferred<Unit>()
        val first = store(dir)
        // `start` persists before the body runs, so the record is on disk while still `working`.
        val started = first.start(alice, job { never.await(); ok() })
        assertEquals(TaskStatus.Working, first.get(alice, started.taskId).status)
        first.close()

        // Its coroutine died with the process and nothing will ever finish it. Leaving it `working`
        // would make tasks/result block until the TTL expired on a task that cannot complete.
        val second = store(dir)
        val restored = second.get(alice, started.taskId)
        assertEquals(TaskStatus.Failed, restored.status)
        assertContains(restored.statusMessage.orEmpty(), "restart")

        // Terminal on restore, so tasks/result answers instead of parking.
        assertFailsWith<TaskNotReadyException> { second.payload(alice, started.taskId) }
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
}
