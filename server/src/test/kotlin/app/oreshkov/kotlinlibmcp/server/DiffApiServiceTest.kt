package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.dto.ApiChange
import app.oreshkov.kotlinlibmcp.dto.ApiChangeType
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.Deprecation
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.model.SourceLocation
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import app.oreshkov.kotlinlibmcp.model.Visibility
import app.oreshkov.kotlinlibmcp.server.tools.toolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * `diff_api` at the service level, on in-memory index pairs. Most of these pin a way the obvious
 * implementation reports churn that is not API: overload keys renumbered, per-target copies,
 * `expect`/`actual`, members of an internal class, a deprecation annotation inside the signature.
 */
class DiffApiServiceTest {

    private val group = "com.example"
    private val artifact = "demo"

    private class MultiIndexCache(private val indexes: Map<LibraryCoordinate, LibraryIndex>) : LibraryCache {
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = indexes[coordinate]
        override suspend fun putIndex(index: LibraryIndex) = Unit
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
        override suspend fun list(): List<LibraryCoordinate> = indexes.keys.toList()
        override suspend fun clear(coordinate: LibraryCoordinate) = Unit
        override suspend fun size(): Long = 0
    }

    private fun symbol(
        fqName: String,
        signature: String,
        kind: SymbolKind = SymbolKind.FUNCTION,
        visibility: Visibility = Visibility.PUBLIC,
        modifiers: List<String> = emptyList(),
        path: String = "common/commonMain/com/example/Api.kt",
        bestEffort: Boolean = false,
        deprecation: Deprecation? = null,
    ) = ApiSymbol(
        fqName = fqName,
        kind = kind,
        visibility = visibility,
        signature = signature,
        modifiers = modifiers,
        sourceRef = SourceLocation(
            file = SourceFileRef(path, packageName = fqName.packageOf()),
            line = 1,
            offset = 0,
        ),
        bestEffort = bestEffort,
        deprecation = deprecation,
    )

    /** The segments before the first type (`com.example.sub.Api.member` → `com.example.sub`), else all but the last. */
    private fun String.packageOf(): String {
        val segments = split('.')
        val firstType = segments.indexOfFirst { it.first().isUpperCase() }
        return segments.take(if (firstType >= 0) firstType else segments.size - 1).joinToString(".")
    }

    /** Keys [symbols] the way the analyzer does: FQ name, then `#2`, `#3`… in the order given. */
    private fun index(version: String, symbols: List<ApiSymbol>): LibraryIndex {
        val keyed = LinkedHashMap<String, ApiSymbol>()
        for (symbol in symbols) {
            var key = symbol.fqName
            var n = 1
            while (key in keyed) key = "${symbol.fqName}#${++n}"
            keyed[key] = symbol
        }
        return LibraryIndex(
            coordinate = LibraryCoordinate(group, artifact, version),
            packages = symbols.mapNotNull { it.sourceRef?.file?.packageName }.distinct().sorted()
                .map { PackageInfo(name = it, declarationCount = 1) },
            symbolsByFqName = keyed,
            fetchedAt = Instant.fromEpochSeconds(0),
        )
    }

    private fun service(from: List<ApiSymbol>, to: List<ApiSymbol>) = LibraryService(
        fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
        analyzer = UnusedAnalyzer,
        cache = MultiIndexCache(
            listOf(index("1.0", from), index("2.0", to)).associateBy { it.coordinate },
        ),
    )

    private suspend fun diff(
        from: List<ApiSymbol>,
        to: List<ApiSymbol>,
        packageName: String? = null,
        change: ApiChangeType? = null,
        maxResults: Int = LibraryService.DEFAULT_API_DIFF_RESULTS,
        offset: Int = 0,
    ) = service(from, to).diffApi(group, artifact, "1.0", "2.0", packageName, change, maxResults, offset)

    private fun List<ApiChange>.summary(): List<String> = map { "${it.change.name.lowercase()} ${it.fqName}" }

    @Test
    fun reportsAddedRemovedAndChangedWithTheirSignatures() = runTest {
        val result = diff(
            from = listOf(
                symbol("com.example.keep", "public fun keep(): kotlin.Unit"),
                symbol("com.example.gone", "public fun gone(): kotlin.Unit"),
                symbol("com.example.widen", "public fun widen(x: kotlin.Int): kotlin.Unit"),
            ),
            to = listOf(
                symbol("com.example.keep", "public fun keep(): kotlin.Unit"),
                symbol("com.example.fresh", "public fun fresh(): kotlin.Unit"),
                symbol("com.example.widen", "public fun widen(x: kotlin.Long): kotlin.Unit"),
            ),
        )

        assertEquals(listOf("added com.example.fresh", "removed com.example.gone", "changed com.example.widen"), result.changes.summary())
        assertEquals(listOf(1, 1, 1, 0), listOf(result.added, result.removed, result.changed, result.deprecated))
        val changed = result.changes.single { it.change == ApiChangeType.CHANGED }
        assertEquals("public fun widen(x: kotlin.Int): kotlin.Unit", changed.fromSignature)
        assertEquals("public fun widen(x: kotlin.Long): kotlin.Unit", changed.toSignature)
        assertNull(result.changes.single { it.change == ApiChangeType.ADDED }.fromSignature)
        assertNull(result.changes.single { it.change == ApiChangeType.REMOVED }.toSignature)
    }

