package app.oreshkov.kotlinlibmcp.fetch

import app.oreshkov.kotlinlibmcp.cache.CacheLayout
import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.io.ZipExtractor
import app.oreshkov.kotlinlibmcp.io.writeAtomically
import app.oreshkov.kotlinlibmcp.io.writeTextAtomically
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.util.Coordinates
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.JvmDnsResolver
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlinx.io.readByteArray
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Base type for fetch failures surfaced to callers. */
public open class FetchException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** No sources jar could be resolved for the coordinate in any configured repository. */
public class SourcesNotFoundException(message: String) : FetchException(message)

/** A downloaded jar did not match the sha256 published in its Gradle module metadata. */
public class ChecksumMismatchException(message: String) : FetchException(message)

/**
 * A response body exceeded its cap, declared or streamed: [MavenSourceFetcherImpl.maxDownloadBytes]
 * for a jar, [MavenSourceFetcherImpl.maxMetadataBytes] for `.module`, `.pom` or `maven-metadata.xml`.
 */
public class DownloadTooLargeException(message: String) : FetchException(message)

/**
 * Gradle module metadata named a file that is not a plain file name, so saving it under `jars/`
 * would land somewhere else. The name comes from the repository, not from the caller.
 */
public class UnsafeArtifactNameException(message: String) : FetchException(message)

/**
 * A repository answered with something other than the file or a 404 — a 5xx that outlasted the
 * retries, a 429, a proxy's 403 — so nothing can be concluded about whether the file exists.
 * Only a 404 means "not published there"; reading anything else as absent would report a
 * repository outage as "no sources", or cache a fetch with a target missing.
 */
public class RepositoryUnavailableException(public val status: Int, public val url: String) :
    FetchException("Repository returned HTTP $status for $url; try again later")

/**
 * [MavenSourceFetcher] backed by a Ktor client.
 *
 * Resolution order per repository: Gradle `.module` metadata (variants matched by attributes
 * `org.gradle.category=documentation` + `org.gradle.docstype=sources`, one `available-at` hop
 * followed to the per-target companion module), then filename heuristics
 * (`-jvm-<v>-sources.jar`, `<v>-sources.jar`). Downloads land directly in the [CacheLayout]
 * cache dir; a [CacheLayout.FETCH_RESULT_FILE] marker makes warm fetches network-free.
 *
 * A fetch downloads first and writes second: every jar of the chosen repository is in memory and
 * verified before anything touches the cache, and the writes run under a lock on the version
 * directory, because the cache root is shared by every server on the machine. A repository that
 * fails (see [RepositoryUnavailableException]) is skipped for the next one, but if none has the
 * file, that failure is what the caller sees, and no marker is written, so the next call retries.
 *
 * The [engine] is injectable so tests drive the fetcher with Ktor's `MockEngine`.
 */
