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
        require(group.isNotBlank()) { "group must not be blank" }
        require(artifact.isNotBlank()) { "artifact must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
    }

    /** Canonical `group:artifact:version` form. */
    override fun toString(): String = "$group:$artifact:$version"

    public companion object {
        /**
         * Parses a `group:artifact:version` string.
         *
         * @throws IllegalArgumentException if [coordinate] is not exactly three non-blank,
         *   colon-separated parts.
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
