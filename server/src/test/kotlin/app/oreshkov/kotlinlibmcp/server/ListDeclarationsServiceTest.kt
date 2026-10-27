package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.Deprecation
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.model.SourceLocation
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    /** Every [deprecatedEvery]-th symbol is deprecated; `0` deprecates none. */
    private fun serviceWith(symbolCount: Int, deprecatedEvery: Int = 0): LibraryService {
        val symbols = (1..symbolCount).associate { i ->
            val fq = "com.example.Sym$i"
            val deprecation = Deprecation(Deprecation.Level.WARNING).takeIf { deprecatedEvery > 0 && i % deprecatedEvery == 0 }
            fq to ApiSymbol(fq, SymbolKind.CLASS, Visibility.PUBLIC, signature = "class Sym$i", deprecation = deprecation)
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

    @Test
    fun deprecatedFilterSelectsEitherSideAndCountsOnlyWhatMatched() = runTest {
        val service = serviceWith(10, deprecatedEvery = 3)

        val deprecated = service.listDeclarations(coordinate, packageName = null, visibility = null, deprecated = true)
        assertEquals(listOf("com.example.Sym3", "com.example.Sym6", "com.example.Sym9"), deprecated.declarations.map { it.fqName })
        assertEquals(3, deprecated.totalCount)

        val current = service.listDeclarations(coordinate, packageName = null, visibility = null, deprecated = false)
        assertEquals(7, current.totalCount)
        assertTrue(current.declarations.none { it.deprecation != null })

        assertEquals(10, service.listDeclarations(coordinate, packageName = null, visibility = null).totalCount)
    }

    @Test
    fun aPackageTheLibraryDoesNotHaveIsAnErrorNotAnEmptyPage() = runTest {
        // An empty page would read as "this package declares nothing" — a different answer.
        val e = assertFailsWith<IllegalArgumentException> {
            serviceWith(3).listDeclarations(coordinate, packageName = "com.exmaple", visibility = null)
        }
        assertEquals("No package 'com.exmaple' in $coordinate (see list_packages)", e.message)
    }

    /** Keys [symbols] the way the analyzer does: FQ name, then `#2`, `#3`… in the order given. */
    private fun serviceOf(symbols: List<ApiSymbol>): LibraryService {
        val keyed = LinkedHashMap<String, ApiSymbol>()
        for (symbol in symbols) {
            var key = symbol.fqName
            var n = 1
            while (key in keyed) key = "${symbol.fqName}#${++n}"
            keyed[key] = symbol
        }
        return LibraryService(
            fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(LibraryIndex(coordinate, symbolsByFqName = keyed, fetchedAt = Instant.fromEpochSeconds(0))),
        )
    }

    private fun copy(fqName: String, signature: String, path: String, bestEffort: Boolean = false) = ApiSymbol(
        fqName = fqName,
        kind = SymbolKind.FUNCTION,
        visibility = Visibility.PUBLIC,
        signature = signature,
        sourceRef = SourceLocation(SourceFileRef(path, packageName = "com.example"), line = 1, offset = 0),
        bestEffort = bestEffort,
    )

    @Test
    fun listsADeclarationOnceHoweverManyTargetTreesHoldIt() = runTest {
        // A KMP index holds common sources once per target tree, each copy under its own `#n` key.
        val result = serviceOf(
            listOf(
                copy("com.example.f", "public fun f(): kotlin.Unit", "common/com/example/Api.kt"),
                copy("com.example.f", "public fun f(): kotlin.Unit", "jvm/com/example/Api.kt"),
                copy("com.example.f", "public fun f(x: kotlin.Int): kotlin.Unit", "common/com/example/Api.kt"),
                copy("com.example.g", "public fun g(): kotlin.Unit", "common/com/example/Api.kt"),
                copy("com.example.g", "public fun g(): kotlin.Unit", "jvm/com/example/Api.kt"),
            )
        ).listDeclarations(coordinate, packageName = null, visibility = null, maxResults = 2)

        // The real overload of `f` stays; the per-target copies go, and before paging, not after.
        assertEquals(3, result.totalCount)
        assertEquals(
            listOf("public fun f(): kotlin.Unit", "public fun f(x: kotlin.Int): kotlin.Unit"),
            result.declarations.map { it.signature },
        )
        assertEquals(true, result.truncated)
    }

    @Test
    fun keepsTheResolvedCopyOverABestEffortOne() = runTest {
        val result = serviceOf(
            listOf(
                copy("com.example.f", "public fun f(): kotlin.Unit", "common/com/example/Api.kt", bestEffort = true),
                copy("com.example.f", "public fun f(): kotlin.Unit", "jvm/com/example/Api.kt"),
            )
        ).listDeclarations(coordinate, packageName = null, visibility = null)

        assertEquals(listOf("jvm/com/example/Api.kt" to false), result.declarations.map { it.sourceRef?.file?.path to it.bestEffort })
    }
}
