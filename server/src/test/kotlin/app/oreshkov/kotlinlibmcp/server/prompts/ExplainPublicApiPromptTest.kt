package app.oreshkov.kotlinlibmcp.server.prompts

import app.oreshkov.kotlinlibmcp.server.fakeService
import app.oreshkov.kotlinlibmcp.server.icons.Glyph
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExplainPublicApiPromptTest {

    @Test
    fun promptIsRegisteredWithItsArgumentsAndIcon() {
        // Registered through `addPrompt(Prompt(…))` because `addPrompt(name, …)` cannot carry
        // SEP-973 icons — so assert the rest of the metadata survived that detour too.
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(prompts = ServerCapabilities.Prompts(listChanged = false)),
            ),
        ) { registerExplainPublicApiPrompt(fakeService()) }

        val prompt = assertNotNull(server.prompts["explain_public_api"]?.prompt)
        assertNotNull(prompt.description)
        assertEquals(listOf("coordinate", "package"), prompt.arguments?.map { it.name })
        assertEquals(listOf(true, false), prompt.arguments?.map { it.required })
        assertEquals(Glyph.Prompt.icons, prompt.icons, "SEP-973 icons")
    }
}
