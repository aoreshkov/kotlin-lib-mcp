package app.oreshkov.kotlinlibmcp.server.tasks

import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Task
import io.modelcontextprotocol.kotlin.sdk.types.TaskMetadata
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import java.io.Closeable
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/*
 * Task-augmented execution (SEP-1686) for tool calls.
 *
 * The MCP SDK ships the wire types and gates the `tasks/…` methods on the capability, but provides
 * no execution engine: `Server.handleCallTool` ignores `CallToolRequestParams.task` and always
 * answers with a `CallToolResult`. This is that engine — the piece between "the client asked for a
 * task" and "the tool eventually produced a result".
 *
 * Deliberately free of MCP transport concerns: it takes a suspend block and hands back status
 * transitions through [TaskRun.onStatus]. `TaskHandlers.kt` owns the JSON-RPC side, which keeps the
 * lifecycle unit-testable without a session.
 */

/** Retention when the caller does not request one. */
private const val DEFAULT_TTL_MS = 10 * 60 * 1000L

/**
 * Ceiling on retention, whatever the caller asks for. Every record is held in memory, and a task is
 * only as useful as the process that owns it, so an unbounded (or `null` = unlimited) TTL would just
 * be a leak with extra steps.
 */
private const val MAX_TTL_MS = 60 * 60 * 1000L

/**
 * Suggested `tasks/get` cadence. A `fetch_library` runs seconds→tens of seconds, so sub-second
 * polling would only burn round-trips.
 */
private const val POLL_INTERVAL_MS = 1_000L

private val log = Logger.withTag("TaskStore")

/** How a caller describes the work to run; [onStatus] fires on every transition, including the first. */
class TaskRun(
    val label: String,
    val requested: TaskMetadata?,
    val onStatus: suspend (Task) -> Unit = {},
    val block: suspend () -> CallToolResult,
)

/**
 * One tracked task. [status]/[statusMessage]/[lastUpdatedAt] change as the work progresses; the rest
 * is fixed at creation. Guarded by the record's own monitor so [snapshot] never observes a torn state.
 */
private class TaskRecord(
    val taskId: String,
    val label: String,
    val createdAt: Instant,
    val ttlMs: Long,
) {
    var status: TaskStatus = TaskStatus.Working
    var statusMessage: String? = null
    var lastUpdatedAt: Instant = createdAt
    var result: CallToolResult? = null
    var job: Job? = null

    val terminal: Boolean get() = status != TaskStatus.Working && status != TaskStatus.InputRequired

    fun snapshot(): Task = Task(
        taskId = taskId,
        status = status,
        statusMessage = statusMessage,
        createdAt = ISO.format(createdAt),
        lastUpdatedAt = ISO.format(lastUpdatedAt),
        ttl = ttlMs,
        pollInterval = POLL_INTERVAL_MS,
    )
}

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/** Raised for `tasks/…` requests naming a task this store cannot serve; mapped to a JSON-RPC error. */
internal class UnknownTaskException(taskId: String) :
    Exception("Unknown or expired task '$taskId'")

/** Raised by `tasks/result` before the task reaches a terminal state. */
internal class TaskNotReadyException(taskId: String, status: TaskStatus) :
    Exception("Task '$taskId' is still $status — poll tasks/get until it reports 'completed'")

/**
 * In-memory registry of running and recently-finished tasks.
 *
 * **Scope limit:** records live in this process only. A restart, or a second replica, cannot answer
 * `tasks/get` for a task started elsewhere. The expensive half of the work *is* already durable —
 * the on-disk library cache — so a warm coordinate replays as an all-but-instantly `Completed`
 * task; making the records themselves durable is a separate change.
 */
class TaskStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Instant = Instant::now,
) : Closeable {

    private val records = ConcurrentHashMap<String, TaskRecord>()

    /**
     * Registers a task, launches [TaskRun.block] on this store's own scope, and returns the initial
     * `working` snapshot for an immediate `CreateTaskResult`.
     *
     * The work is deliberately **not** launched in the caller's coroutine: the `tools/call` response
     * must go back before the tool finishes, which is the whole point of a task.
     */
    fun start(run: TaskRun): Task {
        sweepExpired()
        val record = TaskRecord(
            taskId = UUID.randomUUID().toString(),
            label = run.label,
            createdAt = now(),
            ttlMs = effectiveTtl(run.requested?.ttl),
        )
        records[record.taskId] = record
        val initial = synchronized(record) { record.snapshot() }
        log.i { "Task ${record.taskId} started (${run.label})" }

        record.job = scope.launch {
            // The first status is published from inside the job so a caller that awaits the very
            // first notification cannot race the CreateTaskResult it is about to receive.
            runCatching { run.onStatus(initial) }
            val outcome = try {
                Outcome.Done(run.block())
            } catch (e: CancellationException) {
                Outcome.Cancelled
            } catch (e: Exception) {
                Outcome.Failed(e)
            }
            publish(record, outcome, run.onStatus)
        }
        return initial
    }

    /** Current state of [taskId] (`tasks/get`). */
    fun get(taskId: String): Task = withRecord(taskId) { synchronized(it) { it.snapshot() } }

    /** Every live task, newest first (`tasks/list`). */
    fun list(): List<Task> {
        sweepExpired()
        return records.values
            .map { synchronized(it) { it.snapshot() } }
            .sortedByDescending { it.createdAt }
    }

    /**
     * The finished tool result (`tasks/result`). A tool that reported `isError` still *completed* —
     * the payload carries the error, so it is returned rather than raised.
     */
    fun payload(taskId: String): CallToolResult = withRecord(taskId) { record ->
        synchronized(record) {
            record.result ?: throw TaskNotReadyException(taskId, record.status)
        }
    }

    /**
     * Cancels [taskId] (`tasks/cancel`), returning the resulting state. Cancelling the job unwinds
     * the tool body through the `CancellationException` that `guarded` already re-throws, so the
     * in-flight download/analysis stops rather than running on unobserved. Terminal tasks are
     * returned unchanged — cancelling a finished task is a no-op, not an error.
     */
    fun cancel(taskId: String): Task {
        val record = withRecord(taskId) { it }
        val job = synchronized(record) { if (record.terminal) return record.snapshot() else record.job }
        job?.cancel()
        // The job's own CancellationException path publishes `cancelled`; report it now so the
        // response does not depend on that coroutine having been rescheduled yet.
        return update(record) {
            if (!record.terminal) {
                record.status = TaskStatus.Cancelled
                record.statusMessage = "Cancelled by client request"
            }
        }
    }

    /** Cancels every in-flight task; called from `McpServerHandle.close()`. */
    override fun close() {
        scope.cancel()
        records.clear()
    }

    // --- internals ---

    private sealed interface Outcome {
        class Done(val result: CallToolResult) : Outcome
        class Failed(val error: Exception) : Outcome
        data object Cancelled : Outcome
    }

    private suspend fun publish(record: TaskRecord, outcome: Outcome, onStatus: suspend (Task) -> Unit) {
        val snapshot = update(record) {
            when (outcome) {
                is Outcome.Done -> {
                    record.status = TaskStatus.Completed
                    record.result = outcome.result
                    record.statusMessage =
                        if (outcome.result.isError == true) "Completed with a tool error" else null
                }
                is Outcome.Failed -> {
                    record.status = TaskStatus.Failed
                    record.statusMessage = outcome.error.message ?: outcome.error.toString()
                    log.w { "Task ${record.taskId} (${record.label}) failed: ${record.statusMessage}" }
                }
                // Set by `cancel` already in the common case; repeated for a cancellation that
                // originated inside the tool body rather than from tasks/cancel.
                Outcome.Cancelled -> if (!record.terminal) {
                    record.status = TaskStatus.Cancelled
                }
            }
        }
        // A client that has already disconnected must not turn into a failed task.
        runCatching { onStatus(snapshot) }
    }

    private fun update(record: TaskRecord, mutate: () -> Unit): Task = synchronized(record) {
        mutate()
        record.lastUpdatedAt = now()
        record.snapshot()
    }

    private fun <T> withRecord(taskId: String, block: (TaskRecord) -> T): T {
        sweepExpired()
        return block(records[taskId] ?: throw UnknownTaskException(taskId))
    }

    /**
     * Drops finished records past their TTL. Running tasks are never swept: the client is still
     * waiting on them, and evicting one would strand work that is genuinely in flight.
     */
    private fun sweepExpired() {
        val cutoff = now()
        records.values.removeAll { record ->
            synchronized(record) {
                record.terminal && record.createdAt.plusMillis(record.ttlMs) < cutoff
            }
        }
    }

    private fun effectiveTtl(requested: Long?): Long =
        (requested ?: DEFAULT_TTL_MS).coerceIn(1L, MAX_TTL_MS)
}
