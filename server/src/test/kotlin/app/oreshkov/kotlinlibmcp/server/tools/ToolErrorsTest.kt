package app.oreshkov.kotlinlibmcp.server.tools

import app.oreshkov.kotlinlibmcp.fetch.SourcesNotFoundException
import app.oreshkov.kotlinlibmcp.io.ZipExtractionException
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.LibraryNotFetchedException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * What a failing tool call shows the client. Messages this server wrote for the caller are returned
 * verbatim on every transport; anything else can carry absolute paths, so a client that does not
 * share the server's machine sees only the exception type.
 */
class ToolErrorsTest {

    private val request = CallToolRequest(CallToolRequestParams(name = "get_source"))

    private suspend fun failWith(e: Exception, revealInternalErrors: Boolean): String {
        val result: CallToolResult = FakeConnection().guarded(request, revealInternalErrors) { throw e }
        assertEquals(true, result.isError)
        return (result.content.single() as TextContent).text
    }

    @Test
    fun anUnexpectedFailureIsReducedToItsTypeForARemoteClient() = runTest {
        val text = failWith(NoSuchFileException("/home/svc/.cache/kotlin-lib-mcp/x.kt"), revealInternalErrors = false)

        assertFalse("/home/svc" in text, text)
        assertEquals(
            "get_source failed with an internal error (NoSuchFileException); the server log has the details.",
            text,
        )
    }

    @Test
    fun anUnexpectedFailureIsShownInFullToAClientOnTheSameMachine() = runTest {
        val text = failWith(NoSuchFileException("/home/me/.cache/kotlin-lib-mcp/x.kt"), revealInternalErrors = true)

        assertEquals("/home/me/.cache/kotlin-lib-mcp/x.kt", text)
    }

    @Test
    fun zipExtractorMessagesAreNotPassedThroughBecauseTheyNameTheArchiveByItsAbsolutePath() = runTest {
        val text = failWith(
            ZipExtractionException("/srv/cache/jars/x-sources.jar expands past 1 bytes; refusing to extract"),
            revealInternalErrors = false,
        )

        assertFalse("/srv/cache" in text, text)
        assertTrue("ZipExtractionException" in text, text)
    }

    @Test
    fun messagesWrittenForTheCallerPassThroughOnEveryTransport() = runTest {
        val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
        val written = listOf(
            IllegalArgumentException("Argument 'maxLines' must be an integer, got \"x\""),
            LibraryNotFetchedException(coordinate),
            SourcesNotFoundException("No sources jar found for $coordinate in [https://repo1.maven.org/maven2/]"),
        )
        for (e in written) {
            assertEquals(e.message, failWith(e, revealInternalErrors = false))
        }
    }

    @Test
    fun cancellationIsNeverFlattenedIntoAResult() = runTest {
        assertFailsWith<CancellationException> {
            FakeConnection().guarded(request) { throw CancellationException("client withdrew the call") }
        }
    }
}
