package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.server.tools.JSON_SCHEMA_DIALECT
import app.oreshkov.kotlinlibmcp.server.tools.registerFetchLibraryTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetApiSignatureTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetDependenciesTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetKDocTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetLatestVersionTool
import app.oreshkov.kotlinlibmcp.server.tools.registerGetSourceTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListDeclarationsTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListPackagesTool
import app.oreshkov.kotlinlibmcp.server.tools.registerListVersionsTool
import app.oreshkov.kotlinlibmcp.server.tools.registerSearchSourceTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every registered tool must carry the metadata the MCP spec encourages: a display title,
 * behavior annotations, and an output schema matching the DTO it serializes.
 */
class ToolRegistrationTest {

    private val readOnlyLocal =
        setOf("list_packages", "list_declarations", "get_api_signature", "get_kdoc", "get_source", "search_source")
    private val readOnlyRepository = setOf("get_dependencies", "list_versions", "get_latest_version")

    private fun serverWithAllTools(): Server {
        val service = fakeService()
        return Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        ) {
            registerFetchLibraryTool(service)
            registerListPackagesTool(service)
            registerListDeclarationsTool(service)
            registerGetApiSignatureTool(service)
            registerGetKDocTool(service)
            registerGetSourceTool(service)
            registerSearchSourceTool(service)
            registerGetDependenciesTool(service)
            registerListVersionsTool(service)
            registerGetLatestVersionTool(service)
        }
    }

    private fun tools(): Map<String, Tool> = serverWithAllTools().tools.mapValues { it.value.tool }

    @Test
    fun allTenToolsAreRegistered() {
        assertEquals(readOnlyLocal + readOnlyRepository + "fetch_library", tools().keys)
    }

    @Test
    fun everyToolDeclaresTitleAnnotationsAndOutputSchema() {
        tools().forEach { (name, tool) ->
            assertNotNull(tool.title, "$name: missing title")
            assertNotNull(tool.annotations?.readOnlyHint, "$name: missing readOnlyHint")
            assertNotNull(tool.annotations?.openWorldHint, "$name: missing openWorldHint")
            assertNotNull(tool.outputSchema?.properties, "$name: missing outputSchema")
        }
    }

    @Test
    fun everyToolDeclaresJsonSchema2020_12Dialect() {
        // SEP-1613: 2020-12 is the default, but we declare it explicitly on every input and
        // output schema so no construction site silently drops it.
        tools().forEach { (name, tool) ->
            assertEquals(JSON_SCHEMA_DIALECT, tool.inputSchema.schema, "$name: inputSchema dialect")
            assertEquals(JSON_SCHEMA_DIALECT, tool.outputSchema?.schema, "$name: outputSchema dialect")
        }
    }

    @Test
    fun readOnlyAndOpenWorldHintsMatchWhatEachToolTouches() {
        val tools = tools()
        readOnlyLocal.forEach { name ->
            val annotations = assertNotNull(tools.getValue(name).annotations)
            assertEquals(true, annotations.readOnlyHint, name)
            assertEquals(false, annotations.openWorldHint, name)
        }
        readOnlyRepository.forEach { name ->
            val annotations = assertNotNull(tools.getValue(name).annotations)
            assertEquals(true, annotations.readOnlyHint, name)
            assertEquals(true, annotations.openWorldHint, name)
        }
    }

    @Test
    fun loggingCapabilityIsOffByDefaultAndOptInViaFlag() {
        // C5: the deprecated `logging` capability is not advertised unless --forward-logs-to-client
        // is set; stderr is the primary channel either way. The other capabilities stay on.
        val default = serverCapabilities(forwardLogsToClient = false)
        assertNull(default.logging, "logging must not be advertised by default")
        assertNotNull(default.tools)
        assertNotNull(default.resources)
        assertNotNull(default.prompts)
        assertNotNull(default.completions)

        val optedIn = serverCapabilities(forwardLogsToClient = true)
        assertNotNull(optedIn.logging, "logging must be advertised with --forward-logs-to-client")
    }

    @Test
    fun fetchLibraryIsAnnotatedAsAdditiveIdempotentAndOpenWorld() {
        val annotations = assertNotNull(tools().getValue("fetch_library").annotations)
        assertEquals(false, annotations.readOnlyHint)
        assertEquals(false, annotations.destructiveHint)
        assertEquals(true, annotations.idempotentHint)
        assertEquals(true, annotations.openWorldHint)
    }
}
