package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.SourceAnalyzer
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * `fetch_library` reports the on-disk source root only to a client that can actually use it.
 *
 * A stdio server is launched *by* its client and shares its filesystem, so handing back
 * `extractedDir` lets an agent with file tools read and diff sources directly instead of paging
 * them through `get_source`. An HTTP client may be on another machine: there the same string is
 * useless to the caller and discloses the server's layout for nothing, so it must stay `null`.
 */
class ExtractedDirExposureTest {

    private val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
    private val root = "/var/cache/kotlin-lib-mcp/io.ktor/ktor-client-core/3.5.1/sources"

    private class RootFetcher(private val extractedDir: String) : MavenSourceFetcher {
        override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult =
            FetchResult(
                coordinate = coordinate,
                resolvedTargets = emptyList(),
                downloadedJars = emptyList(),
                extractedDir = extractedDir,
            )

        override suspend fun fetchVersionCatalog(
            group: String,
            artifact: String,
            repos: List<String>,
        ): VersionCatalog = VersionCatalog(versions = emptyList())

        override suspend fun resolveDependencies(
            coordinate: LibraryCoordinate,
            repos: List<String>,
            maxDepth: Int,
        ): DependencyNode = throw UnsupportedOperationException("not used")
    }

    private class RecordingAnalyzer : SourceAnalyzer {
        override fun analyze(
            coordinate: LibraryCoordinate,
            sourceRoots: List<String>,
            classpathRoots: List<String>,
        ): LibraryIndex = LibraryIndex(coordinate = coordinate, fetchedAt = Instant.fromEpochSeconds(0))
    }

    private class InMemoryCache : LibraryCache {
        private val indexes = ConcurrentHashMap<LibraryCoordinate, LibraryIndex>()
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = indexes[coordinate]
        override suspend fun putIndex(index: LibraryIndex) {
            indexes[index.coordinate] = index
        }

        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = indexes.keys.toList()
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    private fun service(exposeLocalPaths: Boolean) = LibraryService(
        fetcher = RootFetcher(root),
        analyzer = RecordingAnalyzer(),
        cache = InMemoryCache(),
        exposeLocalPaths = exposeLocalPaths,
    )

    /** What the service normalizes the root to; compared against so the test is platform-neutral. */
    private val normalizedRoot: String = Path.of(root).normalize().toString()

    @Test
    fun stdioGetsTheSourceRootOnAFreshFetch() = runTest {
        val summary = service(exposeLocalPaths = true).fetchLibrary(coordinate)

        assertEquals(normalizedRoot, assertNotNull(summary.extractedDir))
    }

    @Test
    fun stdioAlsoGetsItOnAWarmCacheHit() = runTest {
        // The warm path returns early from the cache, so it has to resolve the root itself — the
        // field would otherwise be present on the first call of a session and absent on every
        // one after it, which is worse than never having it.
        val service = service(exposeLocalPaths = true)

        service.fetchLibrary(coordinate)
        val warm = service.fetchLibrary(coordinate)

        assertEquals(true, warm.fromCache, "second call must be the warm path")
        assertEquals(normalizedRoot, assertNotNull(warm.extractedDir))
    }

    @Test
    fun httpNeverLearnsTheServersFilesystemLayout() = runTest {
        val service = service(exposeLocalPaths = false)

        val fresh = service.fetchLibrary(coordinate)
        val warm = service.fetchLibrary(coordinate)

        assertNull(fresh.extractedDir, "a remote caller gets no server-side path")
        assertNull(warm.extractedDir, "not on the warm path either")
    }

    @Test
    fun onlyStdioIsTreatedAsSharingOurFilesystem() {
        // The factory maps the transport to the policy; this is that mapping. Unknown names must
        // fall to the safe side, so adding a transport cannot start leaking paths by default.
        assertEquals(true, sharesFilesystemWithClient("stdio"))
        assertEquals(false, sharesFilesystemWithClient("http"))
        assertEquals(false, sharesFilesystemWithClient("sse"))
        assertEquals(false, sharesFilesystemWithClient(""))
        assertEquals(false, sharesFilesystemWithClient("STDIO"), "the config value is lowercased by Main")
    }

    @Test
    fun theDefaultIsNotToExpose() = runTest {
        // `exposeLocalPaths` defaults to false, so a construction site that forgets to pass it
        // withholds the path rather than leaking it. Fakes.kt's `fakeService` relies on this.
        val service = LibraryService(
            fetcher = RootFetcher(root),
            analyzer = RecordingAnalyzer(),
            cache = InMemoryCache(),
        )

        assertNull(service.fetchLibrary(coordinate).extractedDir)
    }
}