    @Test
    fun reorderedOverloadsAreNotAChange() = runTest {
        // The index keys these `f` and `f#2` in file order, so a release that swaps them renumbers
        // both. Matching by key would report two changed signatures for an unchanged API.
        val byInt = symbol("com.example.f", "public fun f(x: kotlin.Int): kotlin.Unit")
        val byString = symbol("com.example.f", "public fun f(x: kotlin.String): kotlin.Unit")

        val result = diff(from = listOf(byInt, byString), to = listOf(byString, byInt))

        assertEquals(emptyList(), result.changes)
    }

    @Test
    fun anOverloadInsertedAheadOfAnExistingOneIsAddedNotChanged() = runTest {
        val byInt = symbol("com.example.f", "public fun f(x: kotlin.Int): kotlin.Unit")
        val byString = symbol("com.example.f", "public fun f(x: kotlin.String): kotlin.Unit")

        val result = diff(from = listOf(byInt), to = listOf(byString, byInt))

        assertEquals(listOf("added com.example.f"), result.changes.summary())
        assertEquals(byString.signature, result.changes.single().toSignature)
    }

    @Test
    fun severalChangedOverloadsAreReportedAsRemovalsAndAdditions() = runTest {
        // With two on each side there is no telling which became which, so none is guessed at.
        val result = diff(
            from = listOf(
                symbol("com.example.f", "public fun f(x: kotlin.Int): kotlin.Unit"),
                symbol("com.example.f", "public fun f(x: kotlin.String): kotlin.Unit"),
            ),
            to = listOf(
                symbol("com.example.f", "public fun f(x: kotlin.Long): kotlin.Unit"),
                symbol("com.example.f", "public fun f(x: kotlin.CharSequence): kotlin.Unit"),
            ),
        )

        assertEquals(listOf(2, 2, 0), listOf(result.added, result.removed, result.changed))
    }

    @Test
    fun perTargetCopiesAndReflowedTextAreOneDeclaration() = runTest {
        // A KMP index lists a common declaration once per target tree; best-effort text keeps the
        // source's own line breaks.
        val result = diff(
            from = listOf(
                symbol("com.example.f", "public fun f(\n    x: kotlin.Int,\n): kotlin.Unit"),
                symbol("com.example.f", "public fun f( x: kotlin.Int, ): kotlin.Unit", path = "jvm/commonMain/com/example/Api.kt"),
            ),
            to = listOf(symbol("com.example.f", "public fun f( x: kotlin.Int, ): kotlin.Unit")),
        )

        assertEquals(emptyList(), result.changes)
    }

    @Test
    fun movingFromExpectActualToCommonCodeIsNotAChange() = runTest {
        // The actual renders without the default it inherits, so it is a distinct signature; were
        // it kept, the move would read as a removal of `f(x: Int)`.
        val result = diff(
            from = listOf(
                symbol("com.example.f", "public expect fun f(x: kotlin.Int = 1): kotlin.Unit", modifiers = listOf("expect")),
                symbol(
                    "com.example.f",
                    "public actual fun f(x: kotlin.Int): kotlin.Unit",
                    modifiers = listOf("actual"),
                    path = "jvm/jvmMain/com/example/Api.jvm.kt",
                ),
            ),
            to = listOf(symbol("com.example.f", "public fun f(x: kotlin.Int = 1): kotlin.Unit")),
        )

        assertEquals(emptyList(), result.changes)
    }

    @Test
    fun anActualWithNothingElseDeclaringItIsStillApi() = runTest {
        val result = diff(
            from = emptyList(),
            to = listOf(symbol("com.example.f", "public actual fun f(): kotlin.Unit", modifiers = listOf("actual"))),
        )

        assertEquals(listOf("added com.example.f"), result.changes.summary())
    }

