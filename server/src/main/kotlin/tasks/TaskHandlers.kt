package app.oreshkov.kotlinlibmcp.server.tasks

import app.oreshkov.kotlinlibmcp.server.telemetry.METHOD_TASKS_CANCEL
import app.oreshkov.kotlinlibmcp.server.telemetry.METHOD_TASKS_GET
import app.oreshkov.kotlinlibmcp.server.telemetry.METHOD_TASKS_LIST
import app.oreshkov.kotlinlibmcp.server.telemetry.METHOD_TASKS_RESULT
import app.oreshkov.kotlinlibmcp.server.telemetry.taskSpan
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CancelTaskRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateTaskResult
import io.modelcontextprotocol.kotlin.sdk.types.GetTaskPayloadRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetTaskPayloadResult
import io.modelcontextprotocol.kotlin.sdk.types.GetTaskRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetTaskResult
import io.modelcontextprotocol.kotlin.sdk.types.ListTasksRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListTasksResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RELATED_TASK_META_KEY
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ServerResult
import io.modelcontextprotocol.kotlin.sdk.types.Task
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatusNotification
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatusNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.UrlElicitationRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/*
 * The JSON-RPC surface of task-augmented execution (SEP-1686), layered onto an SDK that ships the
 * types but not the engine.
 *
 * Two things are registered on the session:
 *  - the four `tasks/…` methods, for which the SDK installs no handler at all (`ServerSession` only
 *    *asserts* the capability for them);
 *  - a replacement `tools/call`, because `Server.handleCallTool` ignores `params.task` and its
 *    return type is fixed to `CallToolResult`, leaving no way to answer with a `CreateTaskResult`.
 *
 * `Protocol.setRequestHandler` is documented to replace any previous handler for a method, which is
 * what makes the second point work without forking the SDK.
 */

/**
 * Installs task support on [session]. Call once per session, after `Server.createSession`.
 *
 * Only registered for the stdio transport today: `runStdioServer` owns the `ServerSession`, whereas
 * the HTTP transport hands session creation to the SDK's `mcpStreamableHttp`, which exposes no
 * per-session hook. The capability is advertised to match (see `serverCapabilities`).
 */
fun ServerSession.registerTaskHandlers(server: Server, store: TaskStore) {
    // Replaces the SDK's own tools/call handler; see [dispatchToolCall] for the passthrough contract.
    setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
        dispatchToolCall(server.tools, server.clientConnection(sessionId), store, request)
    }

    setRequestHandler<GetTaskRequest>(Method.Defined.TasksGet) { request, _ ->
        taskSpan(METHOD_TASKS_GET, sessionId, request.params.meta) {
            mapTaskErrors { store.get(request.taskId).asGetTaskResult() }
        }
    }

    setRequestHandler<GetTaskPayloadRequest>(Method.Defined.TasksResult) { request, _ ->
        taskSpan(METHOD_TASKS_RESULT, sessionId, request.params.meta) {
            mapTaskErrors {
                // tasks/result returns the original request's result shape verbatim — for a
                // tools/call task that is a CallToolResult, re-encoded as the raw payload object.
                GetTaskPayloadResult(McpJson.encodeToJsonElement(store.payload(request.taskId)) as JsonObject)
            }
        }
    }

    setRequestHandler<ListTasksRequest>(Method.Defined.TasksList) { request, _ ->
        taskSpan(METHOD_TASKS_LIST, sessionId, request.params?.meta) {
            // The store is process-local and self-expiring, so the whole list fits one page.
            ListTasksResult(tasks = store.list(), nextCursor = null)
        }
    }

    setRequestHandler<CancelTaskRequest>(Method.Defined.TasksCancel) { request, _ ->
        taskSpan(METHOD_TASKS_CANCEL, sessionId, request.params.meta) {
            mapTaskErrors { store.cancel(request.taskId).asGetTaskResult() }
        }
    }
}

/**
 * `tools/call`, with a task-augmented branch in front of the SDK's behavior.
 *
 * The non-task path **must** stay byte-for-byte what `Server.handleCallTool` does, since this
 * handler replaces it for every tool, not just the task-capable ones: unknown tool becomes an
 * `isError` result rather than a protocol error, `CancellationException` and
 * `UrlElicitationRequiredException` propagate untouched, and any other exception is flattened into
 * an `isError` result. `TaskDispatchTest` pins this.
 *
 * Takes the tool map and connection rather than the `Server`/`ServerSession` so the whole contract
 * is reachable from a test without standing up a transport.
 */
