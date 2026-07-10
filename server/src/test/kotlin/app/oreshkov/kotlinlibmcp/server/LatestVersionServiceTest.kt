package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * Covers the server-side latest-version resolution (`LibraryService.latestVersion` /
 * `resolveCoordinate`) against a fake fetcher — no network, no analysis, no cache IO.
 */
class LatestVersionServiceTest {

    private fun service(catalog: VersionCatalog): LibraryService = fakeService(catalog)

    @Test
    fun latestVersionPrefersReleaseAndLatestTags() = runTest {
        val result = service(
            VersionCatalog(versions = listOf("1.0.0", "2.0.0", "2.1.0-rc1"), release = "2.0.0", latest = "2.1.0-rc1"),
        ).latestVersion("io.ktor", "ktor-client-core", includePreReleases = false)

        assertEquals("2.0.0", result.latestStable)
        assertEquals("2.1.0-rc1", result.latest)
        assertEquals(3, result.totalVersions)
        assertEquals(false, result.includedPreReleases)
    }

    @Test
    fun latestVersionFallsBackToSemanticPickWhenTagsMissing() = runTest {
        val result = service(
            VersionCatalog(versions = listOf("1.0.0", "1.2.0", "1.3.0-beta1"), release = null, latest = null),
        ).latestVersion("com.example", "demo", includePreReleases = true)

        assertEquals("1.2.0", result.latestStable)
        assertEquals("1.3.0-beta1", result.latest)
        assertEquals(true, result.includedPreReleases)
    }

    @Test
    fun latestVersionIgnoresPreReleaseReleaseTag() = runTest {
        // Some publishers (e.g. Kotlin) point <release> at a Beta — the stable pick must not follow it.
        val result = service(
            VersionCatalog(
                versions = listOf("2.2.20", "2.4.10", "2.4.20-Beta1"),
                release = "2.4.20-Beta1",
                latest = "2.4.20-Beta1",
            ),
        ).latestVersion("org.jetbrains.kotlin", "kotlin-stdlib", includePreReleases = false)

        assertEquals("2.4.10", result.latestStable)
        assertEquals("2.4.20-Beta1", result.latest)
    }

    @Test
    fun resolveCoordinateIgnoresPreReleaseReleaseTagWhenStableExists() = runTest {
        val coordinate = service(
            VersionCatalog(versions = listOf("2.2.20", "2.4.10", "2.4.20-Beta1"), release = "2.4.20-Beta1"),
        ).resolveCoordinate("org.jetbrains.kotlin", "kotlin-stdlib", "latest")

        assertEquals("2.4.10", coordinate.version)
    }

    @Test
    fun latestVersionFailsWhenNoVersionsPublished() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service(VersionCatalog(versions = emptyList())).latestVersion("x", "y", includePreReleases = false)
        }
    }

    @Test
    fun resolveCoordinatePassesConcreteVersionThrough() = runTest {
        val coordinate = service(VersionCatalog(versions = listOf("9.9.9")))
            .resolveCoordinate("io.ktor", "ktor-client-core", "3.5.1")

        assertEquals(LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1"), coordinate)
    }

    @Test
    fun resolveCoordinateResolvesLatestToStableRelease() = runTest {
        val svc = service(
            VersionCatalog(versions = listOf("1.0.0", "2.0.0", "2.1.0-rc1"), release = "2.0.0", latest = "2.1.0-rc1"),
        )
        assertEquals("2.0.0", svc.resolveCoordinate("g", "a", "latest").version)
        assertEquals("2.0.0", svc.resolveCoordinate("g", "a", null).version)
        assertEquals("2.0.0", svc.resolveCoordinate("g", "a", "LATEST").version)
    }

    @Test
    fun resolveCoordinateFallsBackToPreReleaseWhenNoStableExists() = runTest {
        val coordinate = service(
            VersionCatalog(versions = listOf("1.0.0-alpha1", "1.0.0-beta1"), release = null, latest = null),
        ).resolveCoordinate("g", "a", "latest")

        assertEquals("1.0.0-beta1", coordinate.version)
    }
}
