package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.model.Visibility
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/*
 * Effective visibility: what a caller *outside* the library can reach, which is the narrowest
 * visibility on the way from the declaration out through every enclosing class. A symbol's own
 * visibility is not that — the Analysis API documents that the members of an `internal class` are
 * PUBLIC in their own right — so an index that stored it would list `internal class Helper`'s
 * members as public API. It is computed once, here, so `list_declarations` and `diff_api` read the
 * same answer instead of each re-deriving it downstream.
 */

/**
 * The narrower of the two, as seen from another module. `protected` sits between `public` and
 * `internal` there: a subclass in another module still reaches it, while nothing outside the
 * module reaches an `internal` one. So a `protected` member of an `internal class` is INTERNAL.
 */
internal fun Visibility.narrowedBy(enclosing: Visibility): Visibility = maxOf(this, enclosing, compareBy(::reach))

private fun reach(visibility: Visibility): Int = when (visibility) {
    Visibility.PUBLIC -> 0
    Visibility.PROTECTED -> 1
    Visibility.INTERNAL -> 2
    Visibility.PRIVATE -> 3
}

/**
 * Resolved effective visibility; must be called inside `analyze { }`. Resolution is what makes an
 * `override` with no modifier inherit `protected` from the member it overrides, which no amount of
 * reading its own modifiers can tell. `null` when the symbol's own visibility is not one the model
 * has, so the caller falls back to [psiEffectiveVisibility].
 */
internal fun KaSession.effectiveVisibility(symbol: KaDeclarationSymbol): Visibility? {
    var result = symbol.visibility.toModel() ?: return null
    var container = symbol.containingDeclaration
    while (container != null) {
        container.visibility.toModel()?.let { result = result.narrowedBy(it) }
        container = container.containingDeclaration
    }
    return result
}

/**
 * [effectiveVisibility] from modifiers alone, for a declaration that did not resolve: the same walk
 * over the enclosing classes. The one thing it cannot see is an `override` inheriting `protected`,
 * which it reports as PUBLIC.
 */
internal fun psiEffectiveVisibility(declaration: KtDeclaration): Visibility {
    var result = declaredVisibility(declaration)
    var container = declaration.containingClassOrObject
    while (container != null) {
        result = result.narrowedBy(declaredVisibility(container))
        container = container.containingClassOrObject
    }
    return result
}

private fun declaredVisibility(declaration: KtDeclaration): Visibility = when {
    declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) -> Visibility.PRIVATE
    declaration.hasModifier(KtTokens.PROTECTED_KEYWORD) -> Visibility.PROTECTED
    declaration.hasModifier(KtTokens.INTERNAL_KEYWORD) -> Visibility.INTERNAL
    else -> Visibility.PUBLIC
}

/** `PACKAGE_*` are Java's, and only Kotlin files are indexed; `UNKNOWN` is a resolution failure. */
private fun KaSymbolVisibility.toModel(): Visibility? = when (this) {
    KaSymbolVisibility.PUBLIC -> Visibility.PUBLIC
    KaSymbolVisibility.PROTECTED -> Visibility.PROTECTED
    KaSymbolVisibility.INTERNAL -> Visibility.INTERNAL
    KaSymbolVisibility.PRIVATE, KaSymbolVisibility.LOCAL -> Visibility.PRIVATE
    KaSymbolVisibility.PACKAGE_PRIVATE, KaSymbolVisibility.PACKAGE_PROTECTED, KaSymbolVisibility.UNKNOWN -> null
}
