package app.oreshkov.kotlinlibmcp.server.telemetry

import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.fakeService
import app.oreshkov.kotlinlibmcp.server.tools.registerGetLatestVersionTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListPackagesTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `tools/call` spans emitted through `guarded`, asserted end-to-end: a real registered tool
 * handler is invoked with a fake [FakeConnection], so the span comes from the same code path a
 * live MCP request takes.
 *
 * Attribute names follow the MCP semantic conventions, which are **Development** status — these
 * assertions are what pins them, so a rename shows up here first.
 */
class ToolSpanTest {

    private val exporter = InMemorySpanExporter.create()
    private lateinit var tracerProvider: SdkTracerProvider

    @BeforeTest
    fun installTestTracer() {
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        Telemetry.tracer = tracerProvider.get("test")
    }

    @AfterTest
    fun restoreNoopTracer() {
        Telemetry.reset()
        tracerProvider.close()
        exporter.reset()
    }

    private fun server(catalog: VersionCatalog = VersionCatalog(versions = emptyList())): Server {
        val service = fakeService(catalog)
        return Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        ) {
            registerGetLatestVersionTool(service)
            registerListPackagesTool(service)
        }
    }

    /** Invokes a registered tool exactly as the SDK would: its handler, on a client connection. */
    private suspend fun Server.callTool(
        name: String,
        arguments: JsonObject,
        meta: RequestMeta? = null,
    ): CallToolResult {
        val registered = requireNotNull(tools[name]) { "tool '$name' is not registered" }
        val request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments, meta = meta))
        return registered.handler(FakeConnection(), request)
    }

    private fun singleSpan(): SpanData = exporter.finishedSpanItems.single()

    @Test
    fun successfulToolCallEmitsAServerSpanWithMcpSemconvAttributes() = runTest {
        val catalog = VersionCatalog(versions = listOf("1.0.0", "2.0.0"), release = "2.0.0")
        val result = server(catalog).callTool(
            "get_latest_version",
            buildJsonObject { put("coordinate", "io.ktor:ktor-client-core") },
        )
        assertNull(result.isError, "the fake catalog resolves, so the call must succeed")

        val span = singleSpan()
        // `{mcp.method.name} {target}` — the MCP span-naming convention.
        assertEquals("tools/call get_latest_version", span.name)
        assertEquals(SpanKind.SERVER, span.kind)
        assertEquals(StatusCode.UNSET, span.status.statusCode)

        val attributes = span.attributes.asMap().mapKeys { it.key.key }
        assertEquals("tools/call", attributes["mcp.method.name"])
        assertEquals("get_latest_version", attributes["gen_ai.tool.name"])
        assertEquals("execute_tool", attributes["gen_ai.operation.name"])
        assertEquals("test-session", attributes["mcp.session.id"])
        assertEquals("pipe", attributes["network.transport"])
        assertNull(attributes["error.type"], "a successful call must not carry error.type")
    }

    @Test
    fun failingToolCallIsMarkedWithTheSpecsToolErrorType() = runTest {
        // UnusedCache has nothing cached, so list_packages raises LibraryNotFetchedException,
        // which `guarded` flattens into an isError result.
        val result = server().callTool(
            "list_packages",
            buildJsonObject { put("coordinate", "io.ktor:ktor-client-core:3.5.1") },
        )
        assertEquals(true, result.isError)

        val span = singleSpan()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        // The literal value the MCP semconv mandates for a CallToolResult with isError: true.
        assertEquals("tool_error", span.attributes.asMap().mapKeys { it.key.key }["error.type"])
        // The flattened exception is still recorded, so the detail is not lost.
        assertTrue(
            span.events.any { it.name == "exception" },
            "the originating exception must be recorded on the span",
        )
    }

    @Test
    fun traceContextIsAdoptedFromRequestMeta() = runTest {
        // SEP-414: MCP carries trace context in params._meta with unprefixed W3C keys.
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val parentSpanId = "00f067aa0ba902b7"
        val meta = RequestMeta(
            buildJsonObject { put("traceparent", "00-$traceId-$parentSpanId-01") }
        )

        server().callTool(
            "list_packages",
            buildJsonObject { put("coordinate", "io.ktor:ktor-client-core:3.5.1") },
            meta = meta,
        )

        val span = singleSpan()
        assertEquals(traceId, span.spanContext.traceId, "the span must join the caller's trace")
        assertEquals(parentSpanId, span.parentSpanContext.spanId)
    }

    @Test
    fun requestWithoutTraceContextStartsANewRootSpan() = runTest {
        server().callTool(
            "list_packages",
            buildJsonObject { put("coordinate", "io.ktor:ktor-client-core:3.5.1") },
            meta = RequestMeta(JsonObject(mapOf("progressToken" to JsonPrimitive("t1")))),
        )

        assertTrue(singleSpan().parentSpanContext.isValid.not(), "expected a root span")
    }

    @Test
    fun networkTransportReflectsTheRunningTransport() {
        // Stable semconv values: stdio is an OS pipe, Streamable HTTP rides tcp.
        assertEquals("pipe", networkTransportFor("stdio"))
        assertEquals("tcp", networkTransportFor("http"))
        assertEquals("tcp", networkTransportFor("HTTP"))
    }

    @Test
    fun telemetryIsInertUntilStarted() = runTest {
        // Mirrors the `--forward-logs-to-client` default-off guarantee: with no `--otel`, the
        // tracer stays no-op and nothing is recorded.
        Telemetry.reset()
        assertEquals(false, Telemetry.enabled)

        server().callTool(
            "list_packages",
            buildJsonObject { put("coordinate", "io.ktor:ktor-client-core:3.5.1") },
        )

        assertEquals(emptyList(), exporter.finishedSpanItems)
    }
}
