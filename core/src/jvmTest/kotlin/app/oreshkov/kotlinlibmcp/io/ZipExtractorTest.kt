@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.io

import app.oreshkov.kotlinlibmcp.zipBytes
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class ZipExtractorTest {

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): Path = createTempDirectory("klm-zip-test").also(tempDirs::add)

    private fun writeZip(entries: Map<String, String>): Path =
        tempDir().resolve("fixture.jar").also { it.writeBytes(zipBytes(entries)) }

    @Test
    fun extractsPreservingPackageStructure() = runTest {
        val zip = writeZip(
            mapOf(
                "com/example/A.kt" to "package com.example\n\nclass A\n",
                "com/example/inner/B.kt" to "package com.example.inner\n\nclass B\n",
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
            ),
        )
        val target = tempDir()

        val extracted = ZipExtractor().extract(zip, target)

        assertEquals(
            setOf("com/example/A.kt", "com/example/inner/B.kt", "META-INF/MANIFEST.MF"),
            extracted.toSet(),
        )
        assertEquals("package com.example.inner\n\nclass B\n", target.resolve("com/example/inner/B.kt").readText())
    }

    @Test
    fun rejectsPathTraversalEntry() = runTest {
        val zip = writeZip(
            mapOf(
                "safe.kt" to "package p\n",
                "../evil.txt" to "escaped!",
            ),
        )
        val parent = tempDir()
        val target = parent.resolve("out")

        assertFailsWith<ZipExtractionException> { ZipExtractor().extract(zip, target) }
        assertFalse(parent.resolve("evil.txt").exists(), "traversal entry must not be written")
    }

    @Test
    fun enforcesEntryCountLimit() = runTest {
        val zip = writeZip((1..3).associate { "file$it.kt" to "package p\n" })

        assertFailsWith<ZipExtractionException> {
            ZipExtractor(maxEntries = 2).extract(zip, tempDir())
        }
    }

    @Test
    fun enforcesTotalSizeLimit() = runTest {
        val zip = writeZip(mapOf("big.kt" to "x".repeat(2048)))

        assertFailsWith<ZipExtractionException> {
            ZipExtractor(maxTotalBytes = 1024).extract(zip, tempDir())
        }
    }

    @Test
    fun abortsSingleOversizedEntryMidCopy() = runTest {
        // A single entry far larger than the cap must be aborted *during* the copy, not streamed to
        // disk in full and then rejected (zip-bomb: one entry can be highly compressed).
        val cap = 4096L
        val zip = writeZip(mapOf("big.kt" to "x".repeat(1_000_000)))
        val target = tempDir()

        assertFailsWith<ZipExtractionException> {
            ZipExtractor(maxTotalBytes = cap).extract(zip, target)
        }
        // Proof it did not write the whole megabyte: any partial output stays within budget + one buffer.
        val partial = target.resolve("big.kt")
        if (partial.exists()) {
            assertTrue(partial.fileSize() <= cap + 64 * 1024, "must abort mid-copy, not after writing the full entry")
        }
    }

    @Test
    fun extractingIntoExistingDirIsIdempotent() = runTest {
        val zip = writeZip(mapOf("com/example/A.kt" to "package com.example\n"))
        val target = tempDir()

        ZipExtractor().extract(zip, target)
        val second = ZipExtractor().extract(zip, target)

        assertTrue(target.resolve("com/example/A.kt").exists())
        assertEquals(listOf("com/example/A.kt"), second)
    }

    @Test
    fun stopsMidEntryWhenCancelled() = runBlocking {
        // One highly compressible entry: a few KB of archive, BIG_ENTRY_BYTES on disk if finished.
        val zip = tempDir().resolve("zeros.jar")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Zeros.kt"))
            val chunk = ByteArray(64 * 1024)
            repeat((BIG_ENTRY_BYTES / chunk.size).toInt()) { out.write(chunk) }
            out.closeEntry()
        }
        val target = tempDir()
        val extracted = target.resolve("Zeros.kt")

        val extraction = launch(Dispatchers.IO) { ZipExtractor().extract(zip, target) }
        while (!(extracted.exists() && extracted.fileSize() > 0)) yield()
        extraction.cancelAndJoin()

        // Uncancellable, the copy runs to the end of the entry before the job can complete.
        assertTrue(
            extracted.fileSize() < BIG_ENTRY_BYTES / 2,
            "wrote ${extracted.fileSize()} of $BIG_ENTRY_BYTES bytes after cancellation",
        )
    }

    private companion object {
        const val BIG_ENTRY_BYTES: Long = 64L * 1024 * 1024
    }
}
