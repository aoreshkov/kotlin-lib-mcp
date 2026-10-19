package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A declaration's `@Deprecated` status, as the compiler sees it.
 *
 * [replaceWith] and [replaceWithImports] are `ReplaceWith`'s `expression` and `imports`: the
 * library author's own migration, which is what a caller should reach for before guessing one from
 * the KDoc. Both are copied verbatim, so the expression may refer to the declaration's parameters
 * by name (`ReplaceWith("newGreet(name)")`).
 */
@Serializable
@SerialName("Deprecation")
public data class Deprecation(
    val level: Level,
    val message: String? = null,
    val replaceWith: String? = null,
    val replaceWithImports: List<String> = emptyList(),
) {
    /**
     * Mirrors `kotlin.DeprecationLevel`, in declaration order, so [compareTo] is escalation order.
     * Nested rather than top-level so it never shadows the auto-imported stdlib name.
     */
    @Serializable
    public enum class Level {
        /** Use still compiles, with a warning. */
        @SerialName("warning")
        WARNING,

        /** Use no longer compiles; existing binaries still link. */
        @SerialName("error")
        ERROR,

        /** Invisible to new code; kept only for binary compatibility. */
        @SerialName("hidden")
        HIDDEN,
    }
}