public class MavenSourceFetcherImpl(
    private val cacheDir: Path,
    // `dnsResolver` belongs on the engine, not in an `HttpClient(engine) { engine { … } }` block:
    // that block is typed to the engine, which is injectable here. CIO's default resolver is the
    // blocking, *uninterruptible* platform name service, so a hung lookup outlives the
    // `connectTimeoutMillis` below; `JvmDnsResolver` runs the same OS lookup (so /etc/hosts,
    // search domains and corporate DNS keep working — unlike `CioDnsResolver`) under
    // `runInterruptible`, which makes the timeout enforceable. Needs Ktor >= 3.6.0.
    engine: HttpClientEngine = CIO.create { dnsResolver = JvmDnsResolver() },
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val extractor: ZipExtractor = ZipExtractor(),
    private val maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    private val maxMetadataBytes: Long = DEFAULT_MAX_METADATA_BYTES,
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
        readCachedResult(coordinate, versionDir)?.let {
            log.d { "Cache hit for $coordinate" }
            return it
        }
        val repoList = normalizeRepos(repos)
        val (repo, jars) = firstFromRepos(repoList) { repo ->
            downloadFromRepo(repo, coordinate).takeIf { it.isNotEmpty() }?.let { repo to it }
        } ?: throw SourcesNotFoundException("No sources jar found for $coordinate in $repoList")
        return withVersionDirLock(versionDir) {
            // Another server sharing the cache root may have finished this fetch while we downloaded.
            readCachedResult(coordinate, versionDir) ?: persist(repo, coordinate, versionDir, jars)
        }
    }

    override suspend fun fetchVersionCatalog(group: String, artifact: String, repos: List<String>): VersionCatalog {
        val versions = LinkedHashSet<String>()
        var release: String? = null
        var latest: String? = null
        var failure: RepositoryUnavailableException? = null
        for (repo in normalizeRepos(repos)) {
            val xml = try {
                getTextOrNull(repo + Coordinates.metadataPath(group, artifact))
            } catch (e: RepositoryUnavailableException) {
                log.w { e.message.orEmpty() }
                failure = failure ?: e
                null
            } ?: continue
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
        // Versions from the repositories that answered are still the right answer; an empty
        // catalog because the only repository that has the artifact was down is not.
        if (versions.isEmpty()) failure?.let { throw it }
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

    /** A sources jar downloaded and verified, not yet written anywhere. */
    private class DownloadedJar(val target: KmpTarget, val fileName: String, val bytes: ByteArray)

    /**
     * What [CacheLayout.FETCH_RESULT_FILE] holds: a [FetchResult] without the paths, which are
     * rebuilt from the current [cacheDir] on every hit. The first marker format stored them
     * absolute, so a cache that moved kept pointing at the old root, and nothing ever refetched.
     */
    @Serializable
    private data class FetchMarker(
        val resolvedTargets: List<KmpTarget>,
        val jarFileNames: List<String>,
        val files: List<SourceFileRef>,
    )

    /** The marker's result, or `null` when there is none, it is unreadable, or the sources are gone. */
    private suspend fun readCachedResult(coordinate: LibraryCoordinate, versionDir: Path): FetchResult? =
        withContext(Dispatchers.IO) {
            val marker = versionDir.resolve(CacheLayout.FETCH_RESULT_FILE)
            if (!marker.exists()) return@withContext null
            val saved = runCatching { json.decodeFromString(FetchMarker.serializer(), marker.readText()) }
                .onFailure { log.w(it) { "Corrupt fetch marker $marker; refetching" } }
                .getOrNull() ?: return@withContext null
            val sourcesRoot = versionDir.resolve(CacheLayout.SOURCES_DIR)
            if (!sourcesRoot.isDirectory()) {
                log.w { "Fetch marker for $coordinate has no extracted sources beside it; refetching" }
                return@withContext null
            }
            val jarsDir = versionDir.resolve(CacheLayout.JARS_DIR)
            FetchResult(
                coordinate = coordinate,
                resolvedTargets = saved.resolvedTargets,
                downloadedJars = saved.jarFileNames.map { jarsDir.resolve(it).toString() },
                extractedDir = sourcesRoot.toString(),
                files = saved.files,
            )
        }

    /**
     * Every sources jar [repo] has for [coordinate], in memory and checksum-verified; empty when
     * the repository publishes none.
     */
    private suspend fun downloadFromRepo(repo: String, coordinate: LibraryCoordinate): List<DownloadedJar> {
        val downloaded = mutableListOf<DownloadedJar>()
        val modulePlans = planViaModuleMetadata(repo, coordinate)
        if (modulePlans != null) {
            for (plan in modulePlans) {
                downloadJar(plan)?.let { downloaded += it }
            }
        }
        if (downloaded.isEmpty()) {
            if (modulePlans != null) log.i { "Module metadata for $coordinate yielded no sources; falling back to filename heuristics" }
            for (plan in fallbackPlans(repo, coordinate)) {
                downloaded += downloadJar(plan) ?: continue
                break // fallback ladder: first hit wins
            }
        }
        return downloaded
    }

    /** Writes [jars] into the cache, extracts them, and records the result in the marker. */
    private suspend fun persist(
        repo: String,
        coordinate: LibraryCoordinate,
        versionDir: Path,
        jars: List<DownloadedJar>,
    ): FetchResult {
        val jarsDir = versionDir.resolve(CacheLayout.JARS_DIR).normalize()
        val sourcesRoot = versionDir.resolve(CacheLayout.SOURCES_DIR)
        val targets = sortedSetOf<KmpTarget>()
        val files = mutableListOf<SourceFileRef>()
        val jarPaths = mutableListOf<Path>()
        for (jar in jars) {
            // safeFileName already ran when planning; checking the destination too means a plan
            // built any other way still cannot leave jars/.
            val dest = jarsDir.resolve(safeFileName(jar.fileName)).normalize()
            if (dest.parent != jarsDir) {
                throw UnsafeArtifactNameException("Refusing to save '${jar.fileName}' outside the jars directory")
            }
            withContext(Dispatchers.IO) { dest.writeAtomically(jar.bytes) }
            jarPaths.add(dest)
            val targetName = jar.target.name.lowercase()
            val relativePaths = extractor.extract(dest, sourcesRoot.resolve(targetName))
            targets += jar.target
            for (relative in relativePaths) {
                if (!isSourceFile(relative)) continue
                files += SourceFileRef(
                    path = "$targetName/$relative",
                    packageName = packageNameOf(relative),
                    target = jar.target,
                )
            }
        }
        val marker = FetchMarker(targets.toList(), jarPaths.map { it.name }, files)
        withContext(Dispatchers.IO) {
            versionDir.resolve(CacheLayout.FETCH_RESULT_FILE)
                .writeTextAtomically(json.encodeToString(FetchMarker.serializer(), marker))
            // The pre-v2 marker would never be read again; it only misleads someone browsing the cache.
            versionDir.resolve(CacheLayout.LEGACY_FETCH_RESULT_FILE).deleteIfExists()
        }
        log.i { "Fetched $coordinate from $repo: ${jars.size} jar(s), ${files.size} source files" }
        return FetchResult(
            coordinate = coordinate,
            resolvedTargets = marker.resolvedTargets,
            downloadedJars = jarPaths.map { it.toString() },
            extractedDir = sourcesRoot.toString(),
            files = files,
        )
    }

    /**
     * Runs [body] holding an exclusive lock on [versionDir], so two servers sharing the cache root
     * never write or extract the same coordinate at once. Taking it creates the directory, which is
     * why [fetch] takes it only once there is something to write: a coordinate that does not exist
     * leaves nothing behind in the cache.
     *
     * Polls with [FileChannel.tryLock] instead of blocking in `lock()`: a blocked `lock()` cannot be
     * cancelled, and it throws [OverlappingFileLockException] when another coroutine of this same
     * JVM holds the lock (a `get_source` racing a `fetch_library` of a cold coordinate), which
     * polling treats like any other holder. A crashed holder's lock is released by the OS.
     */
    private suspend fun <T> withVersionDirLock(versionDir: Path, body: suspend () -> T): T {
        val channel = withContext(Dispatchers.IO) {
            versionDir.createDirectories()
            FileChannel.open(versionDir.resolve(CacheLayout.FETCH_LOCK_FILE), CREATE, WRITE)
        }
        channel.use {
            while (tryLock(channel) == null) delay(LOCK_POLL_MILLIS)
            return body() // closing the channel releases the lock
        }
    }

    private fun tryLock(channel: FileChannel): FileLock? =
        try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }

    /**
     * Runs [attempt] against each repository in turn and returns the first non-null answer. A
     * repository that fails is skipped rather than ending the search, but it is not evidence of
     * absence: when no repository answers, its failure is thrown instead of returning `null`, so
     * "the repository was down" never reaches the caller as "there is no such artifact".
     */
    private suspend fun <T : Any> firstFromRepos(repos: List<String>, attempt: suspend (String) -> T?): T? {
        var failure: RepositoryUnavailableException? = null
        for (repo in repos) {
            try {
                attempt(repo)?.let { return it }
            } catch (e: RepositoryUnavailableException) {
                log.w { "${e.message}; trying the next repository" }
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
        return null
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
        val typed = sourcesVariants.filter { it.platformType == KmpTarget.COMMON || it.platformType == KmpTarget.JVM }
        // Android sources stand in for JVM only when there are no real JVM ones: both would land in
        // sources/jvm, the Android actuals labelled JVM beside the real ones.
        val hasJvm = typed.any { it.platformType == KmpTarget.JVM && !it.isAndroidJvm }
        val wanted = typed
            .filterNot { hasJvm && it.isAndroidJvm }
            .ifEmpty { sourcesVariants.filter { it.platformType == KmpTarget.UNKNOWN } }
        val plans = mutableListOf<JarPlan>()
        for (variant in wanted) {
            val target = if (variant.platformType == KmpTarget.COMMON) KmpTarget.COMMON else KmpTarget.JVM
            when {
                variant.files.isNotEmpty() -> variant.files.mapTo(plans) { file ->
                    val url = repo + Coordinates.artifactPath(coordinate, file.url)
                    JarPlan(target, url, safeFileName(file.name), file.sha256)
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
            JarPlan(target, repo + Coordinates.artifactPath(companion, file.url), safeFileName(file.name), file.sha256)
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

    /** Downloads one jar into memory, verifying sha256 when published. `null` when absent. */
    private suspend fun downloadJar(plan: JarPlan): DownloadedJar? {
        val bytes = getBytesOrNull(plan.url) ?: return null
        verifySha256(bytes, plan.sha256, plan.url)
        return DownloadedJar(plan.target, plan.fileName, bytes)
    }

    /**
     * [name] when it is a plain file name in Maven's own charset. `.module` metadata is publisher
     * input: a `files[].name` of `../../x` or an absolute path would otherwise choose where the jar
     * is written, and the sha256 beside it is no help, because the same file declares it.
     */
    private fun safeFileName(name: String): String {
        if (name == "." || name == ".." || !SAFE_FILE_NAME.matches(name)) {
            throw UnsafeArtifactNameException("Module metadata names an unsafe artifact file: '$name'")
        }
        return name
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
    ): List<PomDependency> =
        firstFromRepos(repos) { repo ->
            val pomName = "${coordinate.artifact}-${coordinate.version}.pom"
            getTextOrNull(repo + Coordinates.artifactPath(coordinate, pomName))?.let { xml ->
                runCatching { PomParser.parse(xml) }
                    .onFailure { log.w(it) { "Unparseable .pom for $coordinate" } }
                    .getOrNull()
                    ?.let { return@firstFromRepos it.resolvedDependencies() }
            }
            getTextOrNull(repo + moduleFilePath(coordinate))?.let { text ->
                runCatching { json.decodeFromString(GradleModuleMetadata.serializer(), text) }
                    .getOrNull()
                    ?.let { metadata ->
                        return@firstFromRepos metadata.variants
                            .filter { it.isLibraryVariant }
                            .flatMap { it.dependencies }
                            .distinctBy { it.group to it.module }
                            .map { PomDependency(it.group, it.module, it.requestedVersion) }
                    }
            }
            null
        } ?: emptyList()

    // --- HTTP helpers ---

    /** A `.module`, `.pom` or `maven-metadata.xml` body, read under [maxMetadataBytes]. */
    private suspend fun getTextOrNull(url: String): String? =
        getCappedOrNull(url, maxMetadataBytes)?.decodeToString()

    /** A jar, read under [maxDownloadBytes]. */
    private suspend fun getBytesOrNull(url: String): ByteArray? = getCappedOrNull(url, maxDownloadBytes)

    /**
     * The body of [url] read under [cap], or `null` when the repository does not have it (404).
     * Any other failure status, once [HttpRequestRetry] has given up, throws
     * [RepositoryUnavailableException]: it says nothing about whether the file exists.
     *
     * `prepareGet(...).execute { }`, not `get(...)`: a plain `get` returns only after Ktor has saved
     * the whole body in memory, so a cap counted on that response protects nothing. Inside `execute`
     * the body is still the network stream.
     */
    private suspend fun getCappedOrNull(url: String, cap: Long): ByteArray? =
        client.prepareGet(url).execute { response ->
            when {
                response.status.isSuccess() -> readCapped(response, url, cap)
                response.status == HttpStatusCode.NotFound -> null
                else -> throw RepositoryUnavailableException(response.status.value, url)
            }
        }

    /**
     * Reads the streaming body into memory with a [cap] budget: rejects early on a too-large
     * declared `Content-Length`, then aborts the moment the running total exceeds the cap, so a huge
     * (or `Content-Length`-lying) body can't OOM the JVM before extraction limits apply.
     */
    private suspend fun readCapped(response: HttpResponse, url: String, cap: Long): ByteArray {
        response.contentLength()?.let { declared ->
            if (declared > cap) {
                throw DownloadTooLargeException("$url declares $declared bytes, over the $cap-byte download cap")
            }
        }
        val channel = response.bodyAsChannel()
        return withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            var total = 0L
            while (!channel.isClosedForRead) {
                val buffer = channel.readBuffer(READ_CHUNK_BYTES)
                while (!buffer.exhausted()) {
                    val chunk = buffer.readByteArray()
                    total += chunk.size
                    if (total > cap) {
                        throw DownloadTooLargeException("$url exceeds the $cap-byte download cap")
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

        /**
         * Default ceiling for a `.module`, `.pom` or `maven-metadata.xml` body. Real ones are at most
         * a few hundred KB; this only stops a hostile repository streaming one until the timeout.
         */
        public const val DEFAULT_MAX_METADATA_BYTES: Long = 10L * 1024 * 1024

        private const val READ_CHUNK_BYTES: Long = 64L * 1024

        /** How often a fetch waiting on another's version-directory lock checks again. */
        private const val LOCK_POLL_MILLIS: Long = 100

        /** Maven's coordinate charset, as in [LibraryCoordinate.requireValidSegment]: no separators. */
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._+-]+")
    }
}
