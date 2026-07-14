package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Covers `LibraryService.listDeclarations` paging: a large library must be returned in bounded
 * pages with a `truncated`/`totalCount` signal, so it can't flood the model's context.
 */
class ListDeclarationsServiceTest {

    private val coordinate = LibraryCoordinate("com.example", "demo", "1.0.0")

    /** Cache that always serves [index] for `get`; every other op is an inert no-op. */
    private class SingleIndexCache(private val index: LibraryIndex) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex = index
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = listOf(index.coordinate)
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    private fun serviceWith(symbolCount: Int): LibraryService {
        val symbols = (1..symbolCount).associate { i ->
            val fq = "com.example.Sym$i"
            fq to ApiSymbol(fq, SymbolKind.CLASS, Visibility.PUBLIC, signature = "class Sym$i")
        }
        val index = LibraryIndex(coordinate, symbolsByFqName = symbols, fetchedAt = Instant.fromEpochSeconds(0))
        return LibraryService(
            fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(index),
        )
    }

    @Test
    fun capsPageAndReportsTruncationAndTotal() = runTest {
        val result = serviceWith(10)
            .listDeclarations(coordinate, packageName = null, visibility = null, maxResults = 3, offset = 0)

        assertEquals(3, result.declarations.size)
        assertEquals(10, result.totalCount)
        assertEquals(true, result.truncated)
    }

    @Test
    fun offsetPagesThroughAndFinalPageIsNotTruncated() = runTest {
        val page = serviceWith(10)
            .listDeclarations(coordinate, packageName = null, visibility = null, maxResults = 4, offset = 8)

        assertEquals(2, page.declarations.size) // only Sym9, Sym10 remain
        assertEquals(10, page.totalCount)
        assertEquals(false, page.truncated)
    }
}
