package app.oreshkov.kotlinlibmcp.server.tasks

import app.oreshkov.kotlinlibmcp.server.runCatchingCancellable
import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 * All IO is caller-scheduled — [TaskStore] calls these off its own scope on [kotlinx.coroutines.Dispatchers.IO].
 *
 * `open` so a test can hold a [save] in flight: that is the only way to open, on demand, the window
 * between a task turning terminal in memory and reaching disk, which `TaskStore.close` must wait out.
 */
open class TaskRecordStore(private val dir: Path) {

    private val log = Logger.withTag("TaskRecordStore")

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
        runCatchingCancellable {
            dir.createDirectories()
            val target = fileFor(record.taskId)
            val temp = Files.createTempFile(dir, "${record.taskId}.", ".json.tmp")
            try {
                temp.writeText(McpJson.encodeToString(record))
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

    /** Every readable record on disk. Unparseable files are dropped, not raised. */
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

    /** Clears any `.tmp` left by a crash mid-write, so they cannot accumulate. */
    internal fun sweepTempFiles() {
        runCatchingCancellable {
            if (!dir.exists()) return
            dir.listDirectoryEntries().filter { it.extension == "tmp" }.forEach { it.deleteIfExists() }
        }.onFailure { log.d(it) { "Could not sweep temp task records under $dir" } }
    }

    private fun fileFor(taskId: String): Path = dir.resolve("$taskId.json")
}

/** Encodes a finished tool result for [PersistedTask.result] through the on-the-wire JSON path. */
internal fun CallToolResult.toPersistedJson(): JsonObject =
    McpJson.encodeToJsonElement(CallToolResult.serializer(), this) as JsonObject

/** The inverse of [toPersistedJson]; `null` when the stored payload will not decode. */
internal fun JsonElement.toCallToolResultOrNull(): CallToolResult? =
    runCatchingCancellable { McpJson.decodeFromJsonElement(CallToolResult.serializer(), this) }.getOrNull()