    @Test
    fun membersOfANonPublicClassAreNotApi() = runTest {
        // The analyzer records *effective* visibility, so `Hidden`'s members arrive internal
        // although their own signatures say `public`; the diff takes the field at its word.
        val hidden = symbol("com.example.Hidden", "internal class Hidden", SymbolKind.CLASS, Visibility.INTERNAL)
        val shown = symbol("com.example.Shown", "public class Shown", SymbolKind.CLASS)
        val result = diff(
            from = listOf(
                hidden,
                symbol("com.example.Hidden.member", "public fun member(): kotlin.Unit", visibility = Visibility.INTERNAL),
                symbol("com.example.Hidden.Nested.deep", "public fun deep(): kotlin.Unit", visibility = Visibility.INTERNAL),
                shown,
                symbol("com.example.Shown.member", "public fun member(): kotlin.Unit"),
                symbol("com.example.Shown.secret", "private fun secret(): kotlin.Unit", visibility = Visibility.PRIVATE),
            ),
            to = listOf(hidden, shown),
        )

        assertEquals(listOf("removed com.example.Shown.member"), result.changes.summary())
    }

    @Test
    fun protectedMembersArePartOfTheApi() = runTest {
        val open = symbol("com.example.Base", "public open class Base", SymbolKind.CLASS)
        val result = diff(
            from = listOf(open),
            to = listOf(open, symbol("com.example.Base.hook", "protected open fun hook(): kotlin.Unit", visibility = Visibility.PROTECTED)),
        )

        assertEquals(listOf("added com.example.Base.hook"), result.changes.summary())
    }

    @Test
    fun aClassAndAFactoryFunctionSharingANameAreComparedSeparately() = runTest {
        // One group would hold two unmatched signatures a side, and report both as removed + added.
        val result = diff(
            from = listOf(
                symbol("com.example.Client", "public class Client", SymbolKind.CLASS),
                symbol("com.example.Client", "public fun Client(): com.example.Client"),
            ),
            to = listOf(
                symbol("com.example.Client", "public open class Client", SymbolKind.CLASS),
                symbol("com.example.Client", "public fun Client(name: kotlin.String): com.example.Client"),
            ),
        )

        assertEquals(listOf(ApiChangeType.CHANGED, ApiChangeType.CHANGED), result.changes.map { it.change })
        assertEquals(setOf(SymbolKind.CLASS, SymbolKind.FUNCTION), result.changes.map { it.kind }.toSet())
    }

    @Test
    fun newlyDeprecatedAndEscalatedAreDeprecatedNotChanged() = runTest {
        // The resolved signature *includes* the annotation, so without normalization the newly
        // deprecated one would read as a changed signature.
        val warning = Deprecation(Deprecation.Level.WARNING, message = "Use b", replaceWith = "b()")
        val error = Deprecation(Deprecation.Level.ERROR, message = "Use b")
        val result = diff(
            from = listOf(
                symbol("com.example.a", "public fun a(): kotlin.Unit"),
                symbol("com.example.escalated", "@kotlin.Deprecated(message = \"Use b\") public fun escalated(): kotlin.Unit", deprecation = warning),
                symbol("com.example.relaxed", "public fun relaxed(): kotlin.Unit", deprecation = error),
                symbol("com.example.same", "public fun same(): kotlin.Unit", deprecation = warning),
            ),
            to = listOf(
                symbol(
                    "com.example.a",
                    "@kotlin.Deprecated(message = \"Use b (or \\\"c)\\\"\", replaceWith = kotlin.ReplaceWith(expression = \"b()\")) public fun a(): kotlin.Unit",
                    deprecation = warning,
                ),
                symbol("com.example.escalated", "@kotlin.Deprecated(message = \"Use b\", level = kotlin.DeprecationLevel.ERROR) public fun escalated(): kotlin.Unit", deprecation = error),
                symbol("com.example.relaxed", "public fun relaxed(): kotlin.Unit", deprecation = warning),
                symbol("com.example.same", "public fun same(): kotlin.Unit", deprecation = warning),
            ),
        )

        assertEquals(listOf("deprecated com.example.a", "deprecated com.example.escalated"), result.changes.summary())
        assertEquals("b()", result.changes.first().deprecation?.replaceWith)
        assertEquals(Deprecation.Level.ERROR, result.changes.last().deprecation?.level)
        assertEquals(2, result.deprecated)
    }

    @Test
    fun aRemovedDeclarationCarriesTheDeprecationItHadBeforeItWent() = runTest {
        // That is where the author named the replacement, and the caller migrating off it needs it.
        val result = diff(
            from = listOf(
                symbol(
                    "com.example.old",
                    "public fun old(): kotlin.Unit",
                    deprecation = Deprecation(Deprecation.Level.HIDDEN, replaceWith = "fresh()"),
                ),
            ),
            to = emptyList(),
        )

        assertEquals("fresh()", result.changes.single().deprecation?.replaceWith)
    }

