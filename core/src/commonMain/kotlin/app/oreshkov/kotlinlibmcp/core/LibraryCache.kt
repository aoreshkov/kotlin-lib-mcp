package app.oreshkov.kotlinlibmcp.core

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex

/**
 * Port for the on-disk cache keyed by `group/artifact/version`. Tools read through it so a warm
 * coordinate is never re-fetched or re-analyzed.
 *
 * All operations suspend: the JVM implementation (Phase 03) touches the filesystem, so suspending
 * keeps callers off blocking IO on their dispatcher and matches the suspend [MavenSourceFetcher].
 */
public interface LibraryCache {

    /** The cached index for [coordinate], or `null` on a cache miss. */
    public suspend fun get(coordinate: LibraryCoordinate): LibraryIndex?

    /** Stores (or replaces) the parsed index for a coordinate. */
    public suspend fun putIndex(index: LibraryIndex)

    /**
     * Records that the extracted sources for [coordinate] live at [extractedDir], so later phases
     * can serve raw source/search without re-downloading.
     */
    public suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String)

    /** Every coordinate currently held in the cache. */
    public suspend fun list(): List<LibraryCoordinate>

    /** Evicts a single coordinate's cached sources and index. */
    public suspend fun clear(coordinate: LibraryCoordinate)

    /** Total size of the cache on disk, in bytes. */
    public suspend fun size(): Long
}
