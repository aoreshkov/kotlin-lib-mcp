@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.analyze.AnalysisApiSourceAnalyzer
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.dto.ApiChangeType
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.Visibility
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * `list_declarations` and `diff_api` must give one answer to "what is the public API", on an index
 * the real analyzer built — an in-memory index can only restate whatever contract the test author
 * assumed. The sources sit in a `common/` and a `jvm/` tree at once, as a KMP library's do in the
 * cache, so every declaration is indexed twice.
 */
class PublicApiAgreementTest {

    private companion object {
        const val GROUP = "com.example"
        const val ARTIFACT = "agree"

        val SOURCE = """
            package com.example.agree

            public data class Person(val name: String, private val id: Int)

            internal class Hidden {
                fun member(): Int = 1
            }

            public abstract class Base {
                protected abstract fun hook()
            }

            public class Impl : Base() {
                override fun hook() {}
            }
        """.trimIndent()

        /** Standalone sessions are expensive — analyze once for all tests. */
        val index: LibraryIndex by lazy {
            val root = createTempDirectory("klm-agreement-test")
            try {
                val roots = listOf("common", "jvm").map { target ->
                    root.resolve(target).also {
                        it.resolve("com/example/agree").createDirectories().resolve("Api.kt").writeText(SOURCE)
                    }
                }
                AnalysisApiSourceAnalyzer().analyze(
                    LibraryCoordinate(GROUP, ARTIFACT, "2.0"),
                    sourceRoots = roots.map { it.toString() },
                    classpathRoots = emptyList(),
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private val service by lazy {
        val empty = LibraryIndex(LibraryCoordinate(GROUP, ARTIFACT, "1.0"), fetchedAt = Instant.fromEpochSeconds(0))
        val indexes = listOf(empty, index).associateBy { it.coordinate }
        LibraryService(
            fetcher = FakeFetcher(VersionCatalog(versions = emptyList())),
            analyzer = UnusedAnalyzer,
            cache = object : LibraryCache {
                override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = indexes[coordinate]
                override suspend fun putIndex(index: LibraryIndex) = Unit
                override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
                override suspend fun list(): List<LibraryCoordinate> = indexes.keys.toList()
                override suspend fun clear(coordinate: LibraryCoordinate) = Unit
                override suspend fun size(): Long = 0
            },
        )
    }

    private val coordinate = LibraryCoordinate(GROUP, ARTIFACT, "2.0")

    @Test
    fun listDeclarationsAndDiffApiAgreeOnThePublicSurface() = runTest {
        val listed = service.listDeclarations(coordinate, packageName = null, visibility = "all")
            .declarations.filter { it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED }
            .map { it.fqName }
        val added = service.diffApi(GROUP, ARTIFACT, "1.0", "2.0", change = ApiChangeType.ADDED)
            .changes.map { it.fqName }

        assertEquals(listed.sorted(), added.sorted())
        assertTrue("com.example.agree.Hidden.member" !in listed, "an internal class's member is not API: $listed")
        assertTrue("com.example.agree.Impl.hook" in listed, "a protected override is API: $listed")
    }

    @Test
    fun listsEachPublicDeclarationOnceDespiteTwoTargetTrees() = runTest {
        val result = service.listDeclarations(coordinate, packageName = null, visibility = null)
        val names = result.declarations.map { it.fqName }

        assertEquals(
            listOf("com.example.agree.Base", "com.example.agree.Impl", "com.example.agree.Person", "com.example.agree.Person.name"),
            names.sorted(),
        )
        assertEquals(names.size, result.totalCount)
    }

    @Test
    fun aConstructorPropertyHasASignature() = runTest {
        assertEquals(
            "public val name: kotlin.String",
            service.getSignature(coordinate, "com.example.agree.Person.name").symbol.signature,
        )
    }
}
