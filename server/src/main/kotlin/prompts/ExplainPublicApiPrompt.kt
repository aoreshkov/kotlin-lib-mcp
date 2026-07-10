package app.oreshkov.kotlinlibmcp.server.prompts

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.LibraryService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/** Cap on the declarations embedded in the prompt so huge libraries stay within context limits. */
private const val MAX_DECLARATIONS = 150

/**
 * `explain_public_api` prompt: asks the model to explain a fetched library's public API, with the
 * cached signatures and KDoc summaries embedded as concrete context (not a generic instruction).
 */
fun Server.registerExplainPublicApiPrompt(service: LibraryService) {
    addPrompt(
        name = "explain_public_api",
        description = "Explain the public API surface of a fetched library, optionally scoped to " +
            "one package, grounded in its cached signatures and KDoc.",
        arguments = listOf(
            PromptArgument(
                name = "coordinate",
                description = "Maven coordinate 'group:artifact:version' (must be fetched already)",
                required = true,
            ),
            PromptArgument(
                name = "package",
                description = "Optional package to scope the explanation to",
                required = false,
            ),
        ),
    ) { request ->
        val coordinate = LibraryCoordinate.parse(
            request.arguments?.get("coordinate")
                ?: throw IllegalArgumentException("Missing required prompt argument 'coordinate'")
        )
        val packageName = request.arguments?.get("package")?.takeIf { it.isNotBlank() }

        val declarations = service
            .listDeclarations(coordinate, packageName, visibility = "public")
            .declarations
        val scope = packageName?.let { "package $it of $coordinate" } ?: "$coordinate"
        val apiContext = declarations.take(MAX_DECLARATIONS).joinToString("\n") { symbol ->
            buildString {
                append("- ").append(symbol.signature)
                symbol.kdoc?.summary?.let { append("  // ").append(it) }
            }
        }
        val truncationNote =
            if (declarations.size > MAX_DECLARATIONS) {
                "\n(${declarations.size - MAX_DECLARATIONS} more declarations omitted — " +
                    "use the list_declarations tool for the full list.)"
            } else ""

        GetPromptResult(
            description = "Explain the public API of $scope",
            messages = listOf(
                PromptMessage(
                    role = Role.User,
                    content = TextContent(
                        """
                        Explain the public API of $scope to a developer seeing it for the first time.
                        Group related declarations, describe the core entry points and how they fit
                        together, and note anything surprising. Base the explanation strictly on the
                        declarations below (resolved from the library's published sources):
                        """.trimIndent() + "\n\n" + apiContext + truncationNote
                    ),
                )
            ),
        )
    }
}
