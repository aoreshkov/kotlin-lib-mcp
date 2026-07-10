package app.oreshkov.kotlinlibmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A source file inside an extracted library, addressed by its path relative to the extraction
 * root. Paths are plain [String]s in `commonMain` (no `java.io.File`); JVM code resolves them
 * against the cache dir. (A future upgrade could swap these for `kotlinx-io`'s `Path`.)
 */
@Serializable
@SerialName("SourceFileRef")
public data class SourceFileRef(
    val path: String,
    val packageName: String,
    val target: KmpTarget = KmpTarget.UNKNOWN,
)

/**
 * A precise location within a source file. [line] is 1-based; [offset] is a 0-based char index of
 * the declaration's start (after its KDoc). [endOffset] is the exclusive end of the declaration
 * text when known — `offset..<endOffset` slices the declaration out of the file (`get_source`).
 */
@Serializable
@SerialName("SourceLocation")
public data class SourceLocation(
    val file: SourceFileRef,
    val line: Int,
    val offset: Int,
    val endOffset: Int? = null,
)
