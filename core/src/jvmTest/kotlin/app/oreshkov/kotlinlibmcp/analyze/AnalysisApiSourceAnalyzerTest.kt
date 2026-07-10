@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.cache.OnDiskLibraryCache
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class AnalysisApiSourceAnalyzerTest {

    private companion object {
        val coordinate = LibraryCoordinate("com.example", "tiny-lib", "1.0.0")

        /** Standalone sessions are expensive — analyze the fixture once for all tests. */
        val index: LibraryIndex by lazy {
            val fixtureRoot = Path.of(
                checkNotNull(AnalysisApiSourceAnalyzerTest::class.java.classLoader.getResource("fixtures/tiny-lib")) {
                    "Missing test fixture: fixtures/tiny-lib"
                }.toURI()
            )
            AnalysisApiSourceAnalyzer().analyze(
                coordinate = coordinate,
                sourceRoots = listOf(
                    fixtureRoot.resolve("common").toString(),
                    fixtureRoot.resolve("jvm").toString(),
                ),
                classpathRoots = emptyList(),
            )
        }
    }

    @Test
    fun resolvesClassSymbolWithSupertypes() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.DefaultGreeter"])

        assertEquals(SymbolKind.CLASS, symbol.kind)
        assertEquals(Visibility.PUBLIC, symbol.visibility)
        assertFalse(symbol.bestEffort, "expected fully resolved symbol, got: ${symbol.signature}")
        assertContains(symbol.signature, "class DefaultGreeter")
        assertContains(symbol.supertypes, "com.example.tiny.Greeter")
        assertContains(symbol.modifiers, "open")
    }

    @Test
    fun resolvesFunctionSignatureAndTypeParameters() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetWith"])

        assertEquals(SymbolKind.FUNCTION, symbol.kind)
        assertFalse(symbol.bestEffort)
        assertContains(symbol.signature, "greetWith")
        assertContains(symbol.signature, "kotlin.String")
        assertEquals(listOf("T : Greeter"), symbol.typeParameters)
    }

    @Test
    fun extractsKDocSummaryAndTags() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.Greeter.greet"])

        val kdoc = assertNotNull(symbol.kdoc)
        assertEquals("Builds a greeting line for one person.", kdoc.summary)
        assertContains(kdoc.tags.map { it.name to it.value }, "param" to "name who to greet")
        assertContains(kdoc.tags.map { it.name to it.value }, "return" to "the finished greeting")
    }

    @Test
    fun splitsKDocSummaryFromDescription() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.Greeter"])

        val kdoc = assertNotNull(symbol.kdoc)
        assertEquals("Greets people politely.", kdoc.summary)
        assertContains(assertNotNull(kdoc.description), "second paragraph")
    }

    @Test
    fun keepsInternalDeclarationsWithTheirVisibility() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.HiddenHelper"])

        assertEquals(Visibility.INTERNAL, symbol.visibility)
    }

    @Test
    fun degradesUnresolvedTypesToBestEffortPsiSignature() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.render"])

        assertTrue(symbol.bestEffort, "unresolvable Widget type should degrade the symbol")
        assertContains(symbol.signature, "fun render(widget: Widget): Widget")
    }

    @Test
    fun tagsFilesAndPackagesWithTargets() {
        assertEquals(listOf(KmpTarget.COMMON, KmpTarget.JVM), index.targets)
        val pkg = assertNotNull(index.packages.find { it.name == "com.example.tiny" })
        assertTrue(pkg.declarationCount >= 6, "expected all fixture declarations, got $pkg")
        assertEquals(listOf(KmpTarget.COMMON, KmpTarget.JVM), pkg.targets)
        assertTrue(index.files.any { it.path == "common/com/example/tiny/Greeter.kt" }, "files: ${index.files}")
        assertTrue(index.files.any { it.path == "jvm/com/example/tiny/Unresolvable.kt" })
    }

    @Test
    fun recordsSourceLocations() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.DefaultGreeter"])

        val location = assertNotNull(symbol.sourceRef)
        assertEquals("common/com/example/tiny/Greeter.kt", location.file.path)
        assertEquals(19, location.line)
    }

    @Test
    fun indexSurvivesJsonRoundTrip() {
        val json = Json
        assertEquals(index, json.decodeFromString<LibraryIndex>(json.encodeToString(LibraryIndex.serializer(), index)))
    }

    @Test
    fun indexRoundTripsThroughTheOnDiskCache() = runTest {
        val root = createTempDirectory("klm-analyzer-cache-test")
        try {
            val cache = OnDiskLibraryCache(root)
            cache.putIndex(index)
            assertEquals(index, cache.get(coordinate))
        } finally {
            root.deleteRecursively()
        }
    }
}
