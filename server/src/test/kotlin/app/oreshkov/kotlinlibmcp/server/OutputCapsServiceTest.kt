package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The output caps on the three tools whose result size is a function of the *library* rather than
 * the *arguments*: `list_packages`, `list_versions` and `get_dependencies`.
 *
 * The rule they enforce is the one the unpaged `list_declarations` taught at 272 KB, and
 * `get_source` repeated at 1.7 MB. Measured worst case here: `kotlin-compiler-embeddable` has 610
 * packages, and every result is emitted twice — as text *and* as `structuredContent` — so the wire
 * cost is double whatever the page measures.
 */
class OutputCapsServiceTest {

    private val coordinate = LibraryCoordinate("com.example", "demo", "1.0.0")

    private class VersionsFetcher(
        private val versions: List<String>,
        private val tree: DependencyNode? = null,
    ) : MavenSourceFetcher {
        override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult =
            throw UnsupportedOperationException("not used")

        override suspend fun fetchVersionCatalog(
            group: String,
            artifact: String,
            repos: List<String>,
        ): VersionCatalog = VersionCatalog(versions = versions)

        override suspend fun listVersions(group: String, artifact: String, repos: List<String>): List<String> =
            versions

        override suspend fun resolveDependencies(
            coordinate: LibraryCoordinate,
            repos: List<String>,
            maxDepth: Int,
        ): DependencyNode = tree ?: throw UnsupportedOperationException("not used")
    }

    private class SingleIndexCache(private val index: LibraryIndex) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex = index
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = listOf(index.coordinate)
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    private fun serviceWithPackages(count: Int): LibraryService {
        val index = LibraryIndex(
            coordinate = coordinate,
            packages = (1..count).map { PackageInfo(name = "com.example.p$it", declarationCount = it) },
            fetchedAt = Instant.fromEpochSeconds(0),
        )
        return LibraryService(
            fetcher = VersionsFetcher(emptyList()),
            analyzer = UnusedAnalyzer,
            cache = SingleIndexCache(index),
        )
    }

    private fun serviceWithVersions(versions: List<String>) = LibraryService(
        fetcher = VersionsFetcher(versions),
        analyzer = UnusedAnalyzer,
        cache = UnusedCache,
    )

    private fun serviceWithTree(tree: DependencyNode) = LibraryService(
        fetcher = VersionsFetcher(emptyList(), tree),
        analyzer = UnusedAnalyzer,
        cache = UnusedCache,
    )

    /** A tree [breadth] wide at every level, [depth] deep. */
    private fun tree(breadth: Int, depth: Int, prefix: String = "n"): DependencyNode = DependencyNode(
        coordinate = LibraryCoordinate("com.example", prefix, "1.0.0"),
        children = if (depth <= 1) {
            emptyList()
        } else {
            (1..breadth).map { tree(breadth, depth - 1, "$prefix-$it") }
        },
    )

    private fun DependencyNode.count(): Int = 1 + children.sumOf { it.count() }

    // --- list_packages ---

    @Test
    fun aLibraryWithHundredsOfPackagesComesBackAsAPage() = runTest {
        // kotlin-compiler-embeddable has 610.
        val service = serviceWithPackages(610)

        val result = service.listPackages(coordinate)

        assertEquals(200, result.packages.size, "the default page")
        assertEquals(610, result.totalCount, "but the caller is told the real total")
        assertTrue(result.truncated)
    }

    @Test
    fun packagesPageThroughToTheEnd() = runTest {
        val service = serviceWithPackages(250)

        val second = service.listPackages(coordinate, maxResults = 200, offset = 200)

        assertEquals(50, second.packages.size)
        assertEquals("com.example.p201", second.packages.first().name, "offset is honoured")
        assertFalse(second.truncated, "the last page is not truncated")
    }

    @Test
    fun anOrdinaryLibraryIsStillReturnedWhole() = runTest {
        // ktor-client-core has 71, kotlin-stdlib 96: the default must not page the common case.
        val service = serviceWithPackages(96)

        val result = service.listPackages(coordinate)

        assertEquals(96, result.packages.size)
        assertFalse(result.truncated)
    }

    @Test
    fun packageMaxResultsIsClamped() = runTest {
        val service = serviceWithPackages(2_000)

        val result = service.listPackages(coordinate, maxResults = Int.MAX_VALUE)

        assertEquals(1_000, result.packages.size, "clamped to the ceiling")
        assertTrue(result.truncated)
    }

    // --- list_versions ---

