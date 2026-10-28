package app.oreshkov.kotlinlibmcp.server.tasks

import app.oreshkov.kotlinlibmcp.server.runCatchingCancellable
import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One task as it is written to disk.
 *
 * Every field beyond the first has a default so an older file stays readable after the schema grows;
 * [McpJson] is configured with `ignoreUnknownKeys`, which covers the other direction — a file
 * written by a newer build loads here without failing.
 *
 * [result] holds the tool's `CallToolResult` pre-encoded as JSON rather than as a typed field. That
 * is deliberate: it is encoded and decoded through the same [McpJson] path that already puts a
 * `CallToolResult` on the wire in `TaskHandlers`, so what a client gets from `tasks/result` after a
 * restart is byte-for-byte what it would have got before one.
 */
@Serializable
internal data class PersistedTask(
    val taskId: String,
    val owner: String = "",
    val label: String = "",
    val status: TaskStatus = TaskStatus.Failed,
    val statusMessage: String? = null,
    val createdAt: String = "",
    val lastUpdatedAt: String = "",
    val ttlMs: Long = 0,
    val result: JsonElement? = null,
    /**
     * [TaskRecordStore.instance] of the server that last wrote this record. Empty in a file written
     * before records were stamped, which reads as a server that is gone.
     */
    val instance: String = "",
)

/**
 * Persists task records so `tasks/get`, `tasks/result` and `tasks/list` still answer after the
 * server is restarted.
 *
 * Layout is one file per task under [dir] — `<cacheDir>/tasks/<taskId>.json` — mirroring the plain,
 * human-browsable directory tree `core/.../cache/OnDiskLibraryCache.kt` uses for libraries. Task
 * volume is tens of records with a one-hour TTL ceiling, so a file each is simpler than a database
 * and costs no dependency.
 *
 * **Corruption degrades to a miss**, as in the library cache: a file that will not parse is logged
 * and skipped, never fatal. A task record is not worth failing a server start over.
 *
 * ### Several servers, one directory
 *
 * Every Claude Code session launches its own stdio server, and they all default to the same cache
 * directory, so with `--tasks` several live servers can share [dir]. Each must recover only what a
 * server that is *gone* left behind: failing another live server's `working` record, or sweeping
 * its in-flight temp file, breaks a task that is still running.
 *
 * So each store has an [instance] id, stamped on every record and temp file it writes, and holds an
 * exclusive OS lock on `<instance>.lock` from [claim] until [close] — the same `FileChannel` lock
 * core's fetcher takes on a version directory. The OS drops the lock when the process dies, however
 * it dies, so a crashed server reads as gone the moment it is, with no heartbeat or timeout to
 * tune. [loadRecoverable] and [sweepTempFiles] skip whatever a live instance owns.
 *
 * A recovering server adopts a dead server's interrupted record by saving it, which re-stamps it;
 * its finished records keep the dead id, so every server started later can still reach them by id.
 *
 * All IO is caller-scheduled — [TaskStore] calls these off its own scope on [kotlinx.coroutines.Dispatchers.IO].
 *
 * `open` so a test can hold a [save] in flight: that is the only way to open, on demand, the window
 * between a task turning terminal in memory and reaching disk, which `TaskStore.close` must wait out.
 */
open class TaskRecordStore(private val dir: Path) : Closeable {

    private val log = Logger.withTag("TaskRecordStore")

    /** This server's id among those sharing [dir]; random, so never reused by a later server. */
    internal val instance: String = UUID.randomUUID().toString()

    /** Holds the lock on [instance]'s lock file while open; closing it releases the lock. */
    private var lock: FileChannel? = null
    private var closed = false

    /**
     * Tells every server sharing [dir] that this one is alive, by locking `<instance>.lock` until
     * [close]. [save] calls it before every write, so a record is never stamped by an instance that
     * has not claimed; it is idempotent, and a no-op once closed.
     *
     * A filesystem that cannot lock leaves this server looking gone to the others, which then
     * recover its records as every server did before instances existed. That is logged, not fatal.
     */
    @Synchronized
    private fun claim() {
        if (lock != null || closed) return
        runCatchingCancellable {
            dir.createDirectories()
            val channel = FileChannel.open(lockFile(instance), CREATE, WRITE)
            // A fresh random name, so nobody else can hold it; null would mean locking is broken.
            if (channel.tryLock() == null) channel.close() else lock = channel
        }.onFailure { log.w(it) { "Could not lock $dir; a server sharing it may recover this one's tasks" } }
    }

    /** Releases the claim: from here on this server reads as gone, so its records are recoverable. */
    @Synchronized
    override fun close() {
        closed = true
        val channel = lock ?: return
        lock = null
        runCatchingCancellable {
            channel.close()
            lockFile(instance).deleteIfExists()
        }.onFailure { log.d(it) { "Could not remove this server's lock file under $dir" } }
    }

