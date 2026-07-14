package app.oreshkov.kotlinlibmcp.fetch

import app.oreshkov.kotlinlibmcp.cache.CacheLayout
import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.io.ZipExtractor
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.util.Coordinates
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.io.readByteArray
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Base type for fetch failures surfaced to callers. */
public open class FetchException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** No sources jar could be resolved for the coordinate in any configured repository. */
public class SourcesNotFoundException(message: String) : FetchException(message)

/** A downloaded jar did not match the sha256 published in its Gradle module metadata. */
public class ChecksumMismatchException(message: String) : FetchException(message)

/** A response body exceeded [MavenSourceFetcherImpl.maxDownloadBytes] (declared or streamed). */
public class DownloadTooLargeException(message: String) : FetchException(message)

/**
 * [MavenSourceFetcher] backed by a Ktor client.
 *
 * Resolution order per repository: Gradle `.module` metadata (variants matched by attributes
 * `org.gradle.category=documentation` + `org.gradle.docstype=sources`, one `available-at` hop
 * followed to the per-target companion module), then filename heuristics
 * (`-jvm-<v>-sources.jar`, `<v>-sources.jar`). Downloads land directly in the [CacheLayout]
 * cache dir; a `fetch-result.json` marker makes warm fetches network-free.
 *
 * The [engine] is injectable so tests drive the fetcher with Ktor's `MockEngine`.
 */
