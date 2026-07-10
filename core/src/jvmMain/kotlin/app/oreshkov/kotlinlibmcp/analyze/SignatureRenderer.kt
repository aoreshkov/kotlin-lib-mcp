@file:OptIn(KaExperimentalApi::class)

package app.oreshkov.kotlinlibmcp.analyze

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

/*
 * Signatures are rendered two ways: resolved via the Analysis API renderer (inside `analyze { }`)
 * and a PSI-only fallback (declaration text with bodies/initializers cut off) used when resolution
 * fails or produces error types. Rendering is deterministic — same input, same string — so the
 * persisted index is reproducible.
 */

/** A symbol's resolved rendering, produced inside an `analyze { }` block (plain strings only). */
internal data class RenderedSymbol(
    val signature: String,
    val supertypes: List<String>,
    val hasErrorTypes: Boolean,
)

private val whitespace = Regex("""\s+""")

private fun String.squash(): String = replace(whitespace, " ").trim()

/** Resolved rendering; must be called inside `analyze { }`. Throws when the symbol is sick. */
internal fun KaSession.renderResolved(symbol: KaDeclarationSymbol): RenderedSymbol {
    val signature = symbol.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES).squash()
    val supertypes = (symbol as? KaClassSymbol)
        ?.superTypes
        ?.map { it.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, position = Variance.INVARIANT).squash() }
        ?.filterNot { it == "kotlin.Any" }
        .orEmpty()
    return RenderedSymbol(signature, supertypes, hasErrorTypes = symbol.referencesErrorType())
}

/**
 * PSI-only fallback: the declaration's own text, minus KDoc and minus body/initializer, with
 * whitespace collapsed. Always available, never resolves anything.
 */
internal fun psiSignature(declaration: KtNamedDeclaration): String {
    val raw = declaration.text
    val start = declaration.docComment
        ?.let { it.textRange.endOffset - declaration.textRange.startOffset }
        ?: 0
    val cutElement = when (declaration) {
        is KtProperty ->
            declaration.initializer ?: declaration.delegate ?: declaration.accessors.firstOrNull()
        is KtDeclarationWithBody -> declaration.bodyBlockExpression ?: declaration.bodyExpression
        is KtClassOrObject -> declaration.body
        else -> null
    }
    val end = cutElement
        ?.let { it.textRange.startOffset - declaration.textRange.startOffset }
        ?: raw.length
    return raw.substring(start.coerceIn(0, raw.length), end.coerceIn(start, raw.length))
        .squash()
        .removeSuffix("=")
        .removeSuffix("by")
        .trim()
}

/** `true` when any type in the symbol's shape failed to resolve (missing transitive dep, …). */
private fun KaDeclarationSymbol.referencesErrorType(): Boolean {
    val types = buildList {
        (this@referencesErrorType as? KaCallableSymbol)?.let { callable ->
            add(callable.returnType)
            callable.receiverType?.let(::add)
        }
        (this@referencesErrorType as? KaFunctionSymbol)?.valueParameters?.forEach { add(it.returnType) }
        (this@referencesErrorType as? KaClassSymbol)?.superTypes?.let(::addAll)
    }
    return types.any { it.containsErrorType() }
}

private fun KaType.containsErrorType(): Boolean = when (this) {
    is KaErrorType -> true
    is KaClassType -> typeArguments.any { it.type?.containsErrorType() == true }
    else -> false
}
