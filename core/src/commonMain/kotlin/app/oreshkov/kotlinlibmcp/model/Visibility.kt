package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Declared visibility of an API symbol. Tools filter on this (e.g. public-only listings). */
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
