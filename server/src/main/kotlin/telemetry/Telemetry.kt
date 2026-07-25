package app.oreshkov.kotlinlibmcp.server.telemetry

import app.oreshkov.kotlinlibmcp.server.SERVER_NAME
import app.oreshkov.kotlinlibmcp.server.ServerVersion
import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/*
 * OpenTelemetry tracing for the MCP request surface, exported over OTLP/HTTP.
 *
 * Opt-in via `--otel`; until [startTelemetry] runs, [Telemetry.tracer] is a no-op and the span
 * helpers below cost nothing (the API returns singleton no-op spans whose setters do nothing).
 *
 * Two constraints shape this file:
 *  - **Nothing here may write to stdout.** Under the stdio transport stdout carries MCP protocol
 *    frames only. OTel's internal diagnostics go through JUL, whose default handler writes to
 *    stderr, so exporter failures degrade to stderr noise — never protocol corruption.
 *  - The `mcp.*` and `gen_ai.*` attribute names are **Development** status in the MCP semantic
 *    conventions (open-telemetry/semantic-conventions-genai, `docs/gen-ai/mcp.md`). They are
 *    declared once below so a rename is a single-point edit — that instability is also why the
 *    whole feature is behind a flag.
 */

/** Instrumentation scope reported for every span this server emits. */
private const val INSTRUMENTATION_SCOPE = "app.oreshkov.kotlinlibmcp.server"

/**
 * Bound on the shutdown flush, and therefore the worst-case exit delay when the collector is
 * unreachable. Deliberately shorter than `OpenTelemetrySdk.close()`'s built-in 10s: a stdio MCP
 * client notices a hang on exit. A healthy export finishes in milliseconds, so this only ever
 * costs anything when the endpoint is wrong — which [stopTelemetry] then says out loud.
 */
private const val SHUTDOWN_TIMEOUT_SECONDS = 3L

/** OTLP/HTTP's default endpoint, per the exporter spec — reported at startup for diagnostics. */
private const val DEFAULT_OTLP_ENDPOINT = "http://localhost:4318"

private val log = Logger.withTag("Telemetry")

// --- semantic-convention attribute keys (MCP semconv; all Development status) ---

private val MCP_METHOD_NAME = AttributeKey.stringKey("mcp.method.name")
private val MCP_SESSION_ID = AttributeKey.stringKey("mcp.session.id")
private val MCP_RESOURCE_URI = AttributeKey.stringKey("mcp.resource.uri")
private val GEN_AI_TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name")
private val GEN_AI_PROMPT_NAME = AttributeKey.stringKey("gen_ai.prompt.name")
private val GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name")

/** `error.type` and `network.transport` are **stable** semconv attributes. */
private val ERROR_TYPE = AttributeKey.stringKey("error.type")
private val NETWORK_TRANSPORT = AttributeKey.stringKey("network.transport")

/** The spec's literal `error.type` for a `CallToolResult` carrying `isError: true`. */
private const val TOOL_ERROR = "tool_error"

/** `gen_ai.operation.name` for a tool invocation. */
private const val EXECUTE_TOOL = "execute_tool"

// MCP method names, as they appear in `mcp.method.name` and in span names.
private const val METHOD_TOOLS_CALL = "tools/call"
private const val METHOD_RESOURCES_READ = "resources/read"
private const val METHOD_PROMPTS_GET = "prompts/get"
private const val METHOD_COMPLETION_COMPLETE = "completion/complete"

internal const val METHOD_TASKS_GET = "tasks/get"
internal const val METHOD_TASKS_RESULT = "tasks/result"
internal const val METHOD_TASKS_LIST = "tasks/list"
internal const val METHOD_TASKS_CANCEL = "tasks/cancel"

/**
 * Process-wide tracer holder.
 *
 * Deliberately **not** `GlobalOpenTelemetry`: that has a once-per-JVM setter which would make the
 * telemetry tests order-dependent, and leaving the global untouched means an embedder (the Compose
 * dashboard) keeps whatever it configured for itself.
 */
internal object Telemetry {
    private val NOOP: Tracer = OpenTelemetry.noop().getTracer(INSTRUMENTATION_SCOPE)

    @Volatile
    internal var tracer: Tracer = NOOP

    /** semconv `network.transport` for the running transport; see [networkTransportFor]. */
    @Volatile
    internal var networkTransport: String = TRANSPORT_PIPE

    /** True once [startTelemetry] (or a test) has installed a real tracer. */
    internal val enabled: Boolean get() = tracer !== NOOP

