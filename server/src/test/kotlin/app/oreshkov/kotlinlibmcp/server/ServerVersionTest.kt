package app.oreshkov.kotlinlibmcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The advertised MCP `Implementation` version must come from the build (gradle.properties), not a
 * hand-maintained constant. Under Gradle the `processResources`-generated resource is on the
 * classpath and the test task passes the expected `project.version`, so we assert they match.
 */
class ServerVersionTest {

    @Test
    fun versionMatchesTheBuildAndIsNeverTheDevFallback() {
        val resolved = ServerVersion.value
        assertTrue(resolved.isNotBlank(), "resolved version must not be blank")

        val expected = System.getProperty("kotlin-lib-mcp.expectedVersion")
        if (expected != null) {
            assertEquals(expected, resolved, "server version must equal project.version from the build")
            assertTrue(
                resolved != ServerVersion.DEV_VERSION,
                "generated version resource should be on the test classpath, not the dev fallback",
            )
        }
    }
}
