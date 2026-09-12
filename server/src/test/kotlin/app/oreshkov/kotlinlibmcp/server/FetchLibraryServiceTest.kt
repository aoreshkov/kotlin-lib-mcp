package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.SourceAnalyzer
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * `fetch_library`'s de-duplication: two concurrent calls for one coordinate download and analyze
 * once, without coupling the callers' lifetimes to each other.
 *
 * This matters because of SDK 0.15.0: `Protocol` dispatches inbound requests concurrently once the
 * session is initialized, so a model issuing `fetch_library` twice in one parallel tool block
 * genuinely runs both handlers at the same time. The analysis pass is the expensive half, so doing
 * it twice is the cost worth removing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FetchLibraryServiceTest {

    private val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
    private val other = LibraryCoordinate("io.ktor", "ktor-client-cio", "3.5.1")

    // --- doubles ---

    /**
     * A fetcher whose download parks until released, so a test can hold one caller mid-fetch and
     * observe what a second one does. [started] completes on the first entry into [fetch].
     */
    private class GatedFetcher : MavenSourceFetcher {
        val fetchCount = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()

        /** Released per coordinate; a test completes these to let the download finish. */
        val release = ConcurrentHashMap<LibraryCoordinate, CompletableDeferred<Unit>>()

        fun releaseSignal(coordinate: LibraryCoordinate): CompletableDeferred<Unit> =
            release.computeIfAbsent(coordinate) { CompletableDeferred() }

        override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult {
            fetchCount.incrementAndGet()
            started.complete(Unit)
            releaseSignal(coordinate).await()
            return FetchResult(
                coordinate = coordinate,
                resolvedTargets = emptyList(),
                downloadedJars = emptyList(),
                extractedDir = "/tmp/${coordinate.artifact}",
            )
        }

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

    private class CountingAnalyzer : SourceAnalyzer {
        val analyzeCount = AtomicInteger(0)

        override fun analyze(
            coordinate: LibraryCoordinate,
            sourceRoots: List<String>,
            classpathRoots: List<String>,
        ): LibraryIndex {
            analyzeCount.incrementAndGet()
            return LibraryIndex(coordinate = coordinate, fetchedAt = Instant.fromEpochSeconds(0))
        }
    }

    private class InMemoryCache : LibraryCache {
        private val indexes = ConcurrentHashMap<LibraryCoordinate, LibraryIndex>()

        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = indexes[coordinate]
        override suspend fun putIndex(index: LibraryIndex) {
            indexes[index.coordinate] = index
        }

        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = indexes.keys.toList()
        override suspend fun clear(coordinate: LibraryCoordinate) {
            indexes.remove(coordinate)
        }

        override suspend fun size(): Long = indexes.size.toLong()
    }

    private fun service(fetcher: MavenSourceFetcher, analyzer: SourceAnalyzer) =
        LibraryService(fetcher = fetcher, analyzer = analyzer, cache = InMemoryCache())

    // --- the de-duplication itself ---

    @Test
    fun concurrentFetchesOfOneCoordinateDownloadAndAnalyzeOnce() = runTest {
        val fetcher = GatedFetcher()
        val analyzer = CountingAnalyzer()
        val service = service(fetcher, analyzer)

        val first = async { service.fetchLibrary(coordinate) }
        fetcher.started.await() // `first` is now parked inside the download

        val second = async { service.fetchLibrary(coordinate) }
        // Give `second` every chance to run. It must block on the gate rather than start its own
        // download — asserting that *while the first is still parked* is what discriminates: on
        // the un-gated implementation it sails past into a second fetch and this reads 2.
        advanceUntilIdle()
        assertEquals(1, fetcher.fetchCount.get(), "the second caller must not start its own download")

        fetcher.releaseSignal(coordinate).complete(Unit)
        val firstSummary = first.await()
        val secondSummary = second.await()

        assertEquals(1, fetcher.fetchCount.get(), "downloaded once")
        assertEquals(1, analyzer.analyzeCount.get(), "analyzed once — the expensive half")
        assertEquals(coordinate, firstSummary.coordinate)
        assertEquals(coordinate, secondSummary.coordinate)
        // The winner did the work; the follower truthfully reports where its index came from.
        assertFalse(firstSummary.fromCache, "the caller that did the work did not read a cache")
        assertTrue(secondSummary.fromCache, "the follower returned the index the winner cached")
    }

    @Test
    fun fetchesOfDifferentCoordinatesDoNotWaitOnEachOther() = runTest {
        val fetcher = GatedFetcher()
        val service = service(fetcher, CountingAnalyzer())

        val first = async { service.fetchLibrary(coordinate) }
        val second = async { service.fetchLibrary(other) }

        // Both must be inside `fetch` at once. A single global lock (or a gate keyed on anything
        // coarser than the coordinate) would hold the second one out and this would read 1.
        advanceUntilIdle()
        assertEquals(2, fetcher.fetchCount.get(), "unrelated coordinates must download concurrently")

        fetcher.releaseSignal(coordinate).complete(Unit)
        fetcher.releaseSignal(other).complete(Unit)
        assertEquals(coordinate, first.await().coordinate)
        assertEquals(other, second.await().coordinate)
    }

    // --- lifetimes stay independent ---

    @Test
    fun cancellingTheWinnerLetsTheNextCallerFetchItself() = runTest {
        // The reason this is a gate and not a shared Deferred of the in-flight fetch: since SDK
        // 0.15.0 an inbound notifications/cancelled really cancels a tools/call handler. Handing
        // the follower the winner's Deferred would fail it too, for a request nobody withdrew.
        val fetcher = GatedFetcher()
        val analyzer = CountingAnalyzer()
        val service = service(fetcher, analyzer)

        val first = async { service.fetchLibrary(coordinate) }
        fetcher.started.await()
        val second = async { service.fetchLibrary(coordinate) }
        advanceUntilIdle()

        first.cancel() // the client withdrew the first tools/call
        advanceUntilIdle()

        // The follower now owns the gate and does the work on its own coroutine.
        assertEquals(2, fetcher.fetchCount.get(), "the follower took over and started its own fetch")
        fetcher.releaseSignal(coordinate).complete(Unit)

        val summary = second.await()
        assertEquals(coordinate, summary.coordinate)
        assertFalse(summary.fromCache, "the follower did the work itself after the winner withdrew")
        assertEquals(1, analyzer.analyzeCount.get())
    }

    // --- the gate map is not a leak ---

    @Test
    fun theGateMapIsPrunedSoCallerSuppliedCoordinatesCannotGrowIt() = runTest {
        // `coordinate` comes straight from tool arguments, so an entry per coordinate that is never
        // removed would be a slow leak driven by untrusted input.
        val fetcher = GatedFetcher()
        val service = service(fetcher, CountingAnalyzer())

        fetcher.releaseSignal(coordinate).complete(Unit)
        fetcher.releaseSignal(other).complete(Unit)
        service.fetchLibrary(coordinate)
        service.fetchLibrary(other)
        service.fetchLibrary(coordinate) // warm hit: never touches the map at all

        assertEquals(0, service.inFlightFetchCount, "every gate is dropped by its last holder")
    }

    @Test
    fun aFailedFetchDropsItsGateAndDoesNotPoisonTheNextCaller() = runTest {
        val failing = object : MavenSourceFetcher {
            val calls = AtomicInteger(0)
            override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult {
                calls.incrementAndGet()
                throw IllegalStateException("404 from the repository")
            }

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
        val service = service(failing, CountingAnalyzer())

        repeat(2) {
            runCatching { service.fetchLibrary(coordinate) }
        }

        // A transient failure must not be cached as "already tried": the retry really retries.
        assertEquals(2, failing.calls.get(), "a failed fetch is retryable")
        assertEquals(0, service.inFlightFetchCount, "a throwing fetch still releases its gate")
    }
}