internal suspend fun dispatchToolCall(
    tools: Map<String, RegisteredTool>,
    connection: ClientConnection,
    store: TaskStore,
    request: CallToolRequest,
): ServerResult {
    val registered = tools[request.name]
        ?: return CallToolResult(
            content = listOf(TextContent("Tool ${request.name} not found")),
            isError = true,
        )

    return if (request.task != null && registered.acceptsTasks) {
        startTask(store, connection, registered, request)
    } else {
        invokeTool(connection, registered, request)
    }
}

/**
 * Whether a tool opted into task-augmented execution. Absent `execution` means
 * [TaskSupport.Forbidden] per the spec, in which case a `task`-bearing request is served
 * synchronously rather than rejected — the field is a hint about what the tool *supports*, and
 * degrading to a normal call is friendlier than failing.
 */
private val RegisteredTool.acceptsTasks: Boolean
    get() = tool.execution?.taskSupport.let { it == TaskSupport.Optional || it == TaskSupport.Required }

/** Hands the call to [TaskStore] and answers immediately with the `working` handle. */
private fun startTask(
    store: TaskStore,
    connection: ClientConnection,
    registered: RegisteredTool,
    request: CallToolRequest,
): CreateTaskResult {
    val task = store.start(
        TaskRun(
            label = request.name,
            requested = request.task,
            onStatus = { connection.sendTaskStatus(it) },
            // The tool body is unchanged and unaware it is running as a task: it still emits
            // notifications/progress for any progressToken the client sent, so a client that polls
            // tasks/get and one that watches progress both stay informed.
            block = { invokeTool(connection, registered, request) },
        )
    )
    return CreateTaskResult(task = task)
}

/** The SDK's tool-invocation semantics, reproduced exactly. */
private suspend fun invokeTool(
    connection: ClientConnection,
    registered: RegisteredTool,
    request: CallToolRequest,
): CallToolResult = try {
    registered.handler.invoke(connection, request)
} catch (e: CancellationException) {
    throw e
} catch (e: UrlElicitationRequiredException) {
    // Surfaced as JSON-RPC -32042 by the protocol layer, not as a tool error.
    throw e
} catch (e: Exception) {
    CallToolResult(
        content = listOf(TextContent("Error executing tool ${request.name}: ${e.message}")),
        isError = true,
    )
}

/**
 * Best-effort `notifications/tasks/status`. A dropped status frame must never fail the task — the
 * client can always fall back to polling `tasks/get`, which is the spec's baseline anyway.
 */
private suspend fun ClientConnection.sendTaskStatus(task: Task) {
    runCatching { notification(TaskStatusNotification(task.asNotificationParams())) }
}

/**
 * `_meta` marking a message as belonging to [taskId]. The spec requires it on **every** message
 * related to a task — including the server-to-client requests a task's body makes, which is why
 * this is shared with `elicitation/VersionElicitation.kt` rather than private here.
 */
internal fun relatedTaskMeta(taskId: String): JsonObject = buildJsonObject {
    put(RELATED_TASK_META_KEY, buildJsonObject { put("taskId", taskId) })
}

private fun Task.asNotificationParams(): TaskStatusNotificationParams = TaskStatusNotificationParams(
    taskId = taskId,
    status = status,
    statusMessage = statusMessage,
    createdAt = createdAt,
    lastUpdatedAt = lastUpdatedAt,
    ttl = ttl,
    pollInterval = pollInterval,
    meta = relatedTaskMeta(taskId),
)

/** `Task` and `GetTaskResult` carry the same fields; the latter flattens them into a result. */
private fun Task.asGetTaskResult(): GetTaskResult = GetTaskResult(
    taskId = taskId,
    status = status,
    statusMessage = statusMessage,
    createdAt = createdAt,
    lastUpdatedAt = lastUpdatedAt,
    ttl = ttl,
    pollInterval = pollInterval,
)

/**
 * Turns store lookup failures into JSON-RPC errors.
 *
 * Unlike a tool call — where SEP-1303 wants failures as `isError` results the model can read and
 * retry — a bad `taskId` is a malformed *protocol* request from the client's own bookkeeping, so it
 * belongs in the error channel where the SDK's task methods expect it.
 */
private inline fun <T> mapTaskErrors(block: () -> T): T = try {
    block()
} catch (e: UnknownTaskException) {
    throw McpException(
        code = RPCError.ErrorCode.INVALID_PARAMS,
        message = e.message ?: "Unknown task",
    )
} catch (e: TaskNotReadyException) {
    throw McpException(
        code = RPCError.ErrorCode.INVALID_PARAMS,
        message = e.message ?: "Task is not complete",
    )
}
