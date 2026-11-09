---
paths:
  - "server/src/**/tasks/**/*.kt"
  - "server/src/**/SessionSetup*.kt"
  - "server/src/**/transport/HttpTransport.kt"
---

# Tasks (`--tasks`)

**Tasks (SEP-1686)** are **opt-in** behind `--tasks`, on **both transports** (`tasks/TaskStore.kt`,
`tasks/TaskHandlers.kt`): `fetch_library` declares `execution.taskSupport: "optional"`, a
task-augmented `tools/call` returns a handle immediately, and `tasks/get`/`result`/`list`/`cancel`
plus `notifications/tasks/status` are served. The SDK ships the wire types but **no execution
engine** — `Server.handleCallTool` ignores `params.task` — so we replace the `tools/call` handler
via `Protocol.setRequestHandler`; the non-task path must stay identical to the SDK's, which
`TaskDispatchTest` pins. Task records are **persisted** to `<cacheDir>/tasks` (`tasks/TaskRecordStore.kt`)
and **owned by the session that created them**, so on HTTP each connection sees only its own. A
record recovered after a restart is *orphaned* — its session is gone, so it is reachable by exact
`taskId` from any caller but never returned by `tasks/list`; that split is the spec's model for a
server with no authorization context, and the reasoning is in the `TaskStore` KDoc. Several live
servers may share `<cacheDir>/tasks` (one per stdio session); each recovers only the records of a
server that is *gone*, told apart by a per-instance OS file lock (`TaskRecordStore` KDoc). A task still
running at shutdown ends up `failed` either way: `close()` marks and persists it on the calling
thread before cancelling the scope, and `restore()` maps a record a *crash* left `working` (so
`close()` never ran) the same way — leaving that to the cancelled job would race both the restart
and JVM exit. A task whose body needs client
input parks in `TaskStatus.InputRequired` and back: `TaskStore.start` puts a `TaskContext` in the
**coroutine context** (same trick as the OTel span) so a tool body can call `awaitingInput { }` without
any layer between it and the store knowing about tasks.

**Tasks gotcha:** `tasks/TaskHandlers.kt` **replaces** the SDK's `tools/call` handler (that is the
only way to answer with a `CreateTaskResult` — `Server.handleCallTool` is hard-typed to
`CallToolResult` and ignores `params.task`). The replacement therefore applies to *every* tool, so
its non-task branch must reproduce the SDK's semantics exactly: unknown tool → `isError` result,
`CancellationException` and `UrlElicitationRequiredException` re-thrown, anything else flattened to
`isError`. `TaskDispatchTest` pins this — if you touch `dispatchToolCall`, keep those tests green.
The `tasks` capability and the handlers are both driven by `ServerConfig.tasks` so they can never
disagree; advertising the capability without handlers makes the SDK route methods that answer
nothing.

