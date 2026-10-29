package app.oreshkov.kotlinlibmcp.io

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
     * What extraction may still write: entries and uncompressed bytes, counted down by every
     * [extract] it is passed to. Sharing one across several archives makes the limits hold for all of
     * them together. That matters because a bound per archive bounds nothing once the number of
     * archives is someone else's choice: a fetch extracts every jar a `.module` lists. Not thread-safe;
     * pass it to one [extract] at a time.
     */
    public class Budget internal constructor(
        internal val maxEntries: Int,
        internal val maxTotalBytes: Long,
    ) {
        internal var entries: Int = 0
        internal var bytes: Long = 0L
    }

    /** A fresh [Budget] of this extractor's limits. */
    public fun newBudget(): Budget = Budget(maxEntries, maxTotalBytes)

    /**
     * Extracts [archive] into [targetDir] (created if absent) and returns the extracted files'
     * paths relative to [targetDir], `/`-separated. Whatever it writes is charged to [budget].
     *
     * Cancellable: every copied chunk checks for cancellation, so a withdrawn fetch stops writing
     * within one buffer instead of finishing the archive (up to [maxTotalBytes]) on an IO thread
     * nobody is waiting for, while the caller's fetch gate stays held.
     *
     * @throws ZipExtractionException on a traversal attempt or when limits are exceeded.
     */
    public suspend fun extract(archive: Path, targetDir: Path, budget: Budget = newBudget()): List<String> =
        withContext(Dispatchers.IO) {
            val root = targetDir.toAbsolutePath().normalize()
            root.createDirectories()
            val extracted = mutableListOf<String>()
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++budget.entries > budget.maxEntries) {
                        throw ZipExtractionException(
                            "$archive takes extraction past ${budget.maxEntries} entries; refusing to extract",
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
                        // Enforce the byte budget *during* the copy: a single highly-compressed
                        // entry must not stream in full before the cap is checked (zip-bomb).
                        dest.outputStream().use { out -> budget.bytes += copyBounded(zip, out, budget, archive, coroutineContext) }
                        extracted += dest.relativeTo(root).invariantSeparatorsPathString
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            extracted
        }

    /**
     * Copies the current zip entry into [target], aborting the moment the cumulative extracted size
     * (what [budget] has already spent + bytes copied here) would exceed its byte limit — before the
     * offending chunk is written, so an over-budget entry never fully lands on disk. Returns bytes
     * written.
     */
    private fun copyBounded(
        source: InputStream,
        target: OutputStream,
        budget: Budget,
        archive: Path,
        context: CoroutineContext,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var written = 0L
        while (true) {
            // Per chunk, not per entry: one entry may be hundreds of MB (it only has to fit
            // maxTotalBytes). Every file entry, even an empty one, passes here at least once.
            context.ensureActive()
            val read = source.read(buffer)
            if (read < 0) break
            if (budget.bytes + written + read > budget.maxTotalBytes) {
                throw ZipExtractionException(
                    "$archive takes extraction past ${budget.maxTotalBytes} bytes; refusing to extract",
                )
            }
            target.write(buffer, 0, read)
            written += read
        }
        return written
    }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 20_000
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 500L * 1024 * 1024

        private const val COPY_BUFFER_BYTES: Int = 64 * 1024
    }
}