    /**
     * Whether the server stamped [other] is still running: its lock file exists and is locked.
     *
     * A lock this JVM holds throws [OverlappingFileLockException] rather than returning `null`;
     * that is another store in this process, and just as live. A lock file nobody holds is a
     * crashed server's, and is removed here. Anything that is not a well-formed id (an unstamped or
     * pre-instance record, a legacy temp file named after its task, a tampered field) reads as gone
     * without touching the filesystem, so a record cannot point this at an arbitrary path.
     */
    private fun isLive(other: String): Boolean {
        if (other == instance) return true
        if (!INSTANCE_ID.matches(other)) return false
        val file = lockFile(other)
        return runCatchingCancellable {
            val channel = try {
                FileChannel.open(file, WRITE)
            } catch (_: NoSuchFileException) {
                return@runCatchingCancellable false
            }
            val acquired = channel.use {
                try {
                    it.tryLock() != null // released again as the channel closes
                } catch (_: OverlappingFileLockException) {
                    false
                }
            }
            if (acquired) file.deleteIfExists()
            !acquired
        }.onFailure { log.d(it) { "Could not tell whether server $other is running; treating it as gone" } }
            .getOrDefault(false)
    }

    /**
     * Writes [record], replacing any previous version.
     *
     * Written to a temp file and moved into place, so a crash mid-write cannot leave a half-written
     * record that would then be skipped as corrupt. `ATOMIC_MOVE` is not universally supported
     * (some network filesystems), hence the fallback — which is still strictly better than writing
     * the target in place.
     *
     * The temp file is uniquely named per save, the way core's `writeAtomically` names its own. A
     * fixed `<taskId>.json.tmp` let two saves of one task write into the same file at once —
     * interleaving their bytes, or moving it away from under each other. `TaskStore` also
     * serializes the saves of one task, so this is the second of two guards, not the only one.
     */
    internal open fun save(record: PersistedTask) {
        claim()
        runCatchingCancellable {
            dir.createDirectories()
            val target = fileFor(record.taskId)
            // Named for this instance first, so [sweepTempFiles] can tell whose it is.
            val temp = Files.createTempFile(dir, "$instance.${record.taskId}.", ".json.tmp")
            try {
                temp.writeText(McpJson.encodeToString(record.copy(instance = instance)))
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    log.d(e) { "Atomic move unavailable under $dir; falling back to a plain replace" }
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                // Gone already after a successful move; otherwise a failed save leaves no litter.
                temp.deleteIfExists()
            }
        }.onFailure {
            // Losing durability must never lose the task itself — the in-memory record is the
            // source of truth while the process lives.
            log.w(it) { "Could not persist task ${record.taskId}; it will not survive a restart" }
        }
    }

    /**
     * The records no running server owns: what a starting server may restore, fail and expire. See
     * the class KDoc. Each other instance is checked once.
     */
    internal fun loadRecoverable(): List<PersistedTask> {
        val live = HashMap<String, Boolean>()
        return loadAll().filterNot { live.getOrPut(it.instance) { isLive(it.instance) } }
    }

    /** Every readable record on disk, whoever owns it. Unparseable files are dropped, not raised. */
    internal fun loadAll(): List<PersistedTask> {
        if (!dir.exists()) return emptyList()
        return runCatchingCancellable {
            dir.listDirectoryEntries("*.json").mapNotNull { file ->
                runCatchingCancellable { McpJson.decodeFromString<PersistedTask>(file.readText()) }
                    .onFailure { log.w(it) { "Corrupt task record at $file; discarding" } }
                    .onFailure { runCatchingCancellable { file.deleteIfExists() } }
                    .getOrNull()
                    ?.takeIf { it.taskId == file.nameWithoutExtension }
            }
        }.onFailure { log.w(it) { "Could not list task records under $dir" } }.getOrDefault(emptyList())
    }

    /** Removes [taskId]'s record; a no-op if it was never written. */
    internal fun delete(taskId: String) {
        runCatchingCancellable { fileFor(taskId).deleteIfExists() }
            .onFailure { log.w(it) { "Could not delete task record $taskId" } }
    }

    /**
     * Clears any `.tmp` left by a crash mid-write, so they cannot accumulate — but only a gone
     * server's: a live one's may be a save in flight, and deleting it would fail that save.
     */
    internal fun sweepTempFiles() {
        runCatchingCancellable {
            if (!dir.exists()) return
            val live = HashMap<String, Boolean>()
            dir.listDirectoryEntries()
                .filter { it.extension == "tmp" }
                .filterNot { file ->
                    val owner = file.fileName.toString().substringBefore('.')
                    live.getOrPut(owner) { isLive(owner) }
                }
                .forEach { it.deleteIfExists() }
        }.onFailure { log.d(it) { "Could not sweep temp task records under $dir" } }
    }

    private fun fileFor(taskId: String): Path = dir.resolve("$taskId.json")

    private fun lockFile(instance: String): Path = dir.resolve("$instance.lock")

    private companion object {
        val INSTANCE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Encodes a finished tool result for [PersistedTask.result] through the on-the-wire JSON path. */
internal fun CallToolResult.toPersistedJson(): JsonObject =
    McpJson.encodeToJsonElement(CallToolResult.serializer(), this) as JsonObject

/** The inverse of [toPersistedJson]; `null` when the stored payload will not decode. */
internal fun JsonElement.toCallToolResultOrNull(): CallToolResult? =
    runCatchingCancellable { McpJson.decodeFromJsonElement(CallToolResult.serializer(), this) }.getOrNull()
