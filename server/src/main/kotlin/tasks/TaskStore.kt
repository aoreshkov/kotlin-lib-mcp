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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * True for a record loaded from disk at startup. Its owning session died with the previous
     * process, so no live caller can ever match [owner] — see [TaskStore] for what that unlocks and
     * what it deliberately does not.
     */
    val orphaned: Boolean = false,
) {
    var status: TaskStatus = TaskStatus.Working
    var statusMessage: String? = null
    var lastUpdatedAt: Instant = createdAt
    var result: CallToolResult? = null
    var job: Job? = null

    /**
     * Completed as soon as the record reaches a terminal status. `tasks/result` awaits it, which is
     * how the spec's "MUST block the response until the task reaches a terminal status" is served
     * without polling.
     */
    val finished: CompletableDeferred<Unit> = CompletableDeferred()

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

    /** The on-disk form of the current state. Call under the record's monitor. */
    fun persisted(): PersistedTask = PersistedTask(
        taskId = taskId,
        owner = owner,
        label = label,
        status = status,
        statusMessage = statusMessage,
        createdAt = ISO.format(createdAt),
        lastUpdatedAt = ISO.format(lastUpdatedAt),
        ttlMs = ttlMs,
        result = result?.toPersistedJson(),
    )
}

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/** Raised for `tasks/…` requests naming a task this store cannot serve; mapped to a JSON-RPC error. */
internal class UnknownTaskException(taskId: String) :
    Exception("Unknown or expired task '$taskId'")

/** Raised by `tasks/result` when a terminal task has no payload to hand back. */
internal class TaskNotReadyException(taskId: String, status: TaskStatus) :
    Exception("Task '$taskId' ended as $status without producing a result")

/**
 * Raised by `tasks/cancel` for a task that already finished. The spec makes this an error rather
 * than a no-op: "Receivers MUST reject cancellation requests for tasks already in a terminal status
 * (completed, failed, or cancelled) with error code -32602 (Invalid params)."
 */
internal class TaskAlreadyTerminalException(taskId: String, status: TaskStatus) :
    Exception("Cannot cancel task '$taskId': already in terminal status '${status.name.lowercase()}'")

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
 * Registry of running and recently-finished tasks, optionally persisted so they survive a restart.
 *
 * **Tasks belong to the session that created them.** Every client-facing operation takes the
 * caller's `sessionId` as `owner` and will not see another session's records: a `taskId` belonging
 * to someone else is reported as [UnknownTaskException], exactly as an id that never existed, so
 * the error cannot be used to probe for other sessions' tasks. That scoping is what makes
 * `tasks/list` safe, since it would otherwise enumerate every client's work — and `tasks/result`
 * payloads are full tool results.
 *
 * With one stdio client this is invisible. It matters the moment more than one session exists,
 * which is the normal case for the Streamable HTTP transport (`mcpStreamableHttp` creates a session
 * per connection).
 *
 * ### Tasks that outlive their session
 *
 * A [recordStore] makes records durable. But a session id is a per-connection UUID, so after a
 * restart the client reconnects with a new one and *no* live caller can ever match a restored
 * record's [owner]. Binding strictly would make every persisted task permanently unreachable, which
 * would defeat the point of persisting it.
 *
 * Restored records are therefore marked orphaned, and the rule splits:
 *
 * - **`tasks/list` never returns an orphan.** Only the caller's own live-session tasks, as before.
 * - **`tasks/get`/`result`/`cancel` accept an orphan from any caller that presents its exact id.**
 *
 * This is the model the spec prescribes where no authorization context exists — as here, a
 * loopback-first server with no auth: *"If context-binding is unavailable, receivers MUST generate
 * cryptographically secure task IDs with enough entropy to prevent guessing."* Ids come from
 * [UUID.randomUUID], which is `SecureRandom`-backed (122 bits). Live sessions keep the stronger
 * guarantee; the relaxation applies only to records that would otherwise be dead weight.
 *
 * ### Scope limit
 *
 * Persistence is per-process-tree, not shared: a second replica reading a different directory
 * cannot answer `tasks/get` for a task this one started. Multi-replica needs shared storage *and* a
 * real authorization context, neither of which this server has.
 */
class TaskStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Instant = Instant::now,
    private val recordStore: TaskRecordStore? = null,
) : Closeable {

    private val records = ConcurrentHashMap<String, TaskRecord>()

    init {
        recordStore?.let { restore(it) }
    }

    /**
     * Loads persisted records at startup.
     *
     * Anything still `working` or `input_required` was interrupted by the shutdown — its coroutine
     * is gone and nothing will ever finish it, so it is moved to `failed`. That is a legal
     * transition (only terminal states may not move) and it matters for more than tidiness: a
     * restored `working` record would make `tasks/result` block until the TTL expired, waiting on a
     * task that cannot complete.
     */
    private fun restore(store: TaskRecordStore) {
        store.sweepTempFiles()
        val cutoff = now()
        var recovered = 0
        var interrupted = 0
        for (persisted in store.loadAll()) {
            val createdAt = runCatching { Instant.parse(persisted.createdAt) }.getOrNull()
            if (createdAt == null || createdAt.plusMillis(persisted.ttlMs) < cutoff) {
                store.delete(persisted.taskId)
                continue
            }
            val wasRunning = persisted.status == TaskStatus.Working ||
                persisted.status == TaskStatus.InputRequired
            val record = TaskRecord(
                taskId = persisted.taskId,
                owner = persisted.owner,
                label = persisted.label,
                createdAt = createdAt,
                ttlMs = persisted.ttlMs,
                onStatus = {}, // the session that wanted the notifications is gone
                orphaned = true,
            ).apply {
                status = if (wasRunning) TaskStatus.Failed else persisted.status
                statusMessage =
                    if (wasRunning) "Server restarted while this task was running" else persisted.statusMessage
                lastUpdatedAt = runCatching { Instant.parse(persisted.lastUpdatedAt) }.getOrDefault(createdAt)
                result = persisted.result?.toCallToolResultOrNull()
                // Everything restored is terminal, so no tasks/result call can park on it.
                finished.complete(Unit)
            }
            records[record.taskId] = record
            recovered++
            if (wasRunning) {
                interrupted++
                store.save(record.persisted())
            }
        }
        if (recovered > 0) {
            log.i { "Recovered $recovered task(s) from disk ($interrupted interrupted by the restart)" }
        }
    }

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
        // Before the body runs, so a crash mid-fetch still leaves a record to recover as `failed`
        // rather than a task the client holds an id for and the server has never heard of.
        persist(record)
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

    /**
     * [owner]'s live tasks, newest first (`tasks/list`) — never another session's, and **never an
     * orphan**: a record restored from a previous process cannot be attributed to any live caller,
     * and enumerating it would hand its metadata to whoever asked first.
     */
    fun list(owner: String): List<Task> {
        sweepExpired()
        return records.values
            .filter { it.owner == owner && !it.orphaned }
            .map { synchronized(it) { it.snapshot() } }
            .sortedByDescending { it.createdAt }
    }

    /**
     * The finished tool result (`tasks/result`). A task that `failed` because the tool reported
     * `isError` still has its payload: the spec requires `tasks/result` to return exactly what the
     * underlying request would have returned, so the error content is returned rather than raised.
     */
    suspend fun payload(owner: String, taskId: String): CallToolResult {
        val record = withRecord(owner, taskId) { it }
        if (!record.finished.isCompleted) {
            // "When a receiver receives a tasks/result request for a task in any other non-terminal
            // status (working or input_required), it MUST block the response until the task reaches
            // a terminal status." Bounded by what is left of the TTL: past that the record may be
            // swept, and nothing would ever complete the wait.
            val budget = remainingTtl(record)
            if (withTimeoutOrNull(budget) { record.finished.await() } == null) {
                throw TaskNotReadyException(taskId, synchronized(record) { record.status })
            }
        }
        return synchronized(record) {
            // Terminal with no payload: cancelled, or a body that threw outside `invokeTool`. The
            // spec allows tasks/result to answer with a JSON-RPC error in exactly that case.
            record.result ?: throw TaskNotReadyException(taskId, record.status)
        }
    }

    /** Milliseconds left before [record] may be swept; never negative. */
    private fun remainingTtl(record: TaskRecord): Long =
        (record.createdAt.toEpochMilli() + record.ttlMs - now().toEpochMilli()).coerceAtLeast(0)

    /**
     * Cancels [taskId] (`tasks/cancel`), returning the resulting state. Cancelling the job unwinds
     * the tool body through the `CancellationException` that `guarded` already re-throws, so the
     * in-flight download/analysis stops rather than running on unobserved.
     *
     * @throws TaskAlreadyTerminalException if the task already finished — the spec requires an
     * error here, not a no-op, so a client that raced the completion learns which way it went.
     */
    fun cancel(owner: String, taskId: String): Task {
        val record = withRecord(owner, taskId) { it }
        val job = synchronized(record) {
            if (record.terminal) throw TaskAlreadyTerminalException(taskId, record.status) else record.job
        }
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

    /**
     * Ends every in-flight task; called from `McpServerHandle.close()`.
     *
     * Each one is moved to `failed` **here, on the calling thread, before the scope is cancelled**,
     * so the terminal state is on disk by the time this returns. Leaving it to the job's own
     * `CancellationException` path would publish it from a [Dispatchers.Default] thread, racing both
     * the restart that reads the file and the JVM exit that may cut the write short — the same task
     * would then come back as `cancelled` or `failed` depending on thread timing. Marking first also
     * means that path finds the record already terminal and leaves it alone.
     *
     * `failed` rather than `cancelled`: the spec's `cancelled` means the requestor asked, and nobody
     * did — the server went away. It matches what [restore] does for a record a hard crash left
     * `working`, with a different message so the two provenances stay distinguishable.
     *
     * [TaskRun.onStatus] is deliberately not fired: it is `suspend`, and the transport it would
     * notify over is being torn down in the same breath.
     */
    override fun close() {
        records.values.forEach { record ->
            if (synchronized(record) { record.terminal }) return@forEach
            update(record) {
                record.status = TaskStatus.Failed
                record.statusMessage = "Server shut down while this task was running"
            }
        }
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
        persist(record)
        runCatching { record.onStatus(snapshot) }
    }

    /**
     * Applies [mutate] under the record's monitor and returns the resulting snapshot, releasing any
     * `tasks/result` call parked on [TaskRecord.finished] if the change was terminal. Every path
     * that can end a task goes through here, so no terminal transition can forget to unblock.
     */
    private fun update(record: TaskRecord, mutate: () -> Unit): Task {
        val (snapshot, terminal) = synchronized(record) {
            mutate()
            record.lastUpdatedAt = now()
            record.snapshot() to record.terminal
        }
        persist(record)
        // Released only *after* the new state is on disk. Completing it inside the lock would let a
        // client observe a finished task through tasks/result that a crash a moment later would
        // resurrect as `working` — the one inconsistency persistence exists to prevent.
        if (terminal) record.finished.complete(Unit)
        return snapshot
    }

    /**
     * Writes [record]'s current state to disk.
     *
     * **Synchronous, deliberately.** Launching the write instead would be cancelled by [close] —
     * losing exactly the final state a task reaches during shutdown, which is the state a restart
     * most needs — and would leave two saves free to land out of order. Records are a few KB and
     * written only on state changes (four per task in the common case), so the cost is a couple of
     * milliseconds on a path that is already doing network and disk work.
     *
     * Called outside the record's monitor. Failures are swallowed and logged by the record store:
     * durability is a bonus, and losing it must never fail a task the client is waiting on.
     */
    private fun persist(record: TaskRecord) {
        val store = recordStore ?: return
        store.save(synchronized(record) { record.persisted() })
    }

    /**
     * Resolves a client-supplied [taskId] for [owner].
     *
     * Accepts the record if the caller owns it, **or** if it is an orphan from a previous process —
     * see the class KDoc for why that fallback exists and why `tasks/list` does not share it.
     *
     * A record owned by a different *live* session raises the *same* [UnknownTaskException] as a
     * missing one, deliberately: distinguishing "not yours" from "not here" would turn `tasks/get`
     * into an oracle for other sessions' task ids.
     */
    private fun <T> withRecord(owner: String, taskId: String, block: (TaskRecord) -> T): T {
        sweepExpired()
        val record = records[taskId]?.takeIf { it.owner == owner || it.orphaned }
            ?: throw UnknownTaskException(taskId)
        return block(record)
    }

    /**
     * Drops finished records past their TTL. Running tasks are never swept: the client is still
     * waiting on them, and evicting one would strand work that is genuinely in flight.
     */
    private fun sweepExpired() {
        val cutoff = now()
        val expired = mutableListOf<String>()
        records.values.removeAll { record ->
            synchronized(record) {
                (record.terminal && record.createdAt.plusMillis(record.ttlMs) < cutoff)
                    .also { if (it) expired += record.taskId }
            }
        }
        if (expired.isEmpty()) return
        val store = recordStore ?: return
        // The spec lets a receiver delete a task and its results once the TTL elapses; dropping the
        // file too is what keeps a restart from resurrecting what this process just swept.
        expired.forEach { store.delete(it) }
    }

    private fun effectiveTtl(requested: Long?): Long =
        (requested ?: DEFAULT_TTL_MS).coerceIn(1L, MAX_TTL_MS)
}
