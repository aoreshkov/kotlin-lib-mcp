@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.fetch

import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Real-network integration smoke test (Phase 03 acceptance criterion). Skipped unless
 * `KOTLIN_LIB_MCP_SMOKE=1`, so regular `jvmTest` runs stay offline:
 *
 * ```
 * KOTLIN_LIB_MCP_SMOKE=1 ./gradlew :core:jvmTest --tests "*SmokeTest"
 * ```
 */
class MavenSourceFetcherSmokeTest {

    @Test
    fun fetchesKtorClientCoreFromMavenCentral() = runTest(timeout = 5.minutes) {
        if (System.getenv("KOTLIN_LIB_MCP_SMOKE") != "1") return@runTest

        val cacheDir = createTempDirectory("klm-smoke")
        try {
            MavenSourceFetcherImpl(cacheDir).use { fetcher ->
                val coordinate = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")

                val result = fetcher.fetch(coordinate, emptyList())

                assertTrue(KmpTarget.COMMON in result.resolvedTargets, "common sources expected")
                assertTrue(KmpTarget.JVM in result.resolvedTargets, "jvm sources expected")
                assertTrue(
                    result.files.any { it.target == KmpTarget.JVM && it.path.endsWith(".kt") },
                    "extracted .kt files expected under sources/jvm",
                )

                // Second call must be served from the fetch-result.json marker.
                assertEquals(result, fetcher.fetch(coordinate, emptyList()))

                val versions = fetcher.listVersions("io.ktor", "ktor-client-core", emptyList())
                assertTrue("3.5.1" in versions)
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
