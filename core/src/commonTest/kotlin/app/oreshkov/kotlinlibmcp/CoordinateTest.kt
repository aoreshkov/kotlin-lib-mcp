package app.oreshkov.kotlinlibmcp

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.util.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CoordinateTest {

    @Test
    fun parseFormatRoundTrip() {
        val text = "io.ktor:ktor-client-core:3.5.1"
        val coordinate = LibraryCoordinate.parse(text)
        assertEquals(LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1"), coordinate)
        assertEquals(text, coordinate.toString())
    }

    @Test
    fun parseTrimsWhitespace() {
        assertEquals(
            LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1"),
            LibraryCoordinate.parse("  io.ktor : ktor-client-core : 3.5.1 "),
        )
    }

    @Test
    fun parseRejectsWrongPartCount() {
        assertFailsWith<IllegalArgumentException> { LibraryCoordinate.parse("io.ktor:ktor-client-core") }
        assertFailsWith<IllegalArgumentException> { LibraryCoordinate.parse("a:b:c:d") }
    }

    @Test
    fun parseRejectsBlankParts() {
        assertFailsWith<IllegalArgumentException> { LibraryCoordinate.parse("io.ktor::3.5.1") }
    }

    @Test
    fun parseOrNullReturnsNullOnInvalid() {
        assertNull(LibraryCoordinate.parseOrNull("not-a-coordinate"))
        assertNull(LibraryCoordinate.parseOrNull("a:b:c:d"))
    }

    @Test
    fun directConstructionRejectsBlanks() {
        assertFailsWith<IllegalArgumentException> { LibraryCoordinate("", "artifact", "1.0") }
    }

    @Test
    fun repositoryPathDerivation() {
        val coordinate = LibraryCoordinate.parse("io.ktor:ktor-client-core:3.5.1")
        assertEquals("io/ktor/ktor-client-core/3.5.1", Coordinates.repositoryPath(coordinate))
        assertEquals(
            "io/ktor/ktor-client-core/3.5.1/ktor-client-core-3.5.1.pom",
            Coordinates.artifactPath(coordinate, "ktor-client-core-3.5.1.pom"),
        )
    }

    @Test
    fun metadataPathDerivation() {
        assertEquals(
            "io/ktor/ktor-client-core/maven-metadata.xml",
            Coordinates.metadataPath("io.ktor", "ktor-client-core"),
        )
    }
}
