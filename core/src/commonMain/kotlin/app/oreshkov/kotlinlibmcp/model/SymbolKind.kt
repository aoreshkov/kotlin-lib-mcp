package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kind of declaration an [ApiSymbol] represents. */
@Serializable
public enum class SymbolKind {
    @SerialName("class")
    CLASS,

    @SerialName("interface")
    INTERFACE,

    @SerialName("object")
    OBJECT,

    @SerialName("enum")
    ENUM,

    @SerialName("annotation")
    ANNOTATION,

    @SerialName("function")
    FUNCTION,

    @SerialName("property")
    PROPERTY,

    @SerialName("constructor")
    CONSTRUCTOR,

    @SerialName("typealias")
    TYPEALIAS,
}
