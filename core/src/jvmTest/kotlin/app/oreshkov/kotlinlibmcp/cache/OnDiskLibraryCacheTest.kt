@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.cache

import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class OnDiskLibraryCacheTest {

    private val root: Path = createTempDirectory("klm-cache-test")
    private val cache = OnDiskLibraryCache(root)
    private val coordinate = LibraryCoordinate("com.example", "demo-lib", "1.0.0")
    private val index = LibraryIndex(
        coordinate = coordinate,
        targets = listOf(KmpTarget.COMMON, KmpTarget.JVM),
        fetchedAt = Instant.fromEpochSeconds(1_750_000_000),
    )

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun missReturnsNull() = runTest {
        assertNull(cache.get(coordinate))
    }

    @Test
    fun putIndexThenGetRoundtrips() = runTest {
        cache.putIndex(index)

        assertEquals(index, cache.get(coordinate))
    }

    @Test
    fun corruptIndexDegradesToMiss() = runTest {
        val dir = CacheLayout.versionDir(root, coordinate).createDirectories()
        dir.resolve(CacheLayout.INDEX_FILE).writeText("not json {")

        assertNull(cache.get(coordinate))
    }

    @Test
    fun listReturnsAllCachedCoordinates() = runTest {
        val other = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
        cache.putIndex(index)
        cache.putIndex(index.copy(coordinate = other))

        assertEquals(setOf(coordinate, other), cache.list().toSet())
    }

    @Test
    fun sizeCountsBytesAndClearEvicts() = runTest {
        cache.putIndex(index)
        assertTrue(cache.size() > 0)

        cache.clear(coordinate)

        assertNull(cache.get(coordinate))
        assertEquals(emptyList(), cache.list())
        assertEquals(0, cache.size())
    }

    @Test
    fun putSourcesCopiesExternalDirIntoCanonicalLocation() = runTest {
        val staging = createTempDirectory("klm-staging").also { dir ->
            dir.resolve("jvm/com/example").createDirectories()
                .resolve("A.kt").writeText("package com.example\n")
        }
        try {
            cache.putSources(coordinate, staging.toString())

            val canonical = CacheLayout.versionDir(root, coordinate).resolve(CacheLayout.SOURCES_DIR)
            assertEquals(
                "package com.example\n",
                canonical.resolve("jvm/com/example/A.kt").readText(),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun putSourcesInPlaceIsNoOp() = runTest {
        val canonical = CacheLayout.versionDir(root, coordinate).resolve(CacheLayout.SOURCES_DIR)
        canonical.resolve("jvm").createDirectories().resolve("A.kt").writeText("package p\n")

        cache.putSources(coordinate, canonical.toString())

        assertTrue(canonical.resolve("jvm/A.kt").exists())
    }
}
