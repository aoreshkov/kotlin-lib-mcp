package app.oreshkov.kotlinlibmcp.server.prompts

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.FakeFetcher
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.UnusedAnalyzer
import app.oreshkov.kotlinlibmcp.server.fakeService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class ExplainPublicApiPromptTest {

    private val coordinate = LibraryCoordinate("com.example", "demo", "1.0.0")

    /** Cache that always serves [index] for `get`; every other op is an inert no-op. */
    private class SingleIndexCache(private val index: LibraryIndex) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex = index
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = listOf(index.coordinate)
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    private fun server(service: LibraryService): Server = Server(
        serverInfo = Implementation(name = "test", version = "0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(prompts = ServerCapabilities.Prompts(listChanged = false)),
        ),
    ) { registerExplainPublicApiPrompt(service) }

    /** A service whose cached index holds [count] public classes. */
    private fun serviceWith(count: Int): LibraryService {
        val symbols = (1..count).associate { i ->
            val fq = "com.example.Sym$i"
            fq to ApiSymbol(fq, SymbolKind.CLASS, Visibility.PUBLIC, signature = "class Sym$i")
        }
        val index = LibraryIndex(coordinate, symbolsByFqName = symbols, fetchedAt = Instant.fromEpochSeconds(0))
        return LibraryService(
            fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(index),
        )
    }

    /** Renders the prompt exactly as the SDK would — its provider, on a client connection — and returns its text. */
    private suspend fun Server.renderPrompt(): String {
        val registered = requireNotNull(prompts["explain_public_api"])
        val request = GetPromptRequest(
            GetPromptRequestParams(name = "explain_public_api", arguments = mapOf("coordinate" to "$coordinate")),
        )
        val result = registered.messageProvider(FakeConnection(), request)
        return (result.messages.single().content as TextContent).text
    }

    private fun String.embeddedDeclarations(): Int = lines().count { it.startsWith("- class Sym") }

    @Test
    fun promptIsRegisteredWithItsArgumentsAndIcon() {
        // Registered through `addPrompt(Prompt(…))` because `addPrompt(name, …)` cannot carry
        // SEP-973 icons — so assert the rest of the metadata survived that detour too.
        val server = server(fakeService())

        val prompt = assertNotNull(server.prompts["explain_public_api"]?.prompt)
        assertNotNull(prompt.description)
        assertEquals(listOf("coordinate", "package"), prompt.arguments?.map { it.name })
        assertEquals(listOf(true, false), prompt.arguments?.map { it.required })
        assertEquals(Glyph.Prompt.icons, prompt.icons, "SEP-973 icons")
    }

    @Test
    fun embedsEveryDeclarationUpToTheCap() = runTest {
        // Between list_declarations' default page (100) and this prompt's own cap (150): the prompt
        // used to inherit the smaller page and silently drop the rest.
        val text = server(serviceWith(120)).renderPrompt()

        assertEquals(120, text.embeddedDeclarations())
        assertFalse("omitted" in text, "nothing was left out, so nothing may claim to be")
    }

    @Test
    fun saysHowManyDeclarationsTheCapLeftOut() = runTest {
        // The model is told to base its explanation strictly on what is embedded, so it must also
        // be told that the list is incomplete.
        val text = server(serviceWith(200)).renderPrompt()

        assertEquals(150, text.embeddedDeclarations())
        assertTrue("(50 more declarations omitted" in text, text.takeLast(200))
    }
}