public class MavenSourceFetcherImpl(
    private val cacheDir: Path,
    engine: HttpClientEngine = CIO.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val extractor: ZipExtractor = ZipExtractor(),
    private val maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
) : MavenSourceFetcher, AutoCloseable {

    private val log = Logger.withTag("MavenSourceFetcher")

    private val client: HttpClient = HttpClient(engine) {
        expectSuccess = false // 404 = "not published there", handled per request
        install(UserAgent) {
            // Maven Central rejects generic/empty user agents with 403.
            agent = "kotlin-lib-mcp/0.0.1"
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 300_000
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
        }
    }

    override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult {
        val versionDir = CacheLayout.versionDir(cacheDir, coordinate)
        val marker = versionDir.resolve(CacheLayout.FETCH_RESULT_FILE)
        readCachedResult(marker)?.let {
            log.d { "Cache hit for $coordinate" }
            return it
        }
        val repoList = normalizeRepos(repos)
        for (repo in repoList) {
            val result = fetchFromRepo(repo, coordinate, versionDir) ?: continue
            withContext(Dispatchers.IO) {
                versionDir.createDirectories()
                marker.writeText(json.encodeToString(FetchResult.serializer(), result))
            }
            return result
        }
        throw SourcesNotFoundException("No sources jar found for $coordinate in $repoList")
    }

    override suspend fun fetchVersionCatalog(group: String, artifact: String, repos: List<String>): VersionCatalog {
        val versions = LinkedHashSet<String>()
        var release: String? = null
        var latest: String? = null
        for (repo in normalizeRepos(repos)) {
            val xml = getTextOrNull(repo + Coordinates.metadataPath(group, artifact)) ?: continue
            val metadata = runCatching { MavenMetadataParser.parse(xml) }
                .getOrElse { e ->
                    log.w(e) { "Unparseable maven-metadata.xml for $group:$artifact in $repo" }
                    null
                } ?: continue
            versions += metadata.versions
            // Keep the tags from the first repository that declares them (Central is tried first).
            release = release ?: metadata.release
            latest = latest ?: metadata.latest
        }
        return VersionCatalog(versions = versions.toList(), release = release, latest = latest)
    }

    override suspend fun resolveDependencies(
        coordinate: LibraryCoordinate,
        repos: List<String>,
        maxDepth: Int,
    ): DependencyNode {
        val repoList = normalizeRepos(repos)
        val visited = mutableSetOf(coordinate.group to coordinate.artifact)
        return resolveNode(coordinate, scope = null, depth = 0, maxDepth, repoList, visited)
    }

    override fun close() {
        client.close()
    }

    // --- fetch internals ---

    private data class JarPlan(
        val target: KmpTarget,
        val url: String,
        val fileName: String,
        val sha256: String?,
    )

    private suspend fun readCachedResult(marker: Path): FetchResult? =
        withContext(Dispatchers.IO) {
            if (!marker.exists()) return@withContext null
            runCatching { json.decodeFromString(FetchResult.serializer(), marker.readText()) }
                .onFailure { log.w(it) { "Corrupt fetch marker $marker; refetching" } }
                .getOrNull()
        }

    private suspend fun fetchFromRepo(
        repo: String,
        coordinate: LibraryCoordinate,
        versionDir: Path,
    ): FetchResult? {
        val downloaded = mutableListOf<Pair<KmpTarget, Path>>()
        val modulePlans = planViaModuleMetadata(repo, coordinate)
        if (modulePlans != null) {
            for (plan in modulePlans) {
                downloadJar(plan, versionDir)?.let { downloaded += plan.target to it }
            }
        }
        if (downloaded.isEmpty()) {
            if (modulePlans != null) log.i { "Module metadata for $coordinate yielded no sources; falling back to filename heuristics" }
            for (plan in fallbackPlans(repo, coordinate)) {
                val jar = downloadJar(plan, versionDir) ?: continue
                downloaded += plan.target to jar
                break // fallback ladder: first hit wins
            }
        }
        if (downloaded.isEmpty()) return null

        val sourcesRoot = versionDir.resolve(CacheLayout.SOURCES_DIR)
        val targets = sortedSetOf<KmpTarget>()
        val files = mutableListOf<SourceFileRef>()
        for ((target, jar) in downloaded) {
            val targetName = target.name.lowercase()
            val relativePaths = extractor.extract(jar, sourcesRoot.resolve(targetName))
            targets += target
            for (relative in relativePaths) {
                if (!isSourceFile(relative)) continue
                files += SourceFileRef(
                    path = "$targetName/$relative",
                    packageName = packageNameOf(relative),
                    target = target,
                )
            }
        }
        log.i { "Fetched $coordinate from $repo: ${downloaded.size} jar(s), ${files.size} source files" }
        return FetchResult(
            coordinate = coordinate,
            resolvedTargets = targets.toList(),
            downloadedJars = downloaded.map { (_, jar) -> jar.toString() },
            extractedDir = sourcesRoot.toString(),
            files = files,
        )
    }

    /** Plans from `.module` metadata, or `null` when the coordinate publishes none. */
    private suspend fun planViaModuleMetadata(
        repo: String,
        coordinate: LibraryCoordinate,
    ): List<JarPlan>? {
        val text = getTextOrNull(repo + moduleFilePath(coordinate)) ?: return null
        val metadata = runCatching { json.decodeFromString(GradleModuleMetadata.serializer(), text) }
            .getOrElse { e ->
                log.w(e) { "Unparseable .module for $coordinate; falling back to filename heuristics" }
                return null
            }
        val sourcesVariants = metadata.variants.filter { it.isSourcesVariant }
        // KMP modules type their sources variants; keep common + jvm. A plain JVM library's
        // single sources variant carries no kotlin platform attribute — treat it as JVM.
        val wanted = sourcesVariants
            .filter { it.platformType == KmpTarget.COMMON || it.platformType == KmpTarget.JVM }
            .ifEmpty { sourcesVariants.filter { it.platformType == KmpTarget.UNKNOWN } }
        val plans = mutableListOf<JarPlan>()
        for (variant in wanted) {
            val target = if (variant.platformType == KmpTarget.COMMON) KmpTarget.COMMON else KmpTarget.JVM
            when {
                variant.files.isNotEmpty() -> variant.files.mapTo(plans) { file ->
                    JarPlan(target, repo + Coordinates.artifactPath(coordinate, file.url), file.name, file.sha256)
                }
                variant.availableAt != null -> {
                    val redirect = variant.availableAt
                    val companion = LibraryCoordinate(redirect.group, redirect.module, redirect.version)
                    plans += planFromCompanionModule(repo, companion, variant.platformType, target)
                }
            }
        }
        return plans
    }

    /** Follows a single `available-at` hop into the per-target companion module. */
    private suspend fun planFromCompanionModule(
        repo: String,
        companion: LibraryCoordinate,
        platform: KmpTarget,
        target: KmpTarget,
    ): List<JarPlan> {
        val text = getTextOrNull(repo + moduleFilePath(companion)) ?: return emptyList()
        val metadata = runCatching { json.decodeFromString(GradleModuleMetadata.serializer(), text) }
            .getOrElse { e ->
                log.w(e) { "Unparseable companion .module for $companion" }
                return emptyList()
            }
        val variant = metadata.variants.firstOrNull {
            it.isSourcesVariant && (it.platformType == platform || it.platformType == KmpTarget.UNKNOWN)
        } ?: return emptyList()
        return variant.files.map { file ->
            JarPlan(target, repo + Coordinates.artifactPath(companion, file.url), file.name, file.sha256)
        }
    }

    private fun fallbackPlans(repo: String, coordinate: LibraryCoordinate): List<JarPlan> {
        val jvmCompanion = coordinate.copy(artifact = "${coordinate.artifact}-jvm")
        val jvmName = "${jvmCompanion.artifact}-${coordinate.version}-sources.jar"
        val plainName = "${coordinate.artifact}-${coordinate.version}-sources.jar"
        return listOf(
            JarPlan(KmpTarget.JVM, repo + Coordinates.artifactPath(jvmCompanion, jvmName), jvmName, sha256 = null),
            JarPlan(KmpTarget.JVM, repo + Coordinates.artifactPath(coordinate, plainName), plainName, sha256 = null),
        )
    }

    /** Downloads one jar into `jars/`, verifying sha256 when published. `null` when absent. */
    private suspend fun downloadJar(plan: JarPlan, versionDir: Path): Path? {
        val bytes = getBytesOrNull(plan.url) ?: return null
        verifySha256(bytes, plan.sha256, plan.url)
        return withContext(Dispatchers.IO) {
            val jarsDir = versionDir.resolve(CacheLayout.JARS_DIR).createDirectories()
            jarsDir.resolve(plan.fileName).also { it.writeBytes(bytes) }
        }
    }

    private fun verifySha256(bytes: ByteArray, expected: String?, url: String) {
        if (expected.isNullOrBlank()) {
            log.d { "No sha256 published for $url; skipping verification" }
            return
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ChecksumMismatchException("sha256 mismatch for $url: expected $expected, got $actual")
        }
    }

    // --- dependency tree internals ---

    private suspend fun resolveNode(
        coordinate: LibraryCoordinate,
        scope: String?,
        depth: Int,
        maxDepth: Int,
        repos: List<String>,
        visited: MutableSet<Pair<String, String>>,
    ): DependencyNode {
        if (depth >= maxDepth || coordinate.version == UNRESOLVED_VERSION) {
            return DependencyNode(coordinate, scope)
        }
        val dependencies = fetchDependencies(coordinate, repos)
            .filter { !it.optional && (it.scope == null || it.scope == "compile" || it.scope == "runtime") }
            .filter { visited.add(it.groupId to it.artifactId) } // claim before recursing: nearest wins
        val children = dependencies.map { dep ->
            resolveNode(
                coordinate = LibraryCoordinate(dep.groupId, dep.artifactId, dep.version ?: UNRESOLVED_VERSION),
                scope = dep.scope,
                depth = depth + 1,
                maxDepth = maxDepth,
                repos = repos,
                visited = visited,
            )
        }
        return DependencyNode(coordinate, scope, children = children)
    }

    /** Direct dependencies from `.pom`, falling back to `.module` library variants. */
    private suspend fun fetchDependencies(
        coordinate: LibraryCoordinate,
        repos: List<String>,
    ): List<PomDependency> {
        for (repo in repos) {
            val pomName = "${coordinate.artifact}-${coordinate.version}.pom"
            getTextOrNull(repo + Coordinates.artifactPath(coordinate, pomName))?.let { xml ->
                runCatching { PomParser.parse(xml) }
                    .onFailure { log.w(it) { "Unparseable .pom for $coordinate" } }
                    .getOrNull()
                    ?.let { return it.resolvedDependencies() }
            }
            getTextOrNull(repo + moduleFilePath(coordinate))?.let { text ->
                runCatching { json.decodeFromString(GradleModuleMetadata.serializer(), text) }
                    .getOrNull()
                    ?.let { metadata ->
                        return metadata.variants
                            .filter { it.isLibraryVariant }
                            .flatMap { it.dependencies }
                            .distinctBy { it.group to it.module }
                            .map { PomDependency(it.group, it.module, it.requestedVersion) }
                    }
            }
        }
        return emptyList()
    }

    // --- HTTP helpers ---

    private suspend fun getTextOrNull(url: String): String? {
        val response = client.get(url)
        return when {
            response.status.isSuccess() -> response.bodyAsText()
            response.status == HttpStatusCode.NotFound -> null
            else -> {
                log.w { "GET $url -> ${response.status}; treating as unavailable" }
                null
            }
        }
    }

    private suspend fun getBytesOrNull(url: String): ByteArray? {
        val response = client.get(url)
        return when {
            response.status.isSuccess() -> readCapped(response, url)
            response.status == HttpStatusCode.NotFound -> null
            else -> {
                log.w { "GET $url -> ${response.status}; treating as unavailable" }
                null
            }
        }
    }

    /**
     * Reads the body into memory with a [maxDownloadBytes] budget: rejects early on a too-large
     * declared `Content-Length`, then streams and aborts the moment the running total exceeds the
     * cap, so a huge (or `Content-Length`-lying) artifact can't OOM the JVM before extraction limits
     * apply.
     */
    private suspend fun readCapped(response: HttpResponse, url: String): ByteArray {
        response.contentLength()?.let { declared ->
            if (declared > maxDownloadBytes) {
                throw DownloadTooLargeException(
                    "$url declares $declared bytes, over the ${maxDownloadBytes}-byte download cap",
                )
            }
        }
        val channel = response.bodyAsChannel()
        return withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            var total = 0L
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(READ_CHUNK_BYTES)
                while (!packet.exhausted()) {
                    val chunk = packet.readByteArray()
                    total += chunk.size
                    if (total > maxDownloadBytes) {
                        throw DownloadTooLargeException(
                            "$url exceeds the ${maxDownloadBytes}-byte download cap",
                        )
                    }
                    out.write(chunk)
                }
            }
            out.toByteArray()
        }
    }

    private fun moduleFilePath(coordinate: LibraryCoordinate): String =
        Coordinates.artifactPath(coordinate, "${coordinate.artifact}-${coordinate.version}.module")

    private fun normalizeRepos(repos: List<String>): List<String> =
        repos.ifEmpty { listOf(MAVEN_CENTRAL) }.map { if (it.endsWith('/')) it else "$it/" }

    private fun isSourceFile(path: String): Boolean =
        path.endsWith(".kt") || path.endsWith(".kts") || path.endsWith(".java")

    private fun packageNameOf(relativePath: String): String =
        relativePath.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')

    public companion object {
        public const val MAVEN_CENTRAL: String = "https://repo1.maven.org/maven2/"

        /** Placeholder version for BOM-managed/uninterpolatable dependency versions. */
        public const val UNRESOLVED_VERSION: String = "unresolved"

        /** Default per-artifact download ceiling; roomy for real sources jars, guards against OOM. */
        public const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 200L * 1024 * 1024

        private const val READ_CHUNK_BYTES: Long = 64L * 1024
    }
}
