package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Maven coordinate identifying a published artifact, e.g. `io.ktor:ktor-client-core:3.5.1`.
 *
 * The canonical string form is `group:artifact:version`; use [parse]/[parseOrNull] to go the
 * other way. This is the primary cache key throughout the project.
 */
@Serializable
@SerialName("LibraryCoordinate")
public data class LibraryCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    init {
        requireValidSegment("group", group)
        requireValidSegment("artifact", artifact)
        requireValidSegment("version", version)
    }

    /** Canonical `group:artifact:version` form. */
    override fun toString(): String = "$group:$artifact:$version"

    public companion object {
        /**
         * Characters permitted in a coordinate segment — Maven's own charset. Deliberately
         * excludes the path separators `/` and `\`, so a segment can never introduce an extra
         * path component.
         */
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9._+-]+")

        /**
         * Rejects a coordinate segment ([kind] = group/artifact/version) that is unsafe to splice
         * into a filesystem path (the on-disk cache under [CacheLayout]) or a repository URL path.
         * The segments are attacker-influenced — a client/LLM chooses the coordinate — so a value
         * like `..` or `../../etc` must not be allowed to escape the cache root or traverse the
         * repo URL. Enforced from [init], so *every* constructed coordinate is validated.
         *
         * @throws IllegalArgumentException on a blank, `.`/`..`, or out-of-charset segment.
         */
        public fun requireValidSegment(kind: String, value: String) {
            require(value.isNotBlank()) { "$kind must not be blank" }
            require(value != "." && value != "..") { "$kind must not be '.' or '..': '$value'" }
            require(SAFE_SEGMENT.matches(value)) {
                "$kind '$value' has characters outside the allowed set [A-Za-z0-9._+-]"
            }
        }

        /**
         * Parses a `group:artifact:version` string.
         *
         * @throws IllegalArgumentException if [coordinate] is not exactly three non-blank,
         *   colon-separated parts, or if any part is not a [valid segment][requireValidSegment].
         */
        public fun parse(coordinate: String): LibraryCoordinate {
            val parts = coordinate.split(':')
            require(parts.size == 3) {
                "Invalid coordinate '$coordinate': expected 'group:artifact:version'"
            }
            val (group, artifact, version) = parts
            return LibraryCoordinate(group.trim(), artifact.trim(), version.trim())
        }

        /** Like [parse], but returns `null` instead of throwing on an invalid form. */
        public fun parseOrNull(coordinate: String): LibraryCoordinate? =
            runCatching { parse(coordinate) }.getOrNull()
    }
}
