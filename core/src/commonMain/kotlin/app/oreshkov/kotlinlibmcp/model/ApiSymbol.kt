package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One public/internal declaration in a library's API surface, as recovered from its sources.
 *
 * [signature] is the rendered declaration (type parameters, value parameters, return/receiver
 * types, modifiers). When the Analysis API can fully resolve the symbol it is exact; when type
 * resolution degrades to a PSI-only reading, [bestEffort] is `true` and [signature] holds the raw
 * declaration text. That flag is part of the model from day one so tools can tell clients a
 * signature is approximate.
 */
@Serializable
@SerialName("ApiSymbol")
public data class ApiSymbol(
    val fqName: String,
    val kind: SymbolKind,
    val visibility: Visibility,
    val signature: String,
    val typeParameters: List<String> = emptyList(),
    val supertypes: List<String> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val kdoc: KDoc? = null,
    val sourceRef: SourceLocation? = null,
    val bestEffort: Boolean = false,
)
