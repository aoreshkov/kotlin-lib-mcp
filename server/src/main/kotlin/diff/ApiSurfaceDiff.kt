package app.oreshkov.kotlinlibmcp.server.diff

import app.oreshkov.kotlinlibmcp.dto.ApiChange
import app.oreshkov.kotlinlibmcp.dto.ApiChangeType
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility

/**
 * The public-API difference between two indexes of one artifact. A pure function over the cached
 * indexes, like [LineDiff], so it reads no source and is tested on in-memory indexes.
 *
 * Each step below exists because the obvious version reports churn that is not API:
 *  - **Effective visibility.** An index records each declaration's *own* modifier, so the members
 *    of an `internal class` read as public. A declaration counts only if it and every enclosing
 *    classifier are public or protected.
 *  - **Grouped by FQ name, not by index key.** Keys carry a `#n` overload suffix assigned in
 *    file/offset order, which a release that adds one overload or moves a file renumbers.
 *  - **Compared by normalized signature** ([normalizeSignature]), deduplicated per group. A KMP
 *    index lists a common declaration once per target tree, and the same declaration may move
 *    between `expect` and plain common code; neither is an API change.
 *  - **An `actual` defers to whatever else declares the name.** Its rendering differs from the
 *    `expect` it implements (an inherited default renders without its value), so keeping both would
 *    report a phantom removal the day a library replaces `expect`/`actual` with common code.
 *
 * Within a group, the signatures only one side has are paired when there is exactly one on each
 * side ([ApiChangeType.CHANGED]); any other shape is reported as individual removals and additions,
 * since guessing which of several overloads became which would be worse than saying so.
 */
internal object ApiSurfaceDiff {

    fun compare(from: LibraryIndex, to: LibraryIndex, packageName: String? = null): ApiComparison {
        val before = apiOf(from, packageName)
        val after = apiOf(to, packageName)
        val changes = buildList {
            for (key in before.keys + after.keys) {
                classify(before[key].orEmpty(), after[key].orEmpty())
            }
        }
        val packagesBefore = before.packages()
        val packagesAfter = after.packages()
        return ApiComparison(
            changes = changes.sortedWith(ORDER),
            packagesAdded = (packagesAfter - packagesBefore).sorted(),
            packagesRemoved = (packagesBefore - packagesAfter).sorted(),
        )
    }

    private fun apiOf(index: LibraryIndex, packageName: String?): Api {
        val symbols = index.symbolsByFqName.values
        // A classifier hides its members only when no copy of it is visible: an index can hold the
        // same class several times over (per target tree, expect and actual).
        val hiddenClassifiers = symbols
            .filter { it.kind in CLASSIFIERS }
            .groupBy { it.fqName }
            .filterValues { copies -> copies.none { it.visibility in API_VISIBILITY } }
            .keys
        return symbols.asSequence()
            .filter { it.visibility in API_VISIBILITY && !it.isInsideAny(hiddenClassifiers) }
            .filter { packageName == null || it.packageName == packageName }
            .groupBy { GroupKey(it.fqName, it.kind in CLASSIFIERS) }
            .mapValues { (_, group) -> distinctSignatures(group) }
    }

    private fun ApiSymbol.isInsideAny(hidden: Set<String>): Boolean {
        var end = fqName.lastIndexOf('.')
        while (end > 0) {
            if (fqName.substring(0, end) in hidden) return true
            end = fqName.lastIndexOf('.', end - 1)
        }
        return false
    }

    private fun distinctSignatures(group: List<ApiSymbol>): Map<String, ApiSymbol> {
        val declared = when {
            group.any { ACTUAL !in it.modifiers } -> group.filterNot { ACTUAL in it.modifiers }
            else -> group
        }
        // Resolved before best-effort, then by path, so the reported copy is the most exact one and
        // the same one on every run.
        return declared
            .sortedWith(compareBy<ApiSymbol>({ it.bestEffort }, { it.sourceRef?.file?.path }))
            .groupBy { normalizeSignature(it.signature) }
            .mapValues { (_, copies) -> copies.first() }
    }

    private fun MutableList<ApiChange>.classify(before: Map<String, ApiSymbol>, after: Map<String, ApiSymbol>) {
        val gone = (before.keys - after.keys).map(before::getValue)
        val new = (after.keys - before.keys).map(after::getValue)
        // A name that vanished outright was removed however its text was read; only a pairing
        // across both sides can be fooled by best-effort formatting.
        val approximate = before.isNotEmpty() && after.isNotEmpty() && (gone + new).any { it.bestEffort }
        if (gone.size == 1 && new.size == 1) {
            val old = gone.single()
            val now = new.single()
            this += ApiChange(
                fqName = now.fqName,
                kind = now.kind,
                change = ApiChangeType.CHANGED,
                fromSignature = old.signature,
                toSignature = now.signature,
                deprecation = now.deprecation,
                approximate = approximate,
            )
        } else {
            gone.mapTo(this) { old ->
                ApiChange(
                    fqName = old.fqName,
                    kind = old.kind,
                    change = ApiChangeType.REMOVED,
                    fromSignature = old.signature,
                    deprecation = old.deprecation,
                    approximate = approximate,
                )
            }
            new.mapTo(this) { now ->
                ApiChange(
                    fqName = now.fqName,
                    kind = now.kind,
                    change = ApiChangeType.ADDED,
                    toSignature = now.signature,
                    deprecation = now.deprecation,
                    approximate = approximate,
                )
            }
        }
        for (signature in before.keys intersect after.keys) {
            val old = before.getValue(signature)
            val now = after.getValue(signature)
            val deprecation = now.deprecation ?: continue
            val previous = old.deprecation
            if (previous == null || deprecation.level > previous.level) {
                this += ApiChange(
                    fqName = now.fqName,
                    kind = now.kind,
                    change = ApiChangeType.DEPRECATED,
                    toSignature = now.signature,
                    deprecation = deprecation,
                    approximate = old.bestEffort || now.bestEffort,
                )
            }
        }
    }

