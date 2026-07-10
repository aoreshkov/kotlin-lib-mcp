package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.SourceAnalyzer
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex

/*
 * Offline collaborators for server tests: a fetcher that serves a canned version catalog and
 * analyzer/cache stand-ins that fail loudly if a test unexpectedly reaches them.
 */

internal class FakeFetcher(private val catalog: VersionCatalog) : MavenSourceFetcher {
    override suspend fun fetchVersionCatalog(group: String, artifact: String, repos: List<String>): VersionCatalog =
        catalog

    override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult =
        throw UnsupportedOperationException("not used")

    override suspend fun resolveDependencies(
        coordinate: LibraryCoordinate,
        repos: List<String>,
        maxDepth: Int,
    ): DependencyNode = throw UnsupportedOperationException("not used")
}

internal object UnusedAnalyzer : SourceAnalyzer {
    override fun analyze(
        coordinate: LibraryCoordinate,
        sourceRoots: List<String>,
        classpathRoots: List<String>,
    ): LibraryIndex = throw UnsupportedOperationException("not used")
}

internal object UnusedCache : LibraryCache {
    override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = null
    override suspend fun putIndex(index: LibraryIndex) = Unit
    override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
    override suspend fun list(): List<LibraryCoordinate> = emptyList()
    override suspend fun clear(coordinate: LibraryCoordinate) = Unit
    override suspend fun size(): Long = 0
}

/** A [LibraryService] wired entirely to the fakes above — no network, no analysis, no cache IO. */
internal fun fakeService(catalog: VersionCatalog = VersionCatalog(versions = emptyList())): LibraryService =
    LibraryService(
        fetcher = FakeFetcher(catalog),
        analyzer = UnusedAnalyzer,
        cache = UnusedCache,
        repos = emptyList(),
    )
