package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single KDoc block tag, e.g. `@param name the value` → `KDocTag("param", "name the value")`,
 * or `@return …`, `@throws …`, `@sample …`. The [name] is the tag without its leading `@`.
 */
@Serializable
@SerialName("KDocTag")
public data class KDocTag(
    val name: String,
    val value: String,
)

/**
 * Parsed KDoc for a declaration: the first-sentence [summary], the remaining free-form
 * [description], and the structured block [tags].
 */
@Serializable
@SerialName("KDoc")
public data class KDoc(
    val summary: String,
    val description: String? = null,
    val tags: List<KDocTag> = emptyList(),
)