    private fun Api.packages(): Set<String> =
        values.asSequence().flatMap { it.values }.mapNotNull { it.packageName }.toSet()

    private val ApiSymbol.packageName: String? get() = sourceRef?.file?.packageName

    private val API_VISIBILITY = setOf(Visibility.PUBLIC, Visibility.PROTECTED)

    private val CLASSIFIERS = setOf(
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM,
        SymbolKind.ANNOTATION, SymbolKind.TYPEALIAS,
    )

    private const val ACTUAL = "actual"

    /** Deterministic pages: by name, then change type, then signature to order overloads. */
    private val ORDER = compareBy<ApiChange>({ it.fqName }, { it.change }, { it.fromSignature ?: it.toSignature })
}

/** A classifier and a callable may share an FQ name (`class HttpClient`, `fun HttpClient()`). */
private data class GroupKey(val fqName: String, val classifier: Boolean)

/** Each group's distinct normalized signatures, mapped to the symbol reported for it. */
private typealias Api = Map<GroupKey, Map<String, ApiSymbol>>

/** [ApiSurfaceDiff.compare]'s answer: every change, sorted, plus the packages only one side has. */
internal data class ApiComparison(
    val changes: List<ApiChange>,
    val packagesAdded: List<String>,
    val packagesRemoved: List<String>,
)

/**
 * [signature] reduced to what makes it API, so that two renderings of one declaration compare equal:
 *  - whitespace squashed, since the best-effort path keeps the source's own line breaks;
 *  - leading annotations dropped — a deprecation is reported through its own field rather than as a
 *    changed signature, and opt-in markers are not modelled at all;
 *  - `expect`/`actual` dropped from the modifiers, so a declaration that moves between `expect` and
 *    plain common code is unchanged.
 *
 * An annotation list it cannot parse (unbalanced parentheses, an unterminated string) is left in
 * place rather than guessed at: a signature that keeps its annotations can only over-report.
 */
internal fun normalizeSignature(signature: String): String {
    val squashed = signature.replace(WHITESPACE, " ").trim()
    val declaration = squashed.substring(leadingAnnotationsEnd(squashed) ?: 0)
    val tokens = declaration.split(' ')
    val keyword = tokens.indexOfFirst { it in DECLARATION_KEYWORDS }
    if (keyword < 0) return declaration
    return tokens.filterIndexed { i, token -> i >= keyword || token !in PLATFORM_MODIFIERS }.joinToString(" ")
}

private val WHITESPACE = Regex("""\s+""")

private val DECLARATION_KEYWORDS = setOf("fun", "val", "var", "class", "interface", "object", "typealias")

private val PLATFORM_MODIFIERS = setOf("expect", "actual")

/**
 * Index just past the annotations [text] starts with (and the space after them), `0` when it starts
 * with none, or `null` when they do not parse. Handles `@Name`, `@a.b.Name`, `@get:Name`,
 * `` @`<error>` `` and an argument list, whose string literals may contain parentheses.
 */
private fun leadingAnnotationsEnd(text: String): Int? {
    var i = 0
    while (i < text.length && text[i] == '@') {
        i++
        while (i < text.length) {
            val c = text[i]
            i = when {
                c == '`' -> text.indexOf('`', i + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                c.isLetterOrDigit() || c == '_' || c == '.' || c == ':' -> i + 1
                else -> break
            }
        }
        if (i < text.length && text[i] == '(') i = closingParenthesisEnd(text, i) ?: return null
        while (i < text.length && text[i] == ' ') i++
    }
    return i
}

/** Index just past the `)` matching the `(` at [open], skipping string and char literals. */
private fun closingParenthesisEnd(text: String, open: Int): Int? {
    var depth = 0
    var i = open
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i + 1
            '"' -> {
                i = stringLiteralEnd(text, i) ?: return null
                continue
            }
            '\'' -> {
                i = quotedEnd(text, i, '\'') ?: return null
                continue
            }
        }
        i++
    }
    return null
}

private fun stringLiteralEnd(text: String, start: Int): Int? =
    if (text.startsWith("\"\"\"", start)) {
        text.indexOf("\"\"\"", start + 3).takeIf { it >= 0 }?.plus(3)
    } else {
        quotedEnd(text, start, '"')
    }

/** Index just past the [quote] closing the literal opened at [start], honouring backslash escapes. */
private fun quotedEnd(text: String, start: Int, quote: Char): Int? {
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            else -> i++
        }
    }
    return null
}