    @Test
    fun aPairingAcrossABestEffortSideIsApproximate() = runTest {
        val result = diff(
            from = listOf(
                symbol("com.example.f", "public fun f(x: kotlin.Int): kotlin.Unit"),
                symbol("com.example.gone", "fun gone(x: Int)", bestEffort = true),
            ),
            to = listOf(symbol("com.example.f", "fun f(x: Int)", bestEffort = true)),
        )

        val (changed, removed) = result.changes.partition { it.change == ApiChangeType.CHANGED }
        assertTrue(changed.single().approximate, "resolved vs text: the difference may be formatting alone")
        assertFalse(removed.single().approximate, "a name that vanished is removed however it was read")
    }

    @Test
    fun packagesOnlyOneSideHasAreListed() = runTest {
        val result = diff(
            from = listOf(
                symbol("com.example.kept.A", "public class A", SymbolKind.CLASS),
                symbol("com.example.old.B", "public class B", SymbolKind.CLASS),
            ),
            to = listOf(
                symbol("com.example.kept.A", "public class A", SymbolKind.CLASS),
                symbol("com.example.fresh.C", "public class C", SymbolKind.CLASS),
            ),
        )

        assertEquals(listOf("com.example.fresh"), result.packagesAdded)
        assertEquals(listOf("com.example.old"), result.packagesRemoved)
        assertFalse(result.packagesTruncated)
    }

    @Test
    fun packageNarrowsTheComparisonAndChangeNarrowsOnlyThePage() = runTest {
        val from = listOf(
            symbol("com.example.a.gone", "public fun gone(): kotlin.Unit"),
            symbol("com.example.b.gone", "public fun gone(): kotlin.Unit"),
        )
        val to = listOf(
            symbol("com.example.a.fresh", "public fun fresh(): kotlin.Unit"),
            symbol("com.example.b.fresh", "public fun fresh(): kotlin.Unit"),
        )

        val inA = diff(from, to, packageName = "com.example.a")
        assertEquals(listOf("added com.example.a.fresh", "removed com.example.a.gone"), inA.changes.summary())
        assertEquals("com.example.a", inA.packageName)

        val removedOnly = diff(from, to, change = ApiChangeType.REMOVED)
        assertEquals(listOf("removed com.example.a.gone", "removed com.example.b.gone"), removedOnly.changes.summary())
        assertEquals(2, removedOnly.totalCount)
        assertEquals(2, removedOnly.added, "the summary still counts every type")
    }

    @Test
    fun aPackageNeitherVersionHasIsAnError() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            diff(listOf(symbol("com.example.f", "public fun f(): kotlin.Unit")), emptyList(), packageName = "com.exampel")
        }
        assertTrue("com.exampel" in error.message.orEmpty())
    }

    @Test
    fun anUnfetchedVersionNamesTheFetchToMake() = runTest {
        val error = assertFailsWith<LibraryNotFetchedException> {
            service(emptyList(), emptyList()).diffApi(group, artifact, "1.0", "3.0")
        }
        assertTrue("fetch_library" in error.message.orEmpty() && "$group:$artifact:3.0" in error.message.orEmpty())
    }

    @Test
    fun changesAreSortedAndPaged() = runTest {
        val added = (1..25).map { symbol("com.example.f%02d".format(it), "public fun f$it(): kotlin.Unit") }

        val first = diff(emptyList(), added.shuffled(kotlin.random.Random(7)), maxResults = 10)
        assertEquals((1..10).map { "added com.example.f%02d".format(it) }, first.changes.summary())
        assertEquals(25, first.totalCount)
        assertTrue(first.truncated)

        val last = diff(emptyList(), added, maxResults = 10, offset = 20)
        assertEquals(5, last.changes.size)
        assertFalse(last.truncated)
    }

    @Test
    fun aWholesaleRewriteStaysBounded() = runTest {
        // A major release of a big library: every declaration gone, as many new ones in new packages.
        val from = (1..10_000).map { symbol("com.old.p${it % 300}.Type$it", "public class Type$it", SymbolKind.CLASS) }
        val to = (1..10_000).map { symbol("com.fresh.p${it % 300}.Type$it", "public class Type$it", SymbolKind.CLASS) }

        val result = diff(from, to, maxResults = Int.MAX_VALUE)

        assertEquals(10_000, result.added)
        assertEquals(10_000, result.removed)
        assertEquals(LibraryService.MAX_API_DIFF_RESULTS, result.changes.size, "clamped to the ceiling")
        assertEquals(LibraryService.MAX_API_DIFF_PACKAGES, result.packagesAdded.size)
        assertTrue(result.packagesTruncated)
        // Emitted twice on the wire (text and structuredContent); one copy must stay modest.
        val bytes = toolJson.encodeToString(result).length
        assertTrue(bytes < 100_000, "a full page is $bytes bytes")
    }
}
