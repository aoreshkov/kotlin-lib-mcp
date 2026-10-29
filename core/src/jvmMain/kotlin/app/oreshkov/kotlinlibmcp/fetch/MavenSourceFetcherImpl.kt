@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.fetch

import app.oreshkov.kotlinlibmcp.cache.CacheLayout
import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.io.ZipExtractor
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
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readBuffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import kotlinx.io.readByteArray
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * A download exceeded a cap. Either one response body, declared or streamed, went past its own:
 * [MavenSourceFetcherImpl.maxDownloadBytes] for a jar, [MavenSourceFetcherImpl.maxMetadataBytes] for
 * `.module`, `.pom` or `maven-metadata.xml`. Or one fetch as a whole went past
 * [MavenSourceFetcherImpl.maxFetchBytes] across its jars, or listed more than
 * [MavenSourceFetcherImpl.maxJarsPerFetch] of them.
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
 * A fetch stages first and commits second. Every jar of the chosen repository is streamed to a
 * fresh staging directory ([CacheLayout.STAGING_DIR]), verified and extracted there before anything
 * touches the version directory; the commit then *replaces* that directory's sources and jars rather
 * than adding to them, under a lock on the version directory, because the cache root is shared by
 * every server on the machine. What one fetch may cost is bounded as a whole, not per jar: how many
 * jars ([maxJarsPerFetch]), how many bytes they download ([maxFetchBytes]) and how much they extract
 * (one [ZipExtractor.Budget] for all of them). A `.module` is publisher input, so per-jar bounds
 * alone would leave the totals to whoever publishes one. A repository that fails (see
 * [RepositoryUnavailableException]) is skipped for the next one, but if none has the file, that
 * failure is what the caller sees, and no marker is written, so the next call retries.
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
    private val maxFetchBytes: Long = DEFAULT_MAX_FETCH_BYTES,
    private val maxJarsPerFetch: Int = DEFAULT_MAX_JARS_PER_FETCH,
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
        return withStagingDir { staging ->
            val (repo, jars) = firstFromRepos(repoList) { repo ->
                downloadFromRepo(repo, coordinate, staging).takeIf { it.isNotEmpty() }?.let { repo to it }
            } ?: throw SourcesNotFoundException("No sources jar found for $coordinate in $repoList")
            val staged = extract(jars, staging)
            withVersionDirLock(versionDir) {
                // Another server sharing the cache root may have finished this fetch while we downloaded.
                readCachedResult(coordinate, versionDir) ?: commit(repo, coordinate, versionDir, staging, staged)
            }
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

    /** A sources jar downloaded to the staging directory and verified, not yet in the cache. */
    private class StagedJar(val target: KmpTarget, val fileName: String, val path: Path)

    /** What one repository attempt may still download, across all of its jars. */
    private class FetchBudget(var bytesLeft: Long)

    /** What a response body may grow to, and how to name that limit when it is exceeded. */
    private class Cap(val bytes: Long, val description: String)

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
            if (!versionDir.resolve(CacheLayout.SOURCES_DIR).isDirectory()) {
                log.w { "Fetch marker for $coordinate has no extracted sources beside it; refetching" }
                return@withContext null
            }
            resultOf(coordinate, versionDir, saved)
        }

    /** The [FetchResult] that [marker] describes, its paths resolved against [versionDir]. */
    private fun resultOf(coordinate: LibraryCoordinate, versionDir: Path, marker: FetchMarker): FetchResult {
        val jarsDir = versionDir.resolve(CacheLayout.JARS_DIR)
        return FetchResult(
            coordinate = coordinate,
            resolvedTargets = marker.resolvedTargets,
            downloadedJars = marker.jarFileNames.map { jarsDir.resolve(it).toString() },
            extractedDir = versionDir.resolve(CacheLayout.SOURCES_DIR).toString(),
            files = marker.files,
        )
    }

    /**
     * Runs [body] with a fresh directory of its own under [CacheLayout.STAGING_DIR], deleted
     * afterwards however [body] ends. Cancellation included, which is why the delete is
     * [NonCancellable]: a withdrawn fetch would otherwise leave its downloads on disk for good.
     *
     * A server that died mid-fetch never reaches that delete, so this also clears what such a fetch
     * left behind: any staging directory older than [STALE_STAGING_MILLIS]. Several servers share the
     * cache root, so age is what tells an abandoned directory from another server's fetch in progress.
     */
    private suspend fun <T> withStagingDir(body: suspend (Path) -> T): T {
        val stagingRoot = cacheDir.resolve(CacheLayout.STAGING_DIR)
        val staging = withContext(Dispatchers.IO) {
            sweepAbandonedStaging(stagingRoot)
            stagingRoot.resolve(UUID.randomUUID().toString()).createDirectories()
        }
        try {
            return body(staging)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { deleteQuietly(staging) }
        }
    }

    private fun sweepAbandonedStaging(stagingRoot: Path) {
        if (!stagingRoot.isDirectory()) return
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MILLIS
        try {
            stagingRoot.listDirectoryEntries()
                .filter { it.getLastModifiedTime().toMillis() < cutoff }
                .forEach(::deleteQuietly)
        } catch (e: IOException) {
            log.w(e) { "Could not sweep abandoned fetches under $stagingRoot" }
        }
    }

    /** Best effort: what cannot be deleted now is swept once it is [STALE_STAGING_MILLIS] old. */
    private fun deleteQuietly(dir: Path) {
        try {
            dir.deleteRecursively()
        } catch (e: IOException) {
            log.w(e) { "Could not delete $dir" }
        }
    }

    /**
     * Every sources jar [repo] has for [coordinate], streamed into [staging]'s `jars/` and
     * checksum-verified; empty when the repository publishes none.
     *
     * `jars/` is emptied first, so a jar a previous repository left behind before it failed is
     * never committed as this one's, and each repository gets the whole [maxFetchBytes].
     */
    private suspend fun downloadFromRepo(repo: String, coordinate: LibraryCoordinate, staging: Path): List<StagedJar> {
        val jarsDir = withContext(Dispatchers.IO) {
            staging.resolve(CacheLayout.JARS_DIR).also {
                it.deleteRecursively()
                it.createDirectories()
            }
        }
        val budget = FetchBudget(maxFetchBytes)
        val downloaded = mutableListOf<StagedJar>()
        val modulePlans = planViaModuleMetadata(repo, coordinate)?.let { bounded(it, coordinate) }
        if (modulePlans != null) {
            for (plan in modulePlans) {
                downloadJar(plan, jarsDir, budget)?.let { downloaded += it }
            }
        }
        if (downloaded.isEmpty()) {
            if (modulePlans != null) log.i { "Module metadata for $coordinate yielded no sources; falling back to filename heuristics" }
            for (plan in fallbackPlans(repo, coordinate)) {
                downloaded += downloadJar(plan, jarsDir, budget) ?: continue
                break // fallback ladder: first hit wins
            }
        }
        return downloaded
    }

    /**
     * [plans] without repeats, refused outright when more than [maxJarsPerFetch] remain, before a
     * single jar is requested. `.module` metadata is publisher input, and nothing in the format bounds
     * how many files a variant lists. Without this, one coordinate could have every fetch download
     * thousands of jars, or one jar thousands of times; a checksum is no help, since the same file
     * declares it. Real KMP modules list one or two once the variants are narrowed to common and JVM.
     */
    private fun bounded(plans: List<JarPlan>, coordinate: LibraryCoordinate): List<JarPlan> {
        // By name as well as by URL: a jar is saved under its name, so a second plan with the same
        // name would overwrite the first.
        val distinct = plans.distinctBy { it.url }.distinctBy { it.fileName }
        if (distinct.size > maxJarsPerFetch) {
            throw DownloadTooLargeException(
                "Module metadata for $coordinate lists ${distinct.size} sources jars, over the $maxJarsPerFetch-jar cap on one fetch",
            )
        }
        return distinct
    }

    /**
     * Extracts every staged jar into [staging]'s `sources/<target>`, all of them charged to one
     * [ZipExtractor.Budget]: the extractor's limits then hold for the fetch as a whole, however many
     * jars it took. Returns the marker that describes the result.
     */
    private suspend fun extract(jars: List<StagedJar>, staging: Path): FetchMarker {
        val sourcesRoot = staging.resolve(CacheLayout.SOURCES_DIR)
        val budget = extractor.newBudget()
        val targets = sortedSetOf<KmpTarget>()
        val files = mutableListOf<SourceFileRef>()
        for (jar in jars) {
            val targetName = jar.target.name.lowercase()
            val relativePaths = extractor.extract(jar.path, sourcesRoot.resolve(targetName), budget)
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
        return FetchMarker(targets.toList(), jars.map { it.fileName }, files)
    }

    /**
     * Moves a staged fetch into [versionDir] and records it in the marker. Runs under the
     * version-directory lock.
     *
     * It **replaces** `sources/` and `jars/`, never adds to them. The analyzer indexes everything
     * under `sources/`, so whatever an earlier fetch left there would be indexed as part of this
     * one: the files of a fetch that was cancelled or failed partway, or of a different variant
     * choice (another repository's, or an older server's, such as Android sources a JVM variant has
     * since displaced).
     *
     * The marker is the commit point. It is deleted first and written last, so a crash or failure
     * anywhere in between leaves a miss, which the next fetch redoes from scratch, never a mix that
     * looks complete. Both moves are renames, since staging is on the cache root's filesystem.
     */
    private suspend fun commit(
        repo: String,
        coordinate: LibraryCoordinate,
        versionDir: Path,
        staging: Path,
        marker: FetchMarker,
    ): FetchResult {
        withContext(Dispatchers.IO) {
            val markerFile = versionDir.resolve(CacheLayout.FETCH_RESULT_FILE)
            markerFile.deleteIfExists()
            for (name in listOf(CacheLayout.SOURCES_DIR, CacheLayout.JARS_DIR)) {
                val live = versionDir.resolve(name)
                live.deleteRecursively()
                staging.resolve(name).moveTo(live, StandardCopyOption.ATOMIC_MOVE)
            }
            markerFile.writeTextAtomically(json.encodeToString(FetchMarker.serializer(), marker))
            // The pre-v2 marker would never be read again; it only misleads someone browsing the cache.
            versionDir.resolve(CacheLayout.LEGACY_FETCH_RESULT_FILE).deleteIfExists()
        }
        log.i { "Fetched $coordinate from $repo: ${marker.jarFileNames.size} jar(s), ${marker.files.size} source files" }
        return resultOf(coordinate, versionDir, marker)
    }

    /**
     * Runs [body] holding an exclusive lock on [versionDir], so two servers sharing the cache root
     * never commit the same coordinate at once. Taking it creates the directory, which is why [fetch]
     * takes it only once there is something to commit: a coordinate that does not exist leaves no
     * version directory behind in the cache.
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

    /**
     * Streams one jar into [jarsDir], hashing it on the way, and verifies its sha256 when one is
     * published. `null` when absent. The jar is never held in memory, so memory use does not grow
     * with the size or number of jars. A jar that fails its cap or its checksum stays in the staging
     * directory, which is deleted with everything else in it.
     */
    private suspend fun downloadJar(plan: JarPlan, jarsDir: Path, budget: FetchBudget): StagedJar? {
        // safeFileName already ran when planning; checking the destination too means a plan built
        // any other way still cannot leave jars/.
        val dest = jarsDir.resolve(safeFileName(plan.fileName)).normalize()
        if (dest.parent != jarsDir.normalize()) {
            throw UnsafeArtifactNameException("Refusing to save '${plan.fileName}' outside the jars directory")
        }
        val cap = if (budget.bytesLeft < maxDownloadBytes) {
            Cap(budget.bytesLeft, "the $maxFetchBytes-byte cap on one fetch's jars")
        } else {
            Cap(maxDownloadBytes, "the $maxDownloadBytes-byte download cap")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val size = getOrNull(plan.url) { response ->
            withContext(Dispatchers.IO) {
                dest.outputStream().use { out ->
                    readCapped(response, plan.url, cap) { chunk ->
                        digest.update(chunk)
                        out.write(chunk)
                    }
                }
            }
        } ?: return null
        budget.bytesLeft -= size
        verifySha256(digest, plan.sha256, plan.url)
        return StagedJar(plan.target, dest.name, dest)
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

    /** Checks what [digest] has hashed against [expected], when the metadata publishes one. */
    private fun verifySha256(digest: MessageDigest, expected: String?, url: String) {
        if (expected.isNullOrBlank()) {
            log.d { "No sha256 published for $url; skipping verification" }
            return
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
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

    /** A `.module`, `.pom` or `maven-metadata.xml` body, read into memory under [maxMetadataBytes]. */
    private suspend fun getTextOrNull(url: String): String? =
        getOrNull(url) { response ->
            val out = ByteArrayOutputStream()
            readCapped(response, url, Cap(maxMetadataBytes, "the $maxMetadataBytes-byte download cap"), out::write)
            out.toString(Charsets.UTF_8)
        }

    /**
     * [read] applied to the response for [url], or `null` when the repository does not have it
     * (404). Any other failure status, once [HttpRequestRetry] has given up, throws
     * [RepositoryUnavailableException]: it says nothing about whether the file exists.
     *
     * `prepareGet(...).execute { }`, not `get(...)`: a plain `get` returns only after Ktor has saved
     * the whole body in memory, so a cap counted on that response protects nothing. Inside `execute`
     * the body is still the network stream.
     */
    private suspend fun <T : Any> getOrNull(url: String, read: suspend (HttpResponse) -> T): T? =
        client.prepareGet(url).execute { response ->
            when {
                response.status.isSuccess() -> read(response)
                response.status == HttpStatusCode.NotFound -> null
                else -> throw RepositoryUnavailableException(response.status.value, url)
            }
        }

    /**
     * Streams the body to [sink] one chunk at a time under [cap], returning the bytes read. It
     * rejects a too-large declared `Content-Length` up front. After that it aborts the moment the
     * running total passes the cap, before that chunk reaches [sink], so a huge or
     * `Content-Length`-lying body is never held whole. Only one [READ_CHUNK_BYTES] chunk is in
     * memory at a time, whatever the body's size.
     *
     * The loop is Ktor 3's streaming read, `exhausted()` then a bounded read. `readBuffer(Long)`
     * rather than the `readRemaining(Long)` the client docs still show: Ktor 3.6 deprecates the latter
     * in favour of the former.
     */
    private suspend fun readCapped(response: HttpResponse, url: String, cap: Cap, sink: (ByteArray) -> Unit): Long {
        response.contentLength()?.let { declared ->
            if (declared > cap.bytes) {
                throw DownloadTooLargeException("$url declares $declared bytes, over ${cap.description}")
            }
        }
        val channel = response.bodyAsChannel()
        return withContext(Dispatchers.IO) {
            var total = 0L
            while (!channel.exhausted()) {
                val chunk = channel.readBuffer(READ_CHUNK_BYTES).readByteArray()
                total += chunk.size
                if (total > cap.bytes) {
                    throw DownloadTooLargeException("$url exceeds ${cap.description}")
                }
                sink(chunk)
            }
            total
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
         * Default ceiling on what one fetch downloads across all its jars, which is also roughly
         * the disk a fetch in progress may take. Twice [DEFAULT_MAX_DOWNLOAD_BYTES], so a common jar
         * and a JVM jar can each be as large as any one jar may be.
         */
        public const val DEFAULT_MAX_FETCH_BYTES: Long = 2 * DEFAULT_MAX_DOWNLOAD_BYTES

        /**
         * Default ceiling on the distinct sources jars one `.module` may have a fetch download. A
         * real KMP module lists one or two once narrowed to common and JVM, so this leaves room for
         * an unusual layout without letting metadata choose an unbounded number.
         */
        public const val DEFAULT_MAX_JARS_PER_FETCH: Int = 8

        /**
         * Default ceiling for a `.module`, `.pom` or `maven-metadata.xml` body. Real ones are at most
         * a few hundred KB; this only stops a hostile repository streaming one until the timeout.
         */
        public const val DEFAULT_MAX_METADATA_BYTES: Long = 10L * 1024 * 1024

        private const val READ_CHUNK_BYTES: Long = 64L * 1024

        /** How often a fetch waiting on another's version-directory lock checks again. */
        private const val LOCK_POLL_MILLIS: Long = 100

        /**
         * Age past which a staging directory is taken to be abandoned by a server that died
         * mid-fetch. Far beyond any live fetch, which the request timeout bounds to minutes per jar.
         */
        private const val STALE_STAGING_MILLIS: Long = 24L * 60 * 60 * 1000

        /** Maven's coordinate charset, as in [LibraryCoordinate.requireValidSegment]: no separators. */
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._+-]+")
    }
}
