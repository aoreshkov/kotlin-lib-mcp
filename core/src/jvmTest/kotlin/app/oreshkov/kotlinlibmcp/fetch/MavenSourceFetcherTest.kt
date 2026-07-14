@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.fetch

import app.oreshkov.kotlinlibmcp.fixture
import app.oreshkov.kotlinlibmcp.mockRepoEngine
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.zipBytes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MavenSourceFetcherTest {

    private val repo = "https://repo.test/maven2/"
    private val coordinate = LibraryCoordinate("com.example", "demo-lib", "1.0.0")
    private val tempDirs = mutableListOf<Path>()

    private val commonJar = zipBytes(
        mapOf(
            "com/example/Common.kt" to "package com.example\n\nexpect fun hello(): String\n",
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
        ),
    )
    private val jvmJar = zipBytes(
        mapOf("com/example/Hello.kt" to "package com.example\n\nactual fun hello(): String = \"jvm\"\n"),
    )

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun newCacheDir(): Path = createTempDirectory("klm-fetch-test").also(tempDirs::add)

    private fun fetcher(engine: MockEngine, cacheDir: Path = newCacheDir()): MavenSourceFetcherImpl =
        MavenSourceFetcherImpl(cacheDir = cacheDir, engine = engine)

    @Test
    fun resolvesSourcesVariantsFromModuleMetadataAndFollowsAvailableAt() = runTest {
        val engine = mockRepoEngine(
            mapOf(
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0.module" to fixture("root.module").toByteArray(),
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0-sources.jar" to commonJar,
                "${repo}com/example/demo-lib-jvm/1.0.0/demo-lib-jvm-1.0.0.module" to fixture("jvm.module").toByteArray(),
                "${repo}com/example/demo-lib-jvm/1.0.0/demo-lib-jvm-1.0.0-sources.jar" to jvmJar,
            ),
        )
        fetcher(engine).use { fetcher ->
            val result = fetcher.fetch(coordinate, listOf(repo))

            assertEquals(listOf(KmpTarget.COMMON, KmpTarget.JVM), result.resolvedTargets)
            assertEquals(2, result.downloadedJars.size)
            val byPath = result.files.associateBy { it.path }
            assertEquals("com.example", byPath.getValue("common/com/example/Common.kt").packageName)
            assertEquals(KmpTarget.JVM, byPath.getValue("jvm/com/example/Hello.kt").target)
            assertTrue(Path.of(result.extractedDir).resolve("jvm/com/example/Hello.kt").exists())
            // The js variant in the fixture must not have been followed.
            assertTrue(engine.requestHistory.none { it.url.toString().contains("demo-lib-js") })
        }
    }

    @Test
    fun fallsBackToJvmSuffixedSourcesJarWhenNoModuleMetadata() = runTest {
        val engine = mockRepoEngine(
            mapOf("${repo}com/example/demo-lib-jvm/1.0.0/demo-lib-jvm-1.0.0-sources.jar" to jvmJar),
        )
        fetcher(engine).use { fetcher ->
            val result = fetcher.fetch(coordinate, listOf(repo))

            assertEquals(listOf(KmpTarget.JVM), result.resolvedTargets)
            assertEquals(listOf("jvm/com/example/Hello.kt"), result.files.map { it.path })
        }
    }

    @Test
    fun fallsBackToPlainSourcesJarForOrdinaryJvmLibraries() = runTest {
        val plain = LibraryCoordinate("com.example", "plain-lib", "2.0.0")
        val engine = mockRepoEngine(
            mapOf("${repo}com/example/plain-lib/2.0.0/plain-lib-2.0.0-sources.jar" to jvmJar),
        )
        fetcher(engine).use { fetcher ->
            val result = fetcher.fetch(plain, listOf(repo))

            assertEquals(listOf(KmpTarget.JVM), result.resolvedTargets)
            assertTrue(result.downloadedJars.single().endsWith("plain-lib-2.0.0-sources.jar"))
        }
    }

    @Test
    fun warmCacheServesSecondFetchWithoutNetwork() = runTest {
        val engine = mockRepoEngine(
            mapOf(
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0.module" to fixture("root.module").toByteArray(),
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0-sources.jar" to commonJar,
                "${repo}com/example/demo-lib-jvm/1.0.0/demo-lib-jvm-1.0.0.module" to fixture("jvm.module").toByteArray(),
                "${repo}com/example/demo-lib-jvm/1.0.0/demo-lib-jvm-1.0.0-sources.jar" to jvmJar,
            ),
        )
        fetcher(engine).use { fetcher ->
            val first = fetcher.fetch(coordinate, listOf(repo))
            val requestsAfterFirst = engine.requestHistory.size

            val second = fetcher.fetch(coordinate, listOf(repo))

            assertEquals(requestsAfterFirst, engine.requestHistory.size, "warm fetch must not hit the network")
            assertEquals(first, second)
        }
    }

    @Test
    fun failsOnSha256Mismatch() = runTest {
        val badModule = """
            {
              "formatVersion": "1.1",
              "variants": [
                {
                  "name": "metadataSourcesElements",
                  "attributes": {
                    "org.gradle.category": "documentation",
                    "org.gradle.docstype": "sources",
                    "org.jetbrains.kotlin.platform.type": "common"
                  },
                  "files": [
                    {
                      "name": "demo-lib-1.0.0-sources.jar",
                      "url": "demo-lib-1.0.0-sources.jar",
                      "sha256": "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val engine = mockRepoEngine(
            mapOf(
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0.module" to badModule.toByteArray(),
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0-sources.jar" to commonJar,
            ),
        )
        fetcher(engine).use { fetcher ->
            assertFailsWith<ChecksumMismatchException> { fetcher.fetch(coordinate, listOf(repo)) }
        }
    }

    @Test
    fun missingEverywhereThrowsSourcesNotFound() = runTest {
        fetcher(mockRepoEngine(emptyMap())).use { fetcher ->
            assertFailsWith<SourcesNotFoundException> { fetcher.fetch(coordinate, listOf(repo)) }
        }
    }

    @Test
    fun rejectsDownloadWhenContentLengthExceedsCap() = runTest {
        val plain = LibraryCoordinate("com.example", "plain-lib", "2.0.0")
        val url = "${repo}com/example/plain-lib/2.0.0/plain-lib-2.0.0-sources.jar"
        val engine = MockEngine { request ->
            if (request.url.toString() == url) {
                respond(
                    content = ByteArray(1_000_000),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, "1000000"),
                )
            } else {
                respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.NotFound)
            }
        }
        MavenSourceFetcherImpl(cacheDir = newCacheDir(), engine = engine, maxDownloadBytes = 4096).use { fetcher ->
            assertFailsWith<DownloadTooLargeException> { fetcher.fetch(plain, listOf(repo)) }
        }
    }

    @Test
    fun rejectsStreamedDownloadExceedingCapWithoutContentLength() = runTest {
        val plain = LibraryCoordinate("com.example", "plain-lib", "2.0.0")
        // mockRepoEngine serves a raw channel with no Content-Length, so the cap must be enforced
        // by counting streamed bytes (guards against a lying/absent Content-Length).
        val engine = mockRepoEngine(
            mapOf("${repo}com/example/plain-lib/2.0.0/plain-lib-2.0.0-sources.jar" to ByteArray(1_000_000)),
        )
        MavenSourceFetcherImpl(cacheDir = newCacheDir(), engine = engine, maxDownloadBytes = 4096).use { fetcher ->
            assertFailsWith<DownloadTooLargeException> { fetcher.fetch(plain, listOf(repo)) }
        }
    }

    @Test
    fun listVersionsParsesMavenMetadataNewestFirst() = runTest {
        val engine = mockRepoEngine(
            mapOf("${repo}com/example/demo-lib/maven-metadata.xml" to fixture("maven-metadata.xml").toByteArray()),
        )
        fetcher(engine).use { fetcher ->
            val versions = fetcher.listVersions("com.example", "demo-lib", listOf(repo))

            assertEquals(listOf("1.1.0", "1.0.0", "0.9.0"), versions)
        }
    }

    @Test
    fun fetchVersionCatalogReadsReleaseAndLatestTags() = runTest {
        val engine = mockRepoEngine(
            mapOf("${repo}com/example/demo-lib/maven-metadata.xml" to fixture("maven-metadata.xml").toByteArray()),
        )
        fetcher(engine).use { fetcher ->
            val catalog = fetcher.fetchVersionCatalog("com.example", "demo-lib", listOf(repo))

            assertEquals("1.1.0", catalog.release)
            assertEquals("1.1.0", catalog.latest)
            assertEquals(listOf("0.9.0", "1.0.0", "1.1.0"), catalog.versions)
        }
    }

    @Test
    fun listVersionsSortsNewestFirstBySemanticOrderIncludingPreReleases() = runTest {
        val metadata = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>demo-lib</artifactId>
              <versioning>
                <latest>2.1.0-alpha01</latest>
                <release>2.0.0</release>
                <versions>
                  <version>1.0.0</version>
                  <version>2.1.0-alpha01</version>
                  <version>2.0.0</version>
                </versions>
              </versioning>
            </metadata>
        """.trimIndent()
        val engine = mockRepoEngine(
            mapOf("${repo}com/example/demo-lib/maven-metadata.xml" to metadata.toByteArray()),
        )
        fetcher(engine).use { fetcher ->
            val catalog = fetcher.fetchVersionCatalog("com.example", "demo-lib", listOf(repo))
            assertEquals("2.0.0", catalog.release)
            assertEquals("2.1.0-alpha01", catalog.latest)

            // listVersions (interface default) sorts newest-first: the pre-release outranks 2.0.0 numerically.
            val versions = fetcher.listVersions("com.example", "demo-lib", listOf(repo))
            assertEquals(listOf("2.1.0-alpha01", "2.0.0", "1.0.0"), versions)
        }
    }

    @Test
    fun resolveDependenciesWalksPomsWithDedupeAndScopeFiltering() = runTest {
        val engine = mockRepoEngine(
            mapOf(
                "${repo}com/example/demo-lib/1.0.0/demo-lib-1.0.0.pom" to fixture("sample.pom").toByteArray(),
                "${repo}com/example/dep-a/2.0.0/dep-a-2.0.0.pom" to fixture("dep-a.pom").toByteArray(),
            ),
        )
        fetcher(engine).use { fetcher ->
            val tree = fetcher.resolveDependencies(coordinate, listOf(repo), maxDepth = 3)

            assertEquals(coordinate, tree.coordinate)
            val children = tree.children.associateBy { it.coordinate.artifact }
            // test-scoped and optional deps are filtered out.
            assertEquals(setOf("dep-a", "dep-b", "dep-managed"), children.keys)
            assertEquals("compile", children.getValue("dep-a").scope)
            // ${depB.version} interpolated from <properties>.
            assertEquals("3.1.4", children.getValue("dep-b").coordinate.version)
            // BOM-managed version surfaces as unresolved instead of failing the tree.
            assertEquals(
                MavenSourceFetcherImpl.UNRESOLVED_VERSION,
                children.getValue("dep-managed").coordinate.version,
            )
            // dep-b is claimed at depth 1, so dep-a's own dep-b edge is deduplicated.
            assertEquals(emptyList(), children.getValue("dep-a").children)
        }
    }
}
