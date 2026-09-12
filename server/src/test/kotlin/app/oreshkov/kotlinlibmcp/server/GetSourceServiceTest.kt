package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.model.SourceLocation
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * `get_source` returns a bounded page.
 *
 * The rule this enforces: a tool whose result size is a function of the *library* rather than the
 * *arguments* needs a cap. The whole-file branch used to return the file entire, and the sources
 * this server fetches include single generated files of 1.7 MB — several times the 272 KB overflow
 * that unpaged `list_declarations` produced before it was paged. Results are also emitted twice
 * (text *and* `structuredContent`), so the wire cost is double the page.
 */
class GetSourceServiceTest {

    private val coordinate = LibraryCoordinate("com.example", "demo", "1.0.0")
    private val tempDir: Path = Files.createTempDirectory("get-source-test")
    private val relativePath = "com/example/Big.kt"

    @AfterTest
    fun cleanUp() {
        tempDir.toFile().deleteRecursively()
    }

    private class FixedFetcher(private val extractedDir: String) : MavenSourceFetcher {
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

    /** Writes one source file of [text] and builds a service that serves it, plus [symbols]. */
    private fun serviceWith(text: String, symbols: Map<String, ApiSymbol> = emptyMap()): LibraryService {
        val absolute = tempDir.resolve(relativePath)
        absolute.parent.createDirectories()
        absolute.writeText(text)
        val index = LibraryIndex(
            coordinate = coordinate,
            symbolsByFqName = symbols,
            files = listOf(SourceFileRef(path = relativePath, packageName = "com.example")),
            fetchedAt = Instant.fromEpochSeconds(0),
        )
        return LibraryService(
            fetcher = FixedFetcher(tempDir.toString()),
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(index),
        )
    }

    private fun numberedLines(count: Int): String = (1..count).joinToString("\n") { "line $it" }

    // --- the cap ---

    @Test
    fun aHugeFileComesBackAsABoundedPageNotWhole() = runTest {
        // 20_000 lines stands in for the real 1.7 MB generated sources in kotlin-compiler-embeddable.
        val service = serviceWith(numberedLines(20_000))

        val result = service.getSource(coordinate, path = relativePath, fqName = null)

        assertEquals(500, result.content.lines().size, "the default page is 500 lines")
        assertEquals(1, result.startLine)
        assertEquals(20_000, result.totalLines, "the caller is told how much there is")
        assertTrue(result.truncated, "and that it did not all fit")
        assertTrue(result.content.startsWith("line 1\n"), "the page starts at the top")
        assertFalse("line 501" in result.content, "and stops at the cap")
    }

    @Test
    fun startLineAdvancesThroughTheFile() = runTest {
        val service = serviceWith(numberedLines(1_200))

        val second = service.getSource(coordinate, relativePath, fqName = null, maxLines = 500, startLine = 501)

        assertEquals(501, second.startLine)
        assertTrue(second.content.startsWith("line 501\n"))
        assertTrue(second.truncated, "1200 lines: a third page remains")

        val third = service.getSource(coordinate, relativePath, fqName = null, maxLines = 500, startLine = 1_001)
        assertEquals(1_001, third.startLine)
        assertEquals(200, third.content.lines().size)
        assertFalse(third.truncated, "the last page is not truncated")
        assertTrue(third.content.endsWith("line 1200"))
    }

    @Test
    fun aSmallFileIsReturnedWholeAndNotMarkedTruncated() = runTest {
        val service = serviceWith(numberedLines(10))

        val result = service.getSource(coordinate, path = relativePath, fqName = null)

        assertEquals(10, result.totalLines)
        assertFalse(result.truncated)
        assertEquals(numberedLines(10), result.content)
    }

    @Test
    fun aPageWithinTheLineBudgetIsStillCappedByCharacters() = runTest {
        // A line cap alone is not a size cap. Generated and minified sources reach thousands of
        // characters per line, so 500 such lines blow the budget while satisfying the line cap.
        // Two lines of 400_000 characters is the same problem in its starkest form.
        val service = serviceWith((1..2).joinToString("\n") { "x".repeat(400_000) })

        val result = service.getSource(coordinate, path = relativePath, fqName = null)

        assertEquals(100_000, result.content.length, "clipped to the character ceiling")
        assertTrue(result.truncated, "clipping by characters also reports truncation")
    }

    @Test
    fun maxLinesIsClampedSoACallerCannotAskForEverything() = runTest {
        val service = serviceWith(numberedLines(20_000))

        val result = service.getSource(coordinate, relativePath, fqName = null, maxLines = Int.MAX_VALUE)

        assertEquals(5_000, result.content.lines().size, "clamped to the 5000-line ceiling")
        assertTrue(result.truncated)
    }

    // --- the declaration branch ---

    @Test
    fun aDeclarationSliceReportsItsOwnFileLineAndIsAlsoCapped() = runTest {
        val text = numberedLines(2_000)
        // A declaration starting at file line 101, running to the end of the file.
        val offset = text.lines().take(100).sumOf { it.length + 1 }
        val symbol = ApiSymbol(
            fqName = "com.example.Big",
            kind = SymbolKind.CLASS,
            visibility = Visibility.PUBLIC,
            signature = "class Big",
            sourceRef = SourceLocation(
                file = SourceFileRef(path = relativePath, packageName = "com.example"),
                offset = offset,
                line = 101,
            ),
        )
        val service = serviceWith(text, symbols = mapOf(symbol.fqName to symbol))

        val result = service.getSource(coordinate, path = null, fqName = "com.example.Big")

        // startLine is absolute in the file, so it lines up with what search_source reports.
        assertEquals(101, result.startLine, "a declaration page starts at the declaration")
        assertTrue(result.content.startsWith("line 101\n"))
        assertEquals(500, result.content.lines().size)
        assertEquals(1_900, result.totalLines, "totalLines covers the declaration, not the file")
        assertTrue(result.truncated)
    }
}
