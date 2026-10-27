package app.oreshkov.kotlinlibmcp

import app.oreshkov.kotlinlibmcp.util.MavenVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenVersionsTest {

    @Test
    fun stableVersionsHaveNoPreReleaseQualifier() {
        assertTrue(MavenVersions.isStable("1.0.0"))
        assertTrue(MavenVersions.isStable("3.5.1"))
        assertTrue(MavenVersions.isStable("2.4.0"))
        // Classifier releases are stable — not "any hyphen".
        assertTrue(MavenVersions.isStable("33.4.0-jre"))
        assertTrue(MavenVersions.isStable("33.4.0-android"))
    }

    @Test
    fun preReleaseQualifiersAreDetected() {
        assertFalse(MavenVersions.isStable("2.0.0-alpha01"))
        assertFalse(MavenVersions.isStable("2.0.0-Beta1"))
        assertFalse(MavenVersions.isStable("2.4.0-RC2"))
        assertFalse(MavenVersions.isStable("1.0-M3"))
        assertFalse(MavenVersions.isStable("1.0.0-SNAPSHOT"))
        assertFalse(MavenVersions.isStable("0.14.0-eap-1"))
    }

    @Test
    fun comparatorOrdersNumericSegmentsNumerically() {
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("1.10.0", "1.9.0") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("2.0.0", "1.9.9") > 0)
        assertEquals(0, MavenVersions.VERSION_COMPARATOR.compare("1.0", "1.0.0"))
    }

    @Test
    fun releaseOutranksPreReleaseAtSameBase() {
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("1.0.0", "1.0.0-rc1") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("2.0.0-rc1", "2.0.0-beta2") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("2.0.0-beta2", "2.0.0-alpha9") > 0)
    }

    @Test
    fun selectLatestPrefersStableUnlessPreReleasesIncluded() {
        val versions = listOf("1.0.0", "2.0.0", "2.1.0-alpha01", "0.9.0")
        assertEquals("2.0.0", MavenVersions.selectLatest(versions, includePreReleases = false))
        assertEquals("2.1.0-alpha01", MavenVersions.selectLatest(versions, includePreReleases = true))
    }

    @Test
    fun earlyAccessBuildsSortBelowTheReleaseTheyPrecede() {
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("3.0.0", "3.0.0-eap-852") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("2.0.0", "2.0.0-dev-1234") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("1.0.0", "1.0.0-preview1") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("25", "25-ea+3") > 0)
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("1.0.0", "1.0.0-pre.2") > 0)
        // ...but a later base still wins over an earlier release.
        assertTrue(MavenVersions.VERSION_COMPARATOR.compare("3.1.0-eap-1", "3.0.0") > 0)
    }

    @Test
    fun selectLatestWithPreReleasesPicksTheReleaseOverItsEap() {
        assertEquals(
            "3.0.0",
            MavenVersions.selectLatest(listOf("3.0.0-eap-1", "3.0.0"), includePreReleases = true),
        )
    }

    @Test
    fun shortAlphaBetaQualifiersAreNotStable() {
        assertFalse(MavenVersions.isStable("2.0b1"))
        assertFalse(MavenVersions.isStable("2.3.0-b170201"))
        assertFalse(MavenVersions.isStable("1.0a2"))
        assertEquals("1.9", MavenVersions.selectLatest(listOf("1.9", "2.0b1")))
    }

    @Test
    fun selectLatestReturnsNullWhenNoStableRelease() {
        val versions = listOf("1.0.0-alpha1", "1.0.0-beta1")
        assertNull(MavenVersions.selectLatest(versions, includePreReleases = false))
        assertEquals("1.0.0-beta1", MavenVersions.selectLatest(versions, includePreReleases = true))
    }
}