    internal fun reset() {
        tracer = NOOP
        networkTransport = TRANSPORT_PIPE
    }
}

/** stdio is an OS pipe; Streamable HTTP rides tcp. Both are stable semconv values. */
private const val TRANSPORT_PIPE = "pipe"
private const val TRANSPORT_TCP = "tcp"

internal fun networkTransportFor(transport: String): String =
    if (transport.equals("http", ignoreCase = true)) TRANSPORT_TCP else TRANSPORT_PIPE

/**
 * Builds and installs the OTLP/HTTP tracing SDK, returning it so the caller owns its shutdown.
 *
 * Configuration is the standard autoconfigure surface (`OTEL_EXPORTER_OTLP_ENDPOINT`,
 * `OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, …) — the
 * officially recommended wiring, and the reason no bespoke `--otlp-*` flags exist. The values
 * supplied here are **defaults only**: precedence is system properties > environment variables >
 * this supplier, so an operator can override every one of them.
 *
 * The JVM shutdown hook is disabled: [stopTelemetry] runs from `McpServerHandle.close()` so flush
 * ordering relative to the rest of the server's cleanup is deterministic.
 */
internal fun startTelemetry(transport: String): OpenTelemetrySdk {
    val sdk = AutoConfiguredOpenTelemetrySdk.builder()
        .disableShutdownHook()
        .addPropertiesSupplier {
            mapOf(
                // This slice exports traces only; metrics and logs are separate work.
                "otel.exporter.otlp.protocol" to "http/protobuf",
                "otel.service.name" to SERVER_NAME,
                "otel.metrics.exporter" to "none",
                "otel.logs.exporter" to "none",
            )
        }
        .build()
        .openTelemetrySdk

    Telemetry.networkTransport = networkTransportFor(transport)
    Telemetry.tracer = sdk.getTracer(INSTRUMENTATION_SCOPE, ServerVersion.value)

    // The endpoint is otherwise invisible, and a wrong one fails silently until shutdown.
    val endpoint = System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        ?: System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
        ?: DEFAULT_OTLP_ENDPOINT
    log.i { "OTLP trace export enabled -> $endpoint (protocol http/protobuf)" }
    return sdk
}

/**
 * Restores the no-op tracer, then flushes and shuts the SDK down within [SHUTDOWN_TIMEOUT_SECONDS].
 *
 * The OTLP exporter reports a failed export only through `java.util.logging`, which this app does
 * not bridge — so a wrong endpoint would otherwise be completely silent, costing the operator the
 * full flush timeout on exit with no explanation. Say so on stderr instead.
 */
internal fun stopTelemetry(sdk: OpenTelemetrySdk) {
    Telemetry.reset()
    val result = sdk.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!result.isSuccess) {
        log.w {
            "OpenTelemetry did not flush within ${SHUTDOWN_TIMEOUT_SECONDS}s — spans were dropped. " +
                "Check that the collector at OTEL_EXPORTER_OTLP_ENDPOINT is reachable."
        }
    }
}

// --- trace context propagation (SEP-414) ---

/**
 * MCP carries trace context in the JSON-RPC `params._meta` bag rather than transport headers,
 * because MCP is transport-independent and one HTTP request may carry several MCP messages. Keys
 * are written **unprefixed** (`traceparent`/`tracestate`/`baggage`) — an explicit carve-out from
 * MCP's usual DNS-prefix rule, formalized in SEP-414.
 */
