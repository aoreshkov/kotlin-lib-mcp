@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.analyze

import app.oreshkov.kotlinlibmcp.cache.OnDiskLibraryCache
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.Deprecation
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
import kotlin.test.assertNull
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
        assertEquals(
            "Implementations decide the salutation.\n\nThis second paragraph is description, not summary.",
            kdoc.description,
        )
    }

    @Test
    fun keepsInternalDeclarationsWithTheirVisibility() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.HiddenHelper"])

        assertEquals(Visibility.INTERNAL, symbol.visibility)
    }

    private fun visibilityOf(fqName: String): Visibility = assertNotNull(index.symbolsByFqName[fqName], fqName).visibility

    @Test
    fun aMemberOfAnInternalClassIsInternal() {
        assertEquals(Visibility.INTERNAL, visibilityOf("com.example.tiny.HiddenHelper.shhh"))
    }

    @Test
    fun aMemberOfAPrivateNestedClassIsPrivate() {
        assertEquals(Visibility.PRIVATE, visibilityOf("com.example.tiny.Envelope.Seal.press"))
    }

    @Test
    fun aPublicMemberOfAProtectedNestedClassIsProtected() {
        assertEquals(Visibility.PROTECTED, visibilityOf("com.example.tiny.Card.Section.render"))
    }

    /** From another module a subclass still reaches `protected`; nothing reaches `internal`. */
    @Test
    fun aProtectedMemberOfAnInternalClassIsInternal() {
        assertEquals(Visibility.INTERNAL, visibilityOf("com.example.tiny.Draft.scribble"))
    }

    /** The case only resolution gets right: the `override` carries no modifier to read. */
    @Test
    fun anOverrideWithoutAModifierInheritsProtected() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.BirthdayCard.layout"])

        assertFalse(symbol.bestEffort)
        assertEquals(Visibility.PROTECTED, symbol.visibility)
    }

    @Test
    fun indexesPrimaryConstructorPropertiesAsProperties() {
        val label = assertNotNull(index.symbolsByFqName["com.example.tiny.Placement.label"])
        val x = assertNotNull(index.symbolsByFqName["com.example.tiny.Placement.x"])

        assertEquals(SymbolKind.PROPERTY, label.kind)
        assertFalse(label.bestEffort, "expected fully resolved symbol, got: ${label.signature}")
        assertEquals("public val label: kotlin.String", label.signature)
        assertEquals(Visibility.PUBLIC, label.visibility)
        assertEquals("public var x: kotlin.Int", x.signature)
        assertEquals(Visibility.PRIVATE, visibilityOf("com.example.tiny.Placement.secret"))
    }

    @Test
    fun leavesPlainConstructorParametersOut() {
        assertNull(index.symbolsByFqName["com.example.tiny.Placement.width"])
    }

    @Test
    fun readsAConstructorPropertysKDocFromItsClassPropertyTag() {
        val kdoc = assertNotNull(index.symbolsByFqName["com.example.tiny.Placement.label"]?.kdoc)

        assertEquals("what is printed there.", kdoc.summary)
        assertEquals("Shown verbatim.", kdoc.description)
    }

    @Test
    fun keysOverloadsInSourceOrder() {
        val first = assertNotNull(index.symbolsByFqName["com.example.tiny.place"])
        val second = assertNotNull(index.symbolsByFqName["com.example.tiny.place#2"])

        assertEquals("public fun place(label: kotlin.String): com.example.tiny.Placement", first.signature)
        assertEquals("public fun place(label: kotlin.String, x: kotlin.Int): com.example.tiny.Placement", second.signature)
        assertNull(index.symbolsByFqName["com.example.tiny.place#3"])
    }

    @Test
    fun summarisesADefaultTooLongToInline() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetFormally"])

        assertFalse(symbol.bestEffort)
        assertContains(symbol.signature, "salutation: kotlin.String = ...")
    }

    @Test
    fun marksAnInheritedDefaultAsOptional() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.QuietPunctuator.punctuate"])

        assertFalse(symbol.bestEffort)
        assertContains(symbol.signature, "mark: kotlin.String = ...")
    }

    @Test
    fun keepsANameEndingInByOnTheDegradedPath() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.enterLobby"])

        assertTrue(symbol.bestEffort, "unresolvable Widget type should degrade the symbol")
        assertEquals("public fun enterLobby(widget: Widget): Lobby", symbol.signature)
    }

    @Test
    fun rendersParameterDefaultValuesFromSource() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetEveryone"])

        assertFalse(symbol.bestEffort, "expected fully resolved symbol, got: ${symbol.signature}")
        assertContains(symbol.signature, "greeter: com.example.tiny.Greeter = DefaultGreeter()")
        assertContains(symbol.signature, "separator: kotlin.String = \", \"")
    }

    @Test
    fun rendersVisibilityOnPublicDeclarations() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetEveryone"])

        assertTrue(
            symbol.signature.startsWith("public fun greetEveryone"),
            "visibility should be spelled out, got: ${symbol.signature}",
        )
    }

    @Test
    fun leavesRequiredParametersWithoutADefault() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetEveryone"])

        val names = symbol.signature.substringAfter("names: ").substringBefore(",")
        assertEquals("kotlin.collections.List<kotlin.String>", names, "in: ${symbol.signature}")
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
    fun capturesWarningDeprecationWithReplaceWithAndImports() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.oldGreet"])

        assertFalse(symbol.bestEffort)
        assertEquals(
            Deprecation(
                level = Deprecation.Level.WARNING,
                message = "Use newGreet instead",
                replaceWith = "newGreet(name)",
                replaceWithImports = listOf("com.example.tiny.newGreet", "kotlin.text.trim"),
            ),
            symbol.deprecation,
        )
    }

    @Test
    fun capturesErrorDeprecationFromNamedAndQualifiedArguments() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetById"])

        assertEquals(
            Deprecation(
                level = Deprecation.Level.ERROR,
                message = "Greeting by number was a mistake",
                replaceWith = "newGreet(id.toString())",
            ),
            symbol.deprecation,
        )
    }

    @Test
    fun capturesHiddenDeprecation() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.greetLoudly"])

        assertEquals(
            Deprecation(level = Deprecation.Level.HIDDEN, message = "Kept for binary compatibility"),
            symbol.deprecation,
        )
    }

    @Test
    fun capturesClassLevelDeprecation() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.LegacyGreeter"])

        assertEquals(Deprecation(level = Deprecation.Level.WARNING, message = "Use DefaultGreeter"), symbol.deprecation)
    }

    @Test
    fun leavesUndeprecatedDeclarationsWithoutADeprecation() {
        assertNull(assertNotNull(index.symbolsByFqName["com.example.tiny.newGreet"]).deprecation)
        assertNull(assertNotNull(index.symbolsByFqName["com.example.tiny.DefaultGreeter"]).deprecation)
    }

    /**
     * The discriminating case: the message is a `const val`, which the PSI fallback cannot read
     * (see [PsiDeprecationTest]). Passing here means the resolved path produced the deprecation.
     */
    @Test
    fun readsAConstantDeprecationMessageThroughResolution() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.legacyGreeting"])

        assertEquals(
            Deprecation(level = Deprecation.Level.WARNING, message = "Greetings are functions now"),
            symbol.deprecation,
        )
    }

    @Test
    fun keepsTheDeprecationOfABestEffortSymbol() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.renderLegacy"])

        assertTrue(symbol.bestEffort, "unresolvable Widget type should degrade the symbol")
        assertEquals(
            Deprecation(level = Deprecation.Level.WARNING, message = "Widgets are going away", replaceWith = "render(widget)"),
            symbol.deprecation,
        )
    }

    @Test
    fun omitsAnAbsentDeprecationFromTheJson() {
        val symbol = assertNotNull(index.symbolsByFqName["com.example.tiny.newGreet"])

        assertFalse("deprecation" in Json.encodeToString(ApiSymbol.serializer(), symbol))
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
