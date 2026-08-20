package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Covers `LibraryService.searchSource`: how often it resolves the source root, and that a
 * caller-supplied regex cannot run away.
 *
 * The fetch-count assertion is the point of the first test. `MavenSourceFetcherImpl.fetch` is a
 * cache-marker hit in the steady state, but not a free one — it reads and JSON-deserializes the
 * marker on *every* call — so resolving the root per file made a search cost one extra file read
 * and one extra parse per source file in the library.
 */
class SearchSourceServiceTest {

    private val coordinate = LibraryCoordinate("com.example", "demo", "1.0.0")
    private val tempDir: Path = Files.createTempDirectory("search-source-test")

    @AfterTest
    fun cleanUp() {
        tempDir.toFile().deleteRecursively()
    }

    /** Counts `fetch` calls; every other operation fails loudly. */
    private class CountingFetcher(private val extractedDir: String) : MavenSourceFetcher {
        var fetchCount: Int = 0
            private set

        override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult {
            fetchCount++
            return FetchResult(
                coordinate = coordinate,
                resolvedTargets = emptyList(),
                downloadedJars = emptyList(),
                extractedDir = extractedDir,
            )
        }

        override suspend fun fetchVersionCatalog(
            group: String,
            artifact: String,
            repos: List<String>,
        ): VersionCatalog = throw UnsupportedOperationException("not used")

        override suspend fun resolveDependencies(
            coordinate: LibraryCoordinate,
            repos: List<String>,
            maxDepth: Int,
        ): DependencyNode = throw UnsupportedOperationException("not used")
    }

    private class SingleIndexCache(private val index: LibraryIndex) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex = index
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = listOf(index.coordinate)
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    /** Writes [fileCount] source files and returns the service plus the fetcher counting its calls. */
    private fun serviceWith(fileCount: Int, line: String): Pair<LibraryService, CountingFetcher> {
        val files = (1..fileCount).map { i ->
            val relative = "com/example/File$i.kt"
            val absolute = tempDir.resolve("f$i").resolve(relative)
            absolute.parent.createDirectories()
            absolute.writeText("package com.example\n$line\n")
            SourceFileRef(path = "f$i/$relative", packageName = "com.example")
        }
        val index = LibraryIndex(
            coordinate = coordinate,
            symbolsByFqName = emptyMap(),
            files = files,
            fetchedAt = Instant.fromEpochSeconds(0),
        )
        val fetcher = CountingFetcher(tempDir.toString())
        return LibraryService(
            fetcher = fetcher,
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(index),
        ) to fetcher
    }

    @Test
    fun resolvesTheSourceRootOncePerSearchNotOncePerFile() = runTest {
        val (service, fetcher) = serviceWith(fileCount = 12, line = "val needle = 1")

        val results = service.searchSource(coordinate, query = "needle", regex = false, maxResults = 50)

        assertEquals(12, results.hits.size, "every file should have matched")
        // The whole point: 12 files, one root resolution. Reverting to a per-file
        // `fetcher.fetch(...)` makes this 12 and fails.
        assertEquals(1, fetcher.fetchCount, "searchSource must resolve the source root once")
    }

    @Test
    fun aBackReferenceBlowUpIsAbortedInsteadOfHangingTheServer() = runTest {
        // Back-references are the case JDK 21's matcher is still genuinely exponential on: this
        // exhausts the budget against only 20 characters. The textbook `(a+)+b` deliberately is
        // *not* used here — measured on JDK 21 it is merely quadratic and finishes in microseconds,
        // so it would assert nothing.
        val (service, _) = serviceWith(fileCount = 1, line = "a".repeat(20))

        assertFailsWith<SearchPatternTooExpensiveException> {
            service.searchSource(coordinate, query = """(a+)+\1b""", regex = true, maxResults = 10)
        }
    }

    @Test
    fun aPolynomialPatternOnARealisticallyLongLineIsAlsoAborted() = runTest {
        // `(x+x+)+y` is roughly quartic on JDK 21, so it needs length to bite — but ~200 characters
        // is an unremarkable line in real source, which is exactly why the budget is per line and
        // not a per-search wall clock.
        val (service, _) = serviceWith(fileCount = 1, line = "x".repeat(220))

        assertFailsWith<SearchPatternTooExpensiveException> {
            service.searchSource(coordinate, query = "(x+x+)+y", regex = true, maxResults = 10)
        }
    }

    @Test
    fun aLinearRegexStaysWellInsideTheBudget() = runTest {
        val (service, _) = serviceWith(fileCount = 3, line = "val answer = 42")

        val results = service.searchSource(coordinate, query = """val \w+ = \d+""", regex = true, maxResults = 10)

        assertEquals(3, results.hits.size)
    }

    @Test
    fun anInvalidRegexIsAnArgumentErrorNotAPatternSyntaxException() = runTest {
        val (service, _) = serviceWith(fileCount = 1, line = "whatever")

        val error = assertFailsWith<IllegalArgumentException> {
            service.searchSource(coordinate, query = "(unclosed", regex = true, maxResults = 10)
        }
        assertTrue(error.message.orEmpty().contains("Invalid regular expression"), error.message.orEmpty())
    }

    @Test
    fun anOverlongPatternIsRejectedBeforeCompilation() = runTest {
        val (service, _) = serviceWith(fileCount = 1, line = "whatever")

        assertFailsWith<IllegalArgumentException> {
            service.searchSource(coordinate, query = "a".repeat(1_001), regex = true, maxResults = 10)
        }
    }
}