private val META_GETTER = object : TextMapGetter<RequestMeta> {
    override fun keys(carrier: RequestMeta): Iterable<String> = carrier.json.keys

    override fun get(carrier: RequestMeta?, key: String): String? =
        (carrier?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private val PROPAGATOR: TextMapPropagator = W3CTraceContextPropagator.getInstance()

/**
 * Parent context for an incoming request. Extraction starts from [Context.root] rather than the
 * ambient context so a server span is either a genuine child of the caller's trace or a fresh
 * root — never accidentally nested under unrelated in-process work.
 */
private fun parentContext(meta: RequestMeta?): Context =
    if (meta == null) Context.root() else PROPAGATOR.extract(Context.root(), meta, META_GETTER)

// --- span helpers, one per MCP primitive ---

/**
 * Core span wrapper. Span names follow the MCP convention `{mcp.method.name} {target}`, falling
 * back to the bare method when there is no low-cardinality target (a resource URI is an attribute,
 * never part of the name).
 *
 * The body runs inside the span's [Context] as a coroutine context element, so the span survives
 * the dispatcher hops the handlers make (notably `withContext(Dispatchers.Default)` around the
 * Analysis API in `LibraryService.fetchLibrary`).
 */
private suspend fun <T> mcpSpan(
    method: String,
    target: String?,
    sessionId: String?,
    meta: RequestMeta?,
    extra: Attributes = Attributes.empty(),
    onResult: (Span, T) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    val parent = parentContext(meta)
    val span = Telemetry.tracer.spanBuilder(if (target == null) method else "$method $target")
        .setParent(parent)
        .setSpanKind(SpanKind.SERVER)
        .setAllAttributes(extra)
        .setAttribute(MCP_METHOD_NAME, method)
        .setAttribute(NETWORK_TRANSPORT, Telemetry.networkTransport)
        .apply { sessionId?.let { setAttribute(MCP_SESSION_ID, it) } }
        .startSpan()

    return try {
        withContext(parent.with(span).asContextElement()) { block() }.also { onResult(span, it) }
    } catch (e: CancellationException) {
        // A cancelled call is not a failure of the server; leave the status unset.
        throw e
    } catch (e: Throwable) {
        span.recordException(e)
        span.setAttribute(ERROR_TYPE, e::class.qualifiedName ?: "_OTHER")
        span.setStatus(StatusCode.ERROR, e.message ?: e.toString())
        throw e
    } finally {
        span.end()
    }
}

/**
 * `tools/call` span, e.g. `tools/call fetch_library`.
 *
 * Tool bodies report failures as `isError` results rather than exceptions (the MCP convention that
 * lets the model self-correct), so the error mapping happens on the **result**: the spec's literal
 * `error.type` for that case is [TOOL_ERROR]. `guarded` additionally records the originating
 * exception on the span, so the detail is not lost.
 */
internal suspend fun toolSpan(
    toolName: String,
    sessionId: String?,
    meta: RequestMeta?,
    block: suspend () -> CallToolResult,
): CallToolResult = mcpSpan(
    method = METHOD_TOOLS_CALL,
    target = toolName,
    sessionId = sessionId,
    meta = meta,
    extra = Attributes.of(GEN_AI_TOOL_NAME, toolName, GEN_AI_OPERATION_NAME, EXECUTE_TOOL),
    onResult = { span, result ->
        if (result.isError == true) {
            span.setAttribute(ERROR_TYPE, TOOL_ERROR)
            span.setStatus(StatusCode.ERROR)
        }
    },
    block = block,
)

/** `resources/read` span. The URI is high-cardinality, so it is an attribute, not part of the name. */
internal suspend fun <T> resourceSpan(
    uri: String,
    sessionId: String?,
    meta: RequestMeta?,
    block: suspend () -> T,
): T = mcpSpan(
    method = METHOD_RESOURCES_READ,
    target = null,
    sessionId = sessionId,
    meta = meta,
    extra = Attributes.of(MCP_RESOURCE_URI, uri),
    block = block,
)

/** `prompts/get` span, e.g. `prompts/get explain_public_api`. */
internal suspend fun <T> promptSpan(
    promptName: String,
    sessionId: String?,
    meta: RequestMeta?,
    block: suspend () -> T,
): T = mcpSpan(
    method = METHOD_PROMPTS_GET,
    target = promptName,
    sessionId = sessionId,
    meta = meta,
    extra = Attributes.of(GEN_AI_PROMPT_NAME, promptName),
    block = block,
)

/**
 * Span for a `tasks/…` request, e.g. `tasks/get`. [method] must be one of the `METHOD_TASKS_*`
 * constants above.
 *
 * The task id is not recorded: it is per-invocation (high-cardinality), and the MCP semantic
 * conventions define no attribute for it yet — inventing one here would defeat the single-point
 * rename this file exists to preserve. The originating `tools/call` span already carries the tool
 * name, and inbound trace context links the two when the client propagates it.
 */
internal suspend fun <T> taskSpan(
    method: String,
    sessionId: String?,
    meta: RequestMeta?,
    block: suspend () -> T,
): T = mcpSpan(
    method = method,
    target = null,
    sessionId = sessionId,
    meta = meta,
    block = block,
)

/** `completion/complete` span. The completion target lives in `ref`, which is not low-cardinality. */
internal suspend fun <T> completionSpan(
    sessionId: String?,
    meta: RequestMeta?,
    block: suspend () -> T,
): T = mcpSpan(
    method = METHOD_COMPLETION_COMPLETE,
    target = null,
    sessionId = sessionId,
    meta = meta,
    block = block,
)
