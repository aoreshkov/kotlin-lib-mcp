package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.fakeService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The spec requires servers to validate tool input and to report a bad argument as an `isError`
 * result the model can correct. The failure this guards against is the quiet one: a wrong type or a
 * misspelt name used to fall back to the default, answering a different question than was asked.
 */
class ToolArgumentsTest {

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.associate { (name, value) ->
            name to when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is JsonArray -> value
                else -> error("unsupported test value $value")
            }
        },
    )

    @Test
    fun integersAcceptUnambiguousEncodings() {
        assertEquals(10, args("n" to 10).intArg("n"))
        assertEquals(10, args("n" to "10").intArg("n"))
        assertEquals(10, args("n" to 10.0).intArg("n"))
        assertNull(args().intArg("n"), "absent means not given")
        assertNull(args("n" to null).intArg("n"), "JSON null means not given")
    }

    @Test
    fun integersRejectEverythingElseInsteadOfFallingBackToTheDefault() {
        for (bad in listOf<Any>("abc", 10.5, true, JsonArray(emptyList()), 1e12)) {
            val e = assertFailsWith<IllegalArgumentException>("$bad") { args("maxResults" to bad).intArg("maxResults") }
            assertTrue(e.message!!.startsWith("Argument 'maxResults' must be an integer, got "), e.message)
        }
    }

    @Test
    fun booleansAcceptTrueAndFalseOnly() {
        assertEquals(true, args("b" to true).booleanArg("b"))
        assertEquals(false, args("b" to "false").booleanArg("b"))
        val e = assertFailsWith<IllegalArgumentException> { args("regex" to "yes").booleanArg("regex") }
        assertEquals("Argument 'regex' must be true or false, got \"yes\"", e.message)
    }

    @Test
    fun stringsAcceptAnyPrimitiveAndTreatBlankAsNotGiven() {
        assertEquals("io.ktor", args("p" to "io.ktor").stringArg("p"))
        assertEquals("42", args("q" to 42).stringArg("q"), "searching for a number is a reasonable query")
        assertNull(args("p" to "  ").stringArg("p"))
        assertFailsWith<IllegalArgumentException> { args("p" to JsonArray(emptyList())).stringArg("p") }
    }

    @Test
    fun aLongRejectedValueIsShortenedInTheMessage() {
        val e = assertFailsWith<IllegalArgumentException> { args("n" to "x".repeat(500)).intArg("n") }
        assertTrue(e.message!!.length < 120, e.message)
    }

    // --- through a registered tool, as a client would see it ---

    private fun server(): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ) { registerListPackagesTool(fakeService()) }

    private suspend fun Server.callTool(arguments: JsonObject): CallToolResult {
        val request = CallToolRequest(CallToolRequestParams(name = "list_packages", arguments = arguments))
        return tools.getValue("list_packages").handler(FakeConnection(), request)
    }

    private val CallToolResult.text: String get() = (content.single() as TextContent).text

    @Test
    fun aMisspeltArgumentIsAnErrorThatNamesTheAcceptedOnes() = runTest {
        val result = server().callTool(
            buildJsonObject {
                put("coordinate", "io.ktor:ktor-client-core:3.5.1")
                put("max_results", 5)
            },
        )

        assertEquals(true, result.isError)
        assertEquals(
            "Unknown argument 'max_results' for list_packages; it accepts coordinate, maxResults, offset.",
            result.text,
        )
    }

    @Test
    fun aWronglyTypedArgumentIsAnErrorBeforeTheServiceRuns() = runTest {
        val result = server().callTool(
            buildJsonObject {
                put("coordinate", "io.ktor:ktor-client-core:3.5.1")
                put("maxResults", "lots")
            },
        )

        assertEquals(true, result.isError)
        assertEquals("Argument 'maxResults' must be an integer, got \"lots\"", result.text)
    }

    @Test
    fun declaredArgumentsPassTheCheck() = runTest {
        // Nothing is cached in the fake, so the call fails — but in the service, not on its arguments.
        val result = server().callTool(
            buildJsonObject {
                put("coordinate", "io.ktor:ktor-client-core:3.5.1")
                put("maxResults", 5)
                put("offset", 0)
            },
        )

        assertEquals(true, result.isError)
        assertFalse("argument" in result.text.lowercase(), result.text)
        assertTrue("fetch_library" in result.text, result.text)
    }
}
