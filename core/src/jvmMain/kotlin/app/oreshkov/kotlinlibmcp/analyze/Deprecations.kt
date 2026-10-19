@file:OptIn(KaExperimentalApi::class)

package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.model.Deprecation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.kotlin.resolve.deprecation.DeprecationLevelValue

/*
 * Deprecations are read two ways, like signatures (see SignatureRenderer.kt).
 *
 * Resolved: *whether* a declaration is deprecated, and at what level, is the compiler's own verdict,
 * `deprecationStatus` — it accounts for `@DeprecatedSinceKotlin`, use-site targets and deprecations
 * inherited through overrides. Everything else comes from the resolved `kotlin.Deprecated`
 * annotation, whose arguments are already constant-evaluated. That includes the message:
 * `deprecationStatus` never carries one in K2 (`KaFirSymbolInformationProvider` passes `null`
 * deliberately, KT-67823), so a deprecation not declared on the symbol itself has none.
 *
 * PSI: the `@Deprecated` annotation's literal text, used only when the symbol could not be resolved
 * at all.
 */

// `StandardClassIds.Annotations.ParameterNames` names `message` and `level`, but nothing of ReplaceWith.
private val replaceWithName = Name.identifier("replaceWith")
private val expressionName = Name.identifier("expression")
private val importsName = Name.identifier("imports")

/** The compiler's deprecation verdict for [symbol]; must be called inside `analyze { }`. */
internal fun KaSession.resolvedDeprecation(symbol: KaDeclarationSymbol): Deprecation? {
    val status = symbol.deprecationStatus ?: return null
    val annotation = symbol.annotations[StandardClassIds.Annotations.Deprecated].firstOrNull()
    val replaceWith = annotation
        ?.argument(replaceWithName)
        .let { it as? KaAnnotationValue.NestedAnnotationValue }
        ?.annotation
    return Deprecation(
        level = when (status.deprecationLevel) {
            DeprecationLevelValue.WARNING -> Deprecation.Level.WARNING
            DeprecationLevelValue.ERROR -> Deprecation.Level.ERROR
            DeprecationLevelValue.HIDDEN -> Deprecation.Level.HIDDEN
        },
        message = annotation?.argument(StandardClassIds.Annotations.ParameterNames.deprecatedMessage)
            ?.stringValues()?.firstOrNull()?.takeIf(String::isNotBlank),
        replaceWith = replaceWith?.argument(expressionName)?.stringValues()?.firstOrNull()?.takeIf(String::isNotBlank),
        replaceWithImports = replaceWith?.argument(importsName)?.stringValues().orEmpty(),
    )
}

private fun KaAnnotation.argument(name: Name): KaAnnotationValue? =
    arguments.firstOrNull { it.name == name }?.expression

/** A string constant, or each string of an array (`imports` is a vararg). */
private fun KaAnnotationValue.stringValues(): List<String> = when (this) {
    is KaAnnotationValue.ConstantValue -> listOfNotNull((value as? KaConstantValue.StringValue)?.value)
    is KaAnnotationValue.ArrayValue -> values.flatMap { it.stringValues() }
    else -> emptyList()
}

/**
 * PSI-only fallback: `@Deprecated(message, replaceWith, level)` read from its source text. Only
 * plain string literals are understood — a message built from a constant or a template is dropped
 * rather than guessed at — and the annotation is matched by short name, so it cannot tell
 * `kotlin.Deprecated` from a same-named annotation of the library's own. Both are acceptable on a
 * symbol already marked `bestEffort`.
 */
internal fun psiDeprecation(declaration: KtAnnotated): Deprecation? {
    val entry = declaration.annotationEntries.firstOrNull { it.shortName?.asString() == "Deprecated" }
        ?: return null
    val arguments = entry.valueArguments
    val replaceWith = arguments.argument(1, replaceWithName)?.unqualified() as? KtCallExpression
    val replaceWithArguments = replaceWith?.valueArguments.orEmpty()
    val namedImports = replaceWithArguments.firstOrNull { it.argumentName() == importsName }
    return Deprecation(
        level = arguments.argument(2, StandardClassIds.Annotations.ParameterNames.deprecatedLevel)
            ?.text
            ?.substringAfterLast('.')
            ?.let { name -> Deprecation.Level.entries.firstOrNull { it.name == name } }
            ?: Deprecation.Level.WARNING,
        message = arguments.argument(0, StandardClassIds.Annotations.ParameterNames.deprecatedMessage)
            ?.literalString()
            ?.takeIf(String::isNotBlank),
        replaceWith = replaceWithArguments.argument(0, expressionName)?.literalString()?.takeIf(String::isNotBlank),
        replaceWithImports = when (namedImports) {
            null -> replaceWithArguments.drop(1).filter { it.argumentName() == null }
                .mapNotNull { it.getArgumentExpression()?.literalString() }
            else -> namedImports.getArgumentExpression()?.literalStrings().orEmpty()
        },
    )
}

private fun ValueArgument.argumentName(): Name? = getArgumentName()?.asName

/** The argument named [name], or else the one at [position] when the call has no named arguments before it. */
private fun List<ValueArgument>.argument(position: Int, name: Name): KtExpression? {
    firstOrNull { it.argumentName() == name }?.let { return it.getArgumentExpression() }
    val positional = getOrNull(position)?.takeIf { it.argumentName() == null } ?: return null
    return positional.takeIf { take(position).none { it.argumentName() != null } }?.getArgumentExpression()
}

/** `kotlin.ReplaceWith(…)` → `ReplaceWith(…)`. */
private fun KtExpression.unqualified(): KtExpression? = (this as? KtQualifiedExpression)?.selectorExpression ?: this

/** The value of an interpolation-free string literal; `null` for anything that needs evaluating. */
private fun KtExpression.literalString(): String? {
    val template = this as? KtStringTemplateExpression ?: return null
    if (template.hasInterpolation()) return null
    return buildString {
        for (entry in template.entries) {
            when (entry) {
                is KtLiteralStringTemplateEntry -> append(entry.text)
                is KtEscapeStringTemplateEntry -> append(entry.unescapedValue)
                else -> return null
            }
        }
    }
}

/** `"a"`, `["a", "b"]` or `arrayOf("a", "b")`, as a vararg may be passed by name. */
private fun KtExpression.literalStrings(): List<String> = when (val expression = unqualified()) {
    is KtCollectionLiteralExpression -> expression.getInnerExpressions().mapNotNull { it.literalString() }
    is KtCallExpression -> expression.valueArguments.mapNotNull { it.getArgumentExpression()?.literalString() }
    else -> listOfNotNull(literalString())
}
