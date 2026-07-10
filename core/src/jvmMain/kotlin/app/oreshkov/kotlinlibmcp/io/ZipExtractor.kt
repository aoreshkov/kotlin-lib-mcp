package app.oreshkov.kotlinlibmcp.io

import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thrown when an archive is rejected (path traversal, entry/size limits) or unreadable. */
public class ZipExtractionException(message: String) : RuntimeException(message)

/**
 * Extracts jar/zip archives with the safeguards recommended for untrusted archives: a NIO
 * path-traversal (zip-slip) guard — every entry must normalize to a path inside the target
 * dir — plus bounds on entry count and total uncompressed size (zip-bomb). Package directory
 * structure is preserved.
 */
public class ZipExtractor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {

    /**
     * Extracts [archive] into [targetDir] (created if absent) and returns the extracted files'
     * paths relative to [targetDir], `/`-separated.
     *
     * @throws ZipExtractionException on a traversal attempt or when limits are exceeded.
     */
    public suspend fun extract(archive: Path, targetDir: Path): List<String> =
        withContext(Dispatchers.IO) {
            val root = targetDir.toAbsolutePath().normalize()
            root.createDirectories()
            val extracted = mutableListOf<String>()
            var entryCount = 0
            var totalBytes = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entryCount > maxEntries) {
                        throw ZipExtractionException(
                            "$archive has more than $maxEntries entries; refusing to extract",
                        )
                    }
                    val dest = root.resolve(entry.name).normalize()
                    if (!dest.startsWith(root)) {
                        throw ZipExtractionException(
                            "Path traversal attempt in $archive: entry '${entry.name}' escapes the target dir",
                        )
                    }
                    if (!entry.isDirectory) {
                        dest.parent?.createDirectories()
                        dest.outputStream().use { out -> totalBytes += zip.copyTo(out) }
                        if (totalBytes > maxTotalBytes) {
                            throw ZipExtractionException(
                                "$archive expands past $maxTotalBytes bytes; refusing to extract",
                            )
                        }
                        extracted += dest.relativeTo(root).invariantSeparatorsPathString
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            extracted
        }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 20_000
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 500L * 1024 * 1024
    }
}
