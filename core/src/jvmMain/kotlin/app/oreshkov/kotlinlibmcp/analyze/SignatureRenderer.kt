@file:OptIn(KaExperimentalApi::class)

package app.oreshkov.kotlinlibmcp.analyze

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KaDeclarationRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.bodies.KaParameterDefaultValueRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.declarations.modifiers.renderers.KaRendererVisibilityModifierProvider
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.utils.printer.PrettyPrinter
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

/*
 * Signatures are rendered two ways: resolved via the Analysis API renderer (inside `analyze { }`)
 * and a PSI-only fallback (declaration text with bodies/initializers cut off) used when resolution
 * fails or produces error types. Rendering is deterministic — same input, same string — so the
 * persisted index is reproducible.
 *
 * The resolved path is the Analysis API's `ForSource` preset with two overrides — see
 * [declarationRenderer]; the preset alone drops parameter defaults and the `public` keyword.
 */

/** A symbol's resolved rendering, produced inside an `analyze { }` block (plain strings only). */
internal data class RenderedSymbol(
    val signature: String,
    val supertypes: List<String>,
    val hasErrorTypes: Boolean,
)

private val whitespace = Regex("""\s+""")

private fun String.squash(): String = replace(whitespace, " ").trim()

/**
 * Source-accurate rendering of a parameter's default value.
 *
 * The `KaDeclarationRendererForSource` preset uses [KaParameterDefaultValueRenderer.NO_DEFAULT_VALUE],
 * which drops defaults entirely and leaves an optional parameter indistinguishable from a required
 * one — the one mistake a tool that promises *the exact signature* must not make, since a caller
 * reading `HttpClient(block: HttpClientConfig<*>.() -> Unit)` concludes `HttpClient()` won't
 * compile. The built-in `THREE_DOTS` alternative only says *that* a default exists; we always
 * analyze real sources, so the default's own text is in PSI and we print it verbatim.
 *
 * Three deliberate compromises:
 * - The text is the author's, so its names are as written (usually short and unqualified) even
 *   though the types around it are fully qualified. Truthful, if visibly mixed.
 * - A long default renders as `...` rather than folding an expression into a signature.
 * - A default *inherited* from an `expect` declaration or an overridden function has no local
 *   text; it renders as `...`, which still tells the caller the argument may be omitted.
 */
private object SourceTextDefaultValueRenderer : KaParameterDefaultValueRenderer {

    /** Past this many characters a default is summarised instead of inlined. */
    private const val MAX_LENGTH = 60

    override fun renderDefaultValue(
        analysisSession: KaSession,
        symbol: KaValueParameterSymbol,
        printer: PrettyPrinter,
    ) {
        val declared = (symbol.psi as? KtParameter)?.defaultValue?.text?.squash()
        when {
            declared != null && declared.length <= MAX_LENGTH -> printer.append(declared)
            declared != null || symbol.hasDefaultValue -> printer.append("...")
        }
    }
}

/**
 * `KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES`, with the two things that preset omits and
 * this server's callers need, put back.
 *
 * `NO_IMPLICIT_VISIBILITY` prints a visibility keyword only when it isn't the default, so `public`
 * never appears — which reads as "visibility unstated" rather than "public", and loses a modifier
 * the declaration may well have spelled out. Visibility is always rendered here instead: one fewer
 * thing a reader has to infer, and the answer never depends on whether the library uses explicit
 * API mode. Defaults come from [SourceTextDefaultValueRenderer].
 */
private val declarationRenderer: KaDeclarationRenderer =
    KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES.with {
        modifiersRenderer = modifiersRenderer.with {
            visibilityProvider = KaRendererVisibilityModifierProvider.WITH_IMPLICIT_VISIBILITY
        }
        parameterDefaultValueRenderer = SourceTextDefaultValueRenderer
    }

/** Resolved rendering; must be called inside `analyze { }`. Throws when the symbol is sick. */
internal fun KaSession.renderResolved(symbol: KaDeclarationSymbol): RenderedSymbol {
    val signature = symbol.render(declarationRenderer).squash()
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
