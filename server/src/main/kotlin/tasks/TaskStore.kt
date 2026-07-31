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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * [owner] is the `sessionId` of the MCP session that created the task, and is what every
 * client-facing lookup is filtered by — see [TaskStore].
 */
private class TaskRecord(
    val taskId: String,
    val owner: String,
    val label: String,
    val createdAt: Instant,
    val ttlMs: Long,
    val onStatus: suspend (Task) -> Unit,
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
 * The running task, carried in the coroutine context so a tool body can report that it is waiting
 * on the client without being written against tasks at all.
 *
 * A context element rather than a parameter for the same reason the OTel span is one
 * (`telemetry/Telemetry.kt`): it has to cross `dispatchToolCall` → `guarded` → the tool → whatever
 * the tool calls, including the `withContext(Dispatchers.Default)` hop inside `fetchLibrary`,
 * without any of those signatures growing a task argument.
 *
 * Absent from the context means "not running as a task" — the plain synchronous `tools/call` path.
 */
class TaskContext internal constructor(
    val taskId: String,
    private val store: TaskStore,
) : AbstractCoroutineContextElement(TaskContext) {

    companion object Key : CoroutineContext.Key<TaskContext>

    /**
     * Runs [block] with the task parked in `input_required`, returning it to `working` afterwards —
     * the spec's `working ⟷ input_required` leg, which tells a polling client to open
     * `tasks/result` and pick up the server's question.
     *
     * The restore is in a `finally` so a declined, failed or cancelled elicitation cannot strand
     * the task in `input_required` forever.
     */
    suspend fun <T> awaitingInput(message: String, block: suspend () -> T): T {
        store.markInputRequired(taskId, message)
        return try {
            block()
        } finally {
            withContext(NonCancellable) { store.markWorking(taskId) }
        }
    }
}

/**
 * In-memory registry of running and recently-finished tasks.
 *
 * **Tasks belong to the session that created them.** Every client-facing operation takes the
 * caller's `sessionId` as `owner` and will not see another session's records: a `taskId` belonging
 * to someone else is reported as [UnknownTaskException], exactly as an id that never existed, so
 * the error cannot be used to probe for other sessions' tasks. Task ids are unguessable UUIDs
 * anyway; the scoping is what makes `tasks/list` safe, since it would otherwise enumerate every
 * client's work — and `tasks/result` payloads are full tool results.
 *
 * With one stdio client this is invisible. It matters the moment more than one session exists,
 * which is the normal case for the Streamable HTTP transport (`mcpStreamableHttp` creates a session
 * per connection).
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
     * Registers a task owned by [owner], launches [TaskRun.block] on this store's own scope, and
     * returns the initial `working` snapshot for an immediate `CreateTaskResult`.
     *
     * The work is deliberately **not** launched in the caller's coroutine: the `tools/call` response
     * must go back before the tool finishes, which is the whole point of a task.
     */
    fun start(owner: String, run: TaskRun): Task {
        sweepExpired()
        val record = TaskRecord(
            taskId = UUID.randomUUID().toString(),
            owner = owner,
            label = run.label,
            createdAt = now(),
            ttlMs = effectiveTtl(run.requested?.ttl),
            onStatus = run.onStatus,
        )
        records[record.taskId] = record
        val initial = synchronized(record) { record.snapshot() }
        log.i { "Task ${record.taskId} started (${run.label})" }

        record.job = scope.launch {
            // The first status is published from inside the job so a caller that awaits the very
            // first notification cannot race the CreateTaskResult it is about to receive.
            runCatching { run.onStatus(initial) }
            val outcome = try {
                // The body runs with a [TaskContext] in scope so that work which needs something
                // from the client — an elicitation — can say so without any of the layers in
                // between (`dispatchToolCall`, `guarded`, the tool itself) knowing about tasks.
                Outcome.Done(withContext(TaskContext(record.taskId, this@TaskStore)) { run.block() })
            } catch (e: CancellationException) {
                Outcome.Cancelled
            } catch (e: Exception) {
                Outcome.Failed(e)
            }
            publish(record, outcome, run.onStatus)
        }
        return initial
    }

    /** Current state of [owner]'s [taskId] (`tasks/get`). */
    fun get(owner: String, taskId: String): Task =
        withRecord(owner, taskId) { synchronized(it) { it.snapshot() } }

    /** [owner]'s live tasks, newest first (`tasks/list`) — never another session's. */
    fun list(owner: String): List<Task> {
        sweepExpired()
        return records.values
            .filter { it.owner == owner }
            .map { synchronized(it) { it.snapshot() } }
            .sortedByDescending { it.createdAt }
    }

    /**
     * The finished tool result (`tasks/result`). A task that `failed` because the tool reported
     * `isError` still has its payload: the spec requires `tasks/result` to return exactly what the
     * underlying request would have returned, so the error content is returned rather than raised.
     */
    fun payload(owner: String, taskId: String): CallToolResult = withRecord(owner, taskId) { record ->
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
    fun cancel(owner: String, taskId: String): Task {
        val record = withRecord(owner, taskId) { it }
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

    /**
     * Reports that the task is blocked on something only the client can supply, per the spec's
     * `input_required` status: the requestor is expected to notice it and call `tasks/result`, on
     * whose stream the server-to-client request (an `elicitation/create`) is delivered.
     *
     * A no-op for a task that has already finished — a client cancelling at exactly the moment the
     * body asks a question is a race the store must absorb, not a state-machine violation.
     */
    internal suspend fun markInputRequired(taskId: String, message: String) {
        transition(taskId, TaskStatus.InputRequired, message)
    }

    /** Back to [TaskStatus.Working] once the client answered; the return leg of [markInputRequired]. */
    internal suspend fun markWorking(taskId: String) {
        transition(taskId, TaskStatus.Working, null)
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
                    // A tool that reported isError did not complete successfully: 2025-11-25 is
                    // explicit that "for tool calls specifically, this includes cases where the
                    // tool call result has isError set to true" → `failed`. The result is still
                    // kept, because tasks/result must return exactly what the underlying request
                    // would have returned — the error content the model needs to read and act on.
                    val failed = outcome.result.isError == true
                    record.status = if (failed) TaskStatus.Failed else TaskStatus.Completed
                    record.result = outcome.result
                    record.statusMessage = if (failed) "The tool reported an error" else null
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

    /**
     * Moves a live task between the two non-terminal states and publishes the change. Terminal
     * records are left alone: `completed`/`failed`/`cancelled` **MUST NOT** transition to anything.
     */
    private suspend fun transition(taskId: String, status: TaskStatus, message: String?) {
        val record = records[taskId] ?: return
        val snapshot = synchronized(record) {
            if (record.terminal || record.status == status) return
            record.status = status
            record.statusMessage = message
            record.lastUpdatedAt = now()
            record.snapshot()
        }
        runCatching { record.onStatus(snapshot) }
    }

    private fun update(record: TaskRecord, mutate: () -> Unit): Task = synchronized(record) {
        mutate()
        record.lastUpdatedAt = now()
        record.snapshot()
    }

    /**
     * Resolves a client-supplied [taskId] within [owner]'s tasks.
     *
     * A record owned by a different session raises the *same* [UnknownTaskException] as a missing
     * one, deliberately: distinguishing "not yours" from "not here" would turn `tasks/get` into an
     * oracle for other sessions' task ids.
     */
    private fun <T> withRecord(owner: String, taskId: String, block: (TaskRecord) -> T): T {
        sweepExpired()
        val record = records[taskId]?.takeIf { it.owner == owner } ?: throw UnknownTaskException(taskId)
        return block(record)
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
