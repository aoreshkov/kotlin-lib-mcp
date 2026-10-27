package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * *Effective* visibility of an API symbol: the narrowest on the way out through its enclosing
 * classes, so the members of an `internal class` are [INTERNAL] whatever their own modifier says.
 * Tools filter on this (e.g. public-only listings).
 */
@Serializable
public enum class Visibility {
    @SerialName("public")
    PUBLIC,

    @SerialName("internal")
    INTERNAL,

    @SerialName("protected")
    PROTECTED,

    @SerialName("private")
    PRIVATE,
}
