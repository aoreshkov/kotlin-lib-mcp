package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.testing.record
import app.oreshkov.kotlinlibmcp.fetch.MavenSourceFetcherImpl.Companion.MAVEN_CENTRAL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Which Maven repositories a fetch searches. `--repo` adds to Maven Central, as its help, the
 * README and `server.json` all say; only `--no-maven-central` takes Central away. `core` reads an
 * empty list as "Central only" and any other list as exactly those, so the server must hand it the
 * whole list, Central included.
 */
class RepositoriesTest {

    private val google = "https://maven.google.com"
    private val mirror = "https://nexus.example.com/repository/maven-public"

    private fun searched(vararg args: String): List<String> {
        var captured: CliOptions? = null
        val result = Cli.program { options, _ ->
            captured = options
            ExitStatus.OK
        }.record(*args)
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        return (captured ?: fail("serve was not called")).config.searchedRepos
    }

    @Test
    fun withoutRepoOnlyCentralIsSearched() {
        assertEquals(listOf(MAVEN_CENTRAL), searched())
    }

    @Test
    fun repoAddsToCentralInsteadOfReplacingIt() {
        assertEquals(listOf(MAVEN_CENTRAL, google, mirror), searched("--repo", google, "--repo", mirror))
    }

    @Test
    fun centralNamedAsARepoIsSearchedOnce() {
        assertEquals(listOf(MAVEN_CENTRAL, google), searched("--repo", MAVEN_CENTRAL.trimEnd('/'), "--repo", google))
    }

    @Test
    fun noMavenCentralSearchesOnlyTheGivenRepos() {
        assertEquals(listOf(mirror), searched("--repo", mirror, "--no-maven-central"))
    }

    @Test
    fun noMavenCentralWithoutARepoIsAUsageError() {
        // Nothing would be left to search, and core would quietly read the empty list as Central.
        val result = Cli.program { _, _ -> fail("must not serve") }.record("--no-maven-central")
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals("", result.stdout, "a usage error goes to stderr, never the protocol channel")
        assertContains(result.stderr, "--repo")
    }

    @Test
    fun theFactoryHandsTheServiceTheWholeList() {
        val cacheDir = Files.createTempDirectory("repositories-test")
        try {
            McpServerFactory.create(ServerConfig(cacheDir = cacheDir, repos = listOf(google))).use {
                assertEquals(listOf(MAVEN_CENTRAL, google), it.service.repos)
            }
            assertFailsWith<IllegalArgumentException> {
                McpServerFactory.create(ServerConfig(cacheDir = cacheDir, mavenCentral = false))
            }
        } finally {
            cacheDir.toFile().deleteRecursively()
        }
    }
}
