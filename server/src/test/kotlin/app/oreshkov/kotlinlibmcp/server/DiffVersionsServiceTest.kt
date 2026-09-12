package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.dto.FileChange
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * `diff_versions` at the service level: what it counts, what it pages, and — the one that is easy
 * to get wrong — that a KMP library's duplicated target trees are collapsed before anything is
 * compared.
 */
class DiffVersionsServiceTest {

    private val group = "io.ktor"
    private val artifact = "ktor-client-core"
    private val tempDir: Path = Files.createTempDirectory("diff-versions-test")

    @AfterTest
    fun cleanUp() {
        tempDir.toFile().deleteRecursively()
    }

    /** Serves each version its own extracted root, as the real cache lays them out per coordinate. */
    private class PerVersionFetcher(private val roots: Map<String, String>) : MavenSourceFetcher {
        override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult =
            FetchResult(
                coordinate = coordinate,
                resolvedTargets = emptyList(),
                downloadedJars = emptyList(),
                extractedDir = roots.getValue(coordinate.version),
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

    private class MultiIndexCache(private val indexes: Map<LibraryCoordinate, LibraryIndex>) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = indexes[coordinate]
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = indexes.keys.toList()
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    /**
     * Writes one version's tree. [files] maps a source path *below* the target directory to its
     * content, and each is written into **both** `common/` and `jvm/` — exactly what a real KMP
     * source jar pair produces, and what the collapse has to undo.
     */
    private fun version(version: String, files: Map<String, String>): Pair<LibraryCoordinate, LibraryIndex> {
        val root = tempDir.resolve(version)
        val refs = files.flatMap { (path, content) ->
            listOf("common", "jvm").map { target ->
                val absolute = root.resolve(target).resolve(path)
                absolute.parent.createDirectories()
                absolute.writeText(content)
                SourceFileRef(path = "$target/$path", packageName = "io.ktor.client")
            }
        }
        val coordinate = LibraryCoordinate(group, artifact, version)
        return coordinate to LibraryIndex(coordinate = coordinate, files = refs, fetchedAt = Instant.fromEpochSeconds(0))
    }

    private fun serviceFor(vararg versions: Pair<LibraryCoordinate, LibraryIndex>): LibraryService =
        LibraryService(
            fetcher = PerVersionFetcher(versions.associate { it.first.version to tempDir.resolve(it.first.version).toString() }),
            analyzer = UnusedAnalyzer,
            cache = MultiIndexCache(versions.toMap()),
        )

    private fun numbered(count: Int, marker: String = "") = (1..count).joinToString("\n") { "line $it$marker" }

    // --- the target-duplication collapse ---

    @Test
    fun aChangedFileIsReportedOnceNotOncePerTarget() = runTest {
        // A KMP index lists commonMain/HttpClient.kt under both common/ and jvm/ — ktor-client-core
        // records 1214 common paths beside 1233 jvm ones. Diffing them verbatim double-counts every
        // change, and the paths in the result read as target trees rather than source files.
        val service = serviceFor(
            version("3.4.0", mapOf("commonMain/io/ktor/client/HttpClient.kt" to numbered(20))),
            version("3.5.1", mapOf("commonMain/io/ktor/client/HttpClient.kt" to numbered(20, marker = " edited"))),
        )

        val diff = service.diffVersions(group, artifact, "3.4.0", "3.5.1")

        assertEquals(1, diff.filesModified, "the same source under two targets is one file")
        assertEquals(0, diff.filesAdded)
        assertEquals(0, diff.filesRemoved)
        assertEquals(
            "commonMain/io/ktor/client/HttpClient.kt",
            diff.files.single().path,
            "the target directory is stripped from the reported path",
        )
    }

    @Test
    fun aTargetSpecificSourceStaysItsOwnFile() = runTest {
        // Stripping the target segment must not merge genuinely different sources: jvmMain/X and
        // commonMain/X are different files that happen to share a tail.
        val from = version("1.0.0", mapOf("commonMain/A.kt" to "common v1"))
        val to = version("1.1.0", mapOf("commonMain/A.kt" to "common v1", "jvmMain/A.kt" to "jvm only"))
        val service = serviceFor(from, to)

        val diff = service.diffVersions(group, artifact, "1.0.0", "1.1.0")

        assertEquals(1, diff.filesAdded)
        assertEquals(0, diff.filesModified)
        assertEquals("jvmMain/A.kt", diff.files.single().path)
    }

    // --- what it reports ---

    @Test
    fun addedRemovedAndModifiedAreCountedAndHunksOnlyForModified() = runTest {
        val service = serviceFor(
            version(
                "1.0.0",
                mapOf("commonMain/Kept.kt" to numbered(10), "commonMain/Gone.kt" to "removed\nfile\n"),
            ),
            version(
                "2.0.0",
                mapOf("commonMain/Kept.kt" to numbered(10, " v2"), "commonMain/New.kt" to "brand\nnew\nfile\n"),
            ),
        )

        val diff = service.diffVersions(group, artifact, "1.0.0", "2.0.0")

        assertEquals(1, diff.filesAdded)
        assertEquals(1, diff.filesRemoved)
        assertEquals(1, diff.filesModified)

        val byPath = diff.files.associateBy { it.path }
        val added = byPath.getValue("commonMain/New.kt")
        assertEquals(FileChange.ADDED, added.change)
        // A whole new file as hunks is the unbounded thing this tool exists to avoid.
        assertEquals(emptyList(), added.hunks)
        assertTrue(added.addedLines > 0, "but its size is still reported")

        assertEquals(FileChange.REMOVED, byPath.getValue("commonMain/Gone.kt").change)

        val modified = byPath.getValue("commonMain/Kept.kt")
        assertEquals(FileChange.MODIFIED, modified.change)
        assertTrue(modified.hunks.isNotEmpty(), "only a modified file carries diff text")
        assertTrue(modified.hunks.first().startsWith("@@ "), "each hunk carries its own header")
        assertFalse(modified.diffOmitted)
    }

    @Test
    fun anIdenticalPairReportsNoChangesAtAll() = runTest {
        val files = mapOf("commonMain/A.kt" to numbered(50))
        val service = serviceFor(version("1.0.0", files), version("1.0.1", files))

        val diff = service.diffVersions(group, artifact, "1.0.0", "1.0.1")

        assertEquals(0, diff.filesAdded + diff.filesRemoved + diff.filesModified)
        assertEquals(emptyList(), diff.files)
        assertFalse(diff.truncated)
    }

    // --- paging and filtering ---

    @Test
    fun filesArePagedAndTheSummaryStillCountsThemAll() = runTest {
        val before = (1..10).associate { "commonMain/F$it.kt" to "v1" }
        val after = (1..10).associate { "commonMain/F$it.kt" to "v2" }
        val service = serviceFor(version("1.0.0", before), version("2.0.0", after))

        val page = service.diffVersions(group, artifact, "1.0.0", "2.0.0", maxResults = 3)

        assertEquals(10, page.filesModified, "the summary describes the whole comparison")
        assertEquals(3, page.files.size, "not the page")
        assertTrue(page.truncated)

        val last = service.diffVersions(group, artifact, "1.0.0", "2.0.0", maxResults = 3, offset = 9)
        assertEquals(1, last.files.size)
        assertFalse(last.truncated, "the final page is not truncated")
    }

    @Test
    fun thePathFilterNarrowsTheComparisonItselfNotJustThePage() = runTest {
        val before = mapOf("commonMain/engine/A.kt" to "v1", "commonMain/plugins/B.kt" to "v1")
        val after = mapOf("commonMain/engine/A.kt" to "v2", "commonMain/plugins/B.kt" to "v2")
        val service = serviceFor(version("1.0.0", before), version("2.0.0", after))

        val diff = service.diffVersions(group, artifact, "1.0.0", "2.0.0", pathFilter = "engine")

        // If the filter only trimmed the page, this would still say 2.
        assertEquals(1, diff.filesModified)
        assertEquals("commonMain/engine/A.kt", diff.files.single().path)
    }

    @Test
    fun anUnfetchedVersionSaysSoRatherThanReportingEverythingAsAdded() = runTest {
        val service = serviceFor(version("1.0.0", mapOf("commonMain/A.kt" to "v1")))

        assertFailsWith<LibraryNotFetchedException> {
            service.diffVersions(group, artifact, "1.0.0", "9.9.9")
        }
    }
}
