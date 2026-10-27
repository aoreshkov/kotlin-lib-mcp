package app.oreshkov.kotlinlibmcp.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/**
 * Writes [bytes] to this path so a reader sees either the old file or the whole new one, never a
 * prefix: the bytes go to a uniquely named temp file in the same directory, which is then moved over
 * the target atomically. Same directory because an atomic move cannot cross file stores.
 *
 * The cache root is shared by every server on the machine (a Claude Code and a Claude Desktop
 * server, say), so a plain `writeBytes` lets one process read a jar, marker or index the other is
 * halfway through writing.
 */
internal fun Path.writeAtomically(bytes: ByteArray) {
    val dir = toAbsolutePath().parent.createDirectories()
    val temp = Files.createTempFile(dir, ".$name.", ".tmp")
    try {
        temp.writeBytes(bytes)
        Files.move(temp, this, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (e: Throwable) {
        temp.deleteIfExists()
        throw e
    }
}

internal fun Path.writeTextAtomically(text: String): Unit = writeAtomically(text.encodeToByteArray())
