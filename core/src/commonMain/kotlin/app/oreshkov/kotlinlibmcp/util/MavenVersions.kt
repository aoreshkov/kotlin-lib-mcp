package app.oreshkov.kotlinlibmcp.util

/**
 * Pure, platform-agnostic version-string logic used to pick the "latest" release of an artifact.
 * No IO — testable in `commonTest`. This is a pragmatic subset of Maven's `ComparableVersion`
 * semantics: enough to order real-world Maven/Gradle versions and to tell a stable release from a
 * pre-release, without depending on the full Maven artifact.
 */
public object MavenVersions {

    /**
     * Qualifier tokens that mark a **pre-release**. Detection is keyword-based (not "any hyphen")
     * so classifier releases like Guava's `33.x-jre`/`-android` stay stable.
     */
    private val PRE_RELEASE_KEYWORDS: Set<String> = setOf(
        "alpha", "beta", "milestone", "m", "rc", "cr",
        "snapshot", "dev", "eap", "preview", "pre", "ea",
    )

    /**
     * Known qualifier ordering (oldest → newest). The empty string is the release itself; anything
     * before it is a pre-release, `sp` is a service-pack after it. Unknown qualifiers sort after all
     * known ones (Maven-ish), compared lexicographically among themselves.
     */
    private val KNOWN_QUALIFIERS: List<String> =
        listOf("alpha", "beta", "milestone", "rc", "snapshot", "", "sp")

    /** `true` when [version] carries no pre-release qualifier (a normal stable release). */
    public fun isStable(version: String): Boolean =
        tokenize(version).none { it in PRE_RELEASE_KEYWORDS }

    /**
     * Orders version strings oldest → newest. Numeric segments compare numerically; a segment with
     * no qualifier outranks a pre-release qualifier at the same base (so `1.0.0` > `1.0.0-rc1`).
     */
    public val VERSION_COMPARATOR: Comparator<String> = Comparator { a, b -> compare(a, b) }

    /**
     * The newest version in [versions], or `null` when empty. When [includePreReleases] is `false`
     * (default) only stable releases are considered, so this returns `null` if every candidate is a
     * pre-release — callers decide how to fall back.
     */
    public fun selectLatest(versions: List<String>, includePreReleases: Boolean = false): String? {
        val pool = if (includePreReleases) versions else versions.filter(::isStable)
        return pool.maxWithOrNull(VERSION_COMPARATOR)
    }

    // --- internals ---

    private fun compare(a: String, b: String): Int {
        val ai = tokenize(a)
        val bi = tokenize(b)
        val n = maxOf(ai.size, bi.size)
        for (i in 0 until n) {
            val c = compareItem(ai.getOrNull(i), bi.getOrNull(i))
            if (c != 0) return c
        }
        return 0
    }

    private fun compareItem(a: String?, b: String?): Int = when {
        a == null && b == null -> 0
        a == null -> -comparePresentVsMissing(b!!)
        b == null -> comparePresentVsMissing(a)
        isNumeric(a) && isNumeric(b) -> compareNumeric(a, b)
        isNumeric(a) -> 1 // a numeric segment outranks a qualifier
        isNumeric(b) -> -1
        else -> compareQualifier(a, b)
    }

    /** Compares a present segment against a missing one: numeric pads with `0`, qualifier with release. */
    private fun comparePresentVsMissing(x: String): Int =
        if (isNumeric(x)) compareNumeric(x, "0") else compareQualifier(x, "")

    private fun compareNumeric(a: String, b: String): Int {
        val na = a.trimStart('0').ifEmpty { "0" }
        val nb = b.trimStart('0').ifEmpty { "0" }
        return if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
    }

    private fun compareQualifier(a: String, b: String): Int {
        val (ra, resA) = qualifierRank(a)
        val (rb, resB) = qualifierRank(b)
        return if (ra != rb) ra - rb else resA.compareTo(resB)
    }

    /** Rank of a qualifier: known qualifiers by [KNOWN_QUALIFIERS] index, unknown ones after all known. */
    private fun qualifierRank(qualifier: String): Pair<Int, String> {
        val normalized = when (qualifier) {
            "a" -> "alpha"
            "b" -> "beta"
            "m" -> "milestone"
            "cr" -> "rc"
            "ga", "final", "release" -> ""
            else -> qualifier
        }
        val index = KNOWN_QUALIFIERS.indexOf(normalized)
        return if (index >= 0) index to "" else KNOWN_QUALIFIERS.size to normalized
    }

    private fun isNumeric(token: String): Boolean = token.isNotEmpty() && token.all { it.isDigit() }

    /**
     * Splits a version into comparable segments: on `.`/`-`/`_`/`+`, and at every digit↔letter
     * boundary (so `1.0rc1` → `1`, `0`, `rc`, `1`). Lowercased for case-insensitive qualifiers.
     */
    private fun tokenize(version: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var lastWasDigit: Boolean? = null
        fun flush() {
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.clear()
            }
            lastWasDigit = null
        }
        for (c in version.lowercase()) {
            when (c) {
                '.', '-', '_', '+' -> flush()
                else -> {
                    val isDigit = c.isDigit()
                    if (lastWasDigit != null && isDigit != lastWasDigit) flush()
                    current.append(c)
                    lastWasDigit = isDigit
                }
            }
        }
        flush()
        return tokens
    }
}