    @Test
    fun versionsArePagedNewestFirst() = runTest {
        val versions = (1..300).map { "1.0.$it" }
        val service = serviceWithVersions(versions)

        val first = service.listVersions("com.example", "demo")

        assertEquals(100, first.versions.size, "the default page")
        assertEquals(300, first.totalCount)
        assertTrue(first.truncated)
        // The fetcher's order is preserved (it already returns newest-first), so the first page is
        // the one nearly every caller wants.
        assertEquals(versions.take(100), first.versions)

        val last = service.listVersions("com.example", "demo", maxResults = 100, offset = 200)
        assertEquals(100, last.versions.size)
        assertFalse(last.truncated)
    }

    @Test
    fun aShortVersionListIsNotMarkedTruncated() = runTest {
        val service = serviceWithVersions(listOf("1.0.0", "1.1.0"))

        val result = service.listVersions("com.example", "demo")

        assertEquals(2, result.versions.size)
        assertEquals(2, result.totalCount)
        assertFalse(result.truncated)
    }

    // --- get_dependencies ---

    @Test
    fun aWideTreeIsPrunedToTheNodeBudget() = runTest {
        // depth bounds *resolution*; breadth is unbounded, so a depth-5 tree of a well-connected
        // artifact carries thousands of nodes even though `depth` was respected.
        val full = tree(breadth = 5, depth = 5)
        assertTrue(full.count() > 500, "fixture must actually be large: ${full.count()}")
        val service = serviceWithTree(full)

        val result = service.getDependencies(coordinate, depth = 5)

        assertEquals(full.count(), result.totalNodes, "the real size is reported")
        assertTrue(result.truncated)
        assertEquals(200, result.root.count(), "and the returned tree is exactly the budget")
    }

    @Test
    fun pruningKeepsDirectDependenciesAndDropsTheDeepest() = runTest {
        // Breadth-first, because answering "what does this drag in" with the deepest transitives
        // and none of the direct dependencies would be worse than not answering.
        val full = tree(breadth = 4, depth = 4)
        val service = serviceWithTree(full)

        val result = service.getDependencies(coordinate, depth = 4, maxNodes = 5)

        assertEquals(5, result.root.count())
        // Root + its 4 direct children, and none of their children.
        assertEquals(4, result.root.children.size, "every direct dependency survived")
        assertTrue(result.root.children.all { it.children.isEmpty() }, "the next level was dropped")
    }

    @Test
    fun aTreeInsideTheBudgetIsReturnedUntouched() = runTest {
        val full = tree(breadth = 2, depth = 3)
        val service = serviceWithTree(full)

        val result = service.getDependencies(coordinate, depth = 3)

        assertEquals(full.count(), result.totalNodes)
        assertEquals(full, result.root, "no pruning, no rebuild")
        assertFalse(result.truncated)
    }

    @Test
    fun repeatedCoordinatesInADiamondKeepTheirOwnSubtrees() = runTest {
        // The pruning map is keyed on identity, not equality. Two distinct nodes for the same
        // coordinate are `==` as data classes, so with a value-keyed map both occurrences share one
        // entry: the second one's `= mutableListOf()` resets what the first recorded, and both then
        // accumulate into it. The shared node comes back carrying its child *twice*.
        //
        // The duplication only shows when the shared node HAS children — with a shared leaf both
        // maps agree, which is why this fixture gives it one.
        // It also only shows on the pruning path: a tree inside the budget is returned as resolved,
        // without a rebuild. Hence the third branch, which pushes the total past the budget.
        fun shared() = DependencyNode(
            coordinate = LibraryCoordinate("com.example", "shared", "1.0.0"),
            children = listOf(DependencyNode(LibraryCoordinate("com.example", "leaf", "1.0.0"))),
        )
        val root = DependencyNode(
            coordinate = coordinate,
            children = listOf(
                DependencyNode(LibraryCoordinate("com.example", "a", "1.0.0"), children = listOf(shared())),
                DependencyNode(LibraryCoordinate("com.example", "b", "1.0.0"), children = listOf(shared())),
                // A 4-node chain, so the 11th node is the one a budget of 10 drops.
                tree(breadth = 1, depth = 4, prefix = "c"),
            ),
        )
        val service = serviceWithTree(root)

        val result = service.getDependencies(coordinate, depth = 5, maxNodes = 10)

        assertEquals(11, result.totalNodes)
        assertTrue(result.truncated)
        assertEquals(10, result.root.count(), "exactly the budget, counted once")
        // The assertion that discriminates: one 'leaf' under each 'shared'. Sharing one entry
        // between the two equal 'shared' nodes gives them both [leaf, leaf].
        result.root.children.take(2).forEach { branch ->
            assertEquals(1, branch.children.single().children.size, "each 'shared' keeps its own child")
        }
    }
}