**`io.modelcontextprotocol/related-task` goes where the message does not already name the task.**
`tasks/result` MUST carry it (its payload is the tool's own result), merged into whatever `_meta`
the tool set; so must server-to-client requests a task body makes (the elicitation). The
`tasks/get`/`list`/`cancel` results and `notifications/tasks/status` SHOULD NOT, because `taskId`
is already in them. `TaskHandlerInstallationTest` and `TaskDispatchTest` pin both directions.

**Registering them differs per transport**, because the SDK only sometimes hands us the session.
stdio calls `registerTaskHandlers` on the `ServerSession` `runStdioServer` owns. HTTP has no such
object — `mcpStreamableHttp` creates a session per connection internally — so it goes through
`installTaskHandlersOnEverySession`, which delegates to `onEachSession` (see `mcp-server.md`).

**Tasks are owned by a session.** One `TaskStore` serves every session, so every client-facing
operation takes the caller's `sessionId` as `owner` and filters by it — `dispatchToolCall` takes it
from `ClientConnection.sessionId`, the `tasks/…` handlers from `ServerSession.sessionId`, and they
are the same id by construction. Since kotlin-sdk 0.16.0 an idle HTTP session *expires*, which
strands its tasks, so `SESSION_IDLE_TIMEOUT` (`HttpTransport.kt`) must never drop below
`MAX_TTL_MS` — raise the TTL ceiling and the timeout follows; `HttpSessionLifetimeTest` pins it.
A `taskId` belonging to another session raises the *same*
`UnknownTaskException` as one that never existed; keep it that way, or the error becomes an oracle
for other sessions' ids. This is invisible with a single stdio client and load-bearing on HTTP,
where `mcpStreamableHttp` creates a session per connection and `tasks/result` returns whole tool
results. Never add an unscoped `list()`/`get()` back to `TaskStore`.

**The one exception is an orphan** — a record `TaskRecordStore` recovered from a previous process.
Its owning session died with that process, so strict binding would make every persisted task
permanently unreachable. `get`/`payload`/`cancel` therefore accept an orphan from any caller
presenting its exact `taskId`, while **`list` still never returns one**. That is the spec's model
where no authorization context exists ("receivers MUST generate cryptographically secure task IDs
with enough entropy"; "receivers that cannot identify requestors SHOULD NOT declare `tasks.list`" —
we can identify live requestors, and we never list what we cannot attribute). Keep both halves:
widening `list` to orphans, or dropping the bypass, each breaks a specific named test in
`TaskPersistenceTest`.

**Persistence ordering is load-bearing.** `update()` writes to disk *before* completing the
`finished` deferred that `tasks/result` awaits. Completing it first lets a client observe a terminal
task that a crash a moment later resurrects as `working`. Writes are synchronous on purpose —
launching them lets `close()` cancel the final state a task reaches during shutdown, which is
exactly the state a restart needs.

For the same reason **`close()` writes the terminal state itself**, marking every non-terminal record
`failed` on the calling thread *before* `scope.cancel()`. Left to the job's own
`CancellationException` path it lands from a `Dispatchers.Default` thread — after `close()` returned,
possibly after the JVM exited — so the same task came back `cancelled` or `failed` depending on
thread timing (a real CI flake). `TaskPersistenceTest` pins both halves: the graceful path through
`close()`, and the crash path (a record left `working` on disk, no `close()`) through `restore()`;
neither test may simulate a crash by calling `TaskStore.close()` (the crash test closes only the
`TaskRecordStore`, which drops the lock as the OS would). A record that is already terminal but whose
write is still in flight on the job's thread gets written by `close()` too (`!finished.isCompleted`),
so `close()` never returns ahead of a state another thread decided.

**Terminal is final, enforced in one place.** Every status write goes through `update(record, to)`,
which checks `canTransition(from, to)` *in the same critical section* as the write and does nothing
— no file, no notification — when it refuses. That one check is what keeps a body that finishes, or
throws while unwinding, from overwriting `cancelled` or `close()`'s `failed`, and keeps `close()` from
relabelling a task that completed a moment earlier. Never write `record.status` outside it, and never
split the "is it terminal?" check from the write. `tasks/cancel` marks the record *before* cancelling
the job ("MUST transition the task to cancelled status before sending the response"). Saves of one
record are serialized by `TaskRecord.writeLock`, with the snapshot taken *inside* it, so the last
save always carries the latest state. The job catches `Throwable`, records `failed`, then re-throws
only a fatal `VirtualMachineError` (not `StackOverflowError`). `TaskStoreRaceTest` races all of this
on real threads, and each of its tests has been seen to fail with its guard removed.

**Several live servers share one `tasks/` directory** — every stdio session launches its own on the
default cache dir — so "a previous process" means *a server that is gone*, never just "a record I did
not write". Each `TaskRecordStore` stamps its `instance` id on every record and temp file and holds an
OS `FileChannel` lock on `<instance>.lock` while open (claimed on first save, released by
`TaskStore.close()` *after* the final writes). `restore()` reads `loadRecoverable()`, not `loadAll()`,
and `sweepTempFiles()` skips a live instance's files: failing a live server's `working` record or
deleting its in-flight temp breaks a task that is still running. Inside one JVM a held lock throws
`OverlappingFileLockException` instead of returning `null`, and both mean *live*; the two paths are
pinned separately, the cross-process one by a test that kills a child JVM.
