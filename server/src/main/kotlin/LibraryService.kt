package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.SourceAnalyzer
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.dto.DeclarationList
import app.oreshkov.kotlinlibmcp.dto.DependencyResult
import app.oreshkov.kotlinlibmcp.dto.FetchSummary
import app.oreshkov.kotlinlibmcp.dto.KDocResult
import app.oreshkov.kotlinlibmcp.dto.LatestVersion
import app.oreshkov.kotlinlibmcp.dto.PackageList
import app.oreshkov.kotlinlibmcp.dto.SearchHit
import app.oreshkov.kotlinlibmcp.dto.SearchResults
import app.oreshkov.kotlinlibmcp.dto.SignatureResult
import app.oreshkov.kotlinlibmcp.dto.SourceResult
import app.oreshkov.kotlinlibmcp.dto.VersionList
import app.oreshkov.kotlinlibmcp.model.ApiSymbol
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.Visibility
import app.oreshkov.kotlinlibmcp.util.MavenVersions
import co.touchlab.kermit.Logger
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thrown by read operations when a coordinate has no cached index yet. */
class LibraryNotFetchedException(coordinate: LibraryCoordinate) : Exception(
    "Library $coordinate is not fetched yet. Call fetch_library with coordinate \"$coordinate\" first."
)

/** A coarse [LibraryService.fetchLibrary] phase: [step] of [totalSteps], human-readable [message]. */
data class FetchProgress(val step: Int, val totalSteps: Int, val message: String)

/**
 * Orchestrates fetch → analyze → cache and exposes the read operations the MCP tools call, so the
 * tool files stay declarative adapters. Every read goes through the cached [LibraryIndex]; raw
 * source reads resolve paths via the (idempotent, cache-marker-backed) fetch result.
 */
class LibraryService(
    private val fetcher: MavenSourceFetcher,
    private val analyzer: SourceAnalyzer,
    private val cache: LibraryCache,
    private val repos: List<String> = emptyList(),
) {
    private val log = Logger.withTag("LibraryService")

    /**
     * Warms the cache for [coordinate]: download sources, analyze, persist the index. Idempotent.
     * [onProgress] is invoked at each phase boundary (never on a warm cache hit).
     */
    suspend fun fetchLibrary(
        coordinate: LibraryCoordinate,
        onProgress: suspend (FetchProgress) -> Unit = {},
    ): FetchSummary {
        cache.get(coordinate)?.let { return it.summary(fromCache = true) }
        log.i { "Fetching and analyzing $coordinate" }
        onProgress(FetchProgress(1, FETCH_STEPS, "Downloading and extracting sources of $coordinate"))
        val fetched = fetcher.fetch(coordinate, repos)
        onProgress(FetchProgress(2, FETCH_STEPS, "Analyzing sources of $coordinate"))
        // The Analysis API session is CPU-bound; keep it off the caller's dispatcher.
        val index = withContext(Dispatchers.Default) {
            analyzer.analyze(coordinate, listOf(fetched.extractedDir), classpathRoots = emptyList())
        }
        onProgress(FetchProgress(3, FETCH_STEPS, "Caching the parsed index of $coordinate"))
        cache.putIndex(index)
        return index.summary(fromCache = false)
    }

    suspend fun listPackages(coordinate: LibraryCoordinate): PackageList =
        PackageList(coordinate, index(coordinate).packages)

    suspend fun listDeclarations(
        coordinate: LibraryCoordinate,
        packageName: String?,
        visibility: String?,
    ): DeclarationList {
        val wanted: Set<Visibility> = when (visibility?.lowercase() ?: "public") {
            "public" -> setOf(Visibility.PUBLIC)
            "internal" -> setOf(Visibility.INTERNAL)
            "all" -> Visibility.entries.toSet()
            else -> throw IllegalArgumentException("visibility must be one of: public, internal, all")
        }
        val declarations = index(coordinate).symbolsByFqName.values.filter { symbol ->
            symbol.visibility in wanted &&
                (packageName == null || symbol.sourceRef?.file?.packageName == packageName)
        }
        return DeclarationList(coordinate, packageName, declarations)
    }

    suspend fun getSignature(coordinate: LibraryCoordinate, fqName: String): SignatureResult =
        SignatureResult(symbol(coordinate, fqName))

    suspend fun getKDoc(coordinate: LibraryCoordinate, fqName: String): KDocResult =
        KDocResult(fqName, symbol(coordinate, fqName).kdoc)

    /** Raw source of a whole file (by index-relative [path]) or a single declaration (by [fqName]). */
    suspend fun getSource(coordinate: LibraryCoordinate, path: String?, fqName: String?): SourceResult {
        val index = index(coordinate)
        return when {
            path != null -> {
                val file = index.files.find { it.path == path }
                    ?: throw IllegalArgumentException("No source file '$path' in $coordinate (see fetch_library/list_packages)")
                SourceResult(path = file.path, content = readSource(coordinate, file.path))
            }
            fqName != null -> {
                val symbol = symbol(coordinate, fqName)
                val ref = symbol.sourceRef
                    ?: throw IllegalArgumentException("Declaration '$fqName' has no recorded source location")
                val text = readSource(coordinate, ref.file.path)
                val end = ref.endOffset?.coerceAtMost(text.length) ?: text.length
                SourceResult(
                    path = ref.file.path,
                    content = text.substring(ref.offset.coerceIn(0, end), end),
                    startLine = ref.line,
                )
            }
            else -> throw IllegalArgumentException("Provide either 'path' or 'fqName'")
        }
    }

    suspend fun searchSource(
        coordinate: LibraryCoordinate,
        query: String,
        regex: Boolean,
        maxResults: Int,
    ): SearchResults {
        val index = index(coordinate)
        val cap = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val pattern = if (regex) Regex(query) else null
        val hits = mutableListOf<SearchHit>()
        var truncated = false

        outer@ for (file in index.files) {
            val lines = readSource(coordinate, file.path).lineSequence()
            for ((lineIndex, line) in lines.withIndex()) {
                val matches = pattern?.containsMatchIn(line) ?: line.contains(query)
                if (!matches) continue
                if (hits.size == cap) {
                    truncated = true
                    break@outer
                }
                hits += SearchHit(path = file.path, line = lineIndex + 1, snippet = line.trim().take(200))
            }
        }
        return SearchResults(query = query, hits = hits, truncated = truncated)
    }

    suspend fun getDependencies(coordinate: LibraryCoordinate, depth: Int): DependencyResult =
        DependencyResult(fetcher.resolveDependencies(coordinate, repos, depth.coerceIn(1, MAX_DEPENDENCY_DEPTH)))

    suspend fun listVersions(group: String, artifact: String): VersionList =
        VersionList(group, artifact, fetcher.listVersions(group, artifact, repos))

    /**
     * Latest version(s) of an artifact from `maven-metadata.xml`. Prefers the canonical
     * `<release>`/`<latest>` tags, falling back to a semantic pick over the version list.
     * [includePreReleases] is echoed back so the caller knows whether to treat [LatestVersion.latest]
     * or [LatestVersion.latestStable] as "the latest".
     */
    suspend fun latestVersion(group: String, artifact: String, includePreReleases: Boolean): LatestVersion {
        val catalog = fetcher.fetchVersionCatalog(group, artifact, repos)
        if (catalog.versions.isEmpty()) {
            throw IllegalArgumentException(
                "No versions found for $group:$artifact. Check the coordinate and the configured repositories."
            )
        }
        return LatestVersion(
            group = group,
            artifact = artifact,
            latestStable = latestStableOf(catalog),
            latest = latestOverallOf(catalog),
            includedPreReleases = includePreReleases,
            totalVersions = catalog.versions.size,
        )
    }

    /**
     * Turns a possibly version-less or `latest` spec into a concrete coordinate. A concrete version
     * passes through unchanged; `latest` (or an absent version) resolves to the latest stable
     * release, falling back to the newest pre-release only when no stable release exists.
     */
    suspend fun resolveCoordinate(group: String, artifact: String, versionSpec: String?): LibraryCoordinate {
        if (versionSpec != null && !versionSpec.equals(LATEST, ignoreCase = true)) {
            return LibraryCoordinate(group, artifact, versionSpec)
        }
        val catalog = fetcher.fetchVersionCatalog(group, artifact, repos)
        val resolved = latestStableOf(catalog)
            ?: latestOverallOf(catalog)
            ?: throw IllegalArgumentException(
                "No versions found for $group:$artifact to resolve 'latest'. Check the coordinate and repositories."
            )
        log.i { "Resolved $group:$artifact:${versionSpec ?: "latest"} -> $resolved" }
        return LibraryCoordinate(group, artifact, resolved)
    }

    /**
     * Newest **stable** version. Prefers the semantic pick over the version list, but also honors the
     * repository `<release>` tag *only when it is itself stable* — some publishers (e.g. Kotlin) point
     * `<release>` at a Beta, so the tag can't be trusted blindly for "stable".
     */
    private fun latestStableOf(catalog: VersionCatalog): String? {
        val computed = MavenVersions.selectLatest(catalog.versions, includePreReleases = false)
        val releaseTag = catalog.release?.takeIf { MavenVersions.isStable(it) }
        return listOfNotNull(computed, releaseTag).maxWithOrNull(MavenVersions.VERSION_COMPARATOR)
    }

    /** Newest version overall (incl. pre-releases): the semantic pick, reconciled with the `<latest>` tag. */
    private fun latestOverallOf(catalog: VersionCatalog): String? {
        val computed = MavenVersions.selectLatest(catalog.versions, includePreReleases = true)
        return listOfNotNull(computed, catalog.latest).maxWithOrNull(MavenVersions.VERSION_COMPARATOR)
    }

    /** Every coordinate with a cached index (feeds the MCP resources and the dashboard). */
    suspend fun listCached(): List<LibraryCoordinate> = cache.list()

    suspend fun index(coordinate: LibraryCoordinate): LibraryIndex =
        cache.get(coordinate) ?: throw LibraryNotFetchedException(coordinate)

    // --- internals ---

    private suspend fun symbol(coordinate: LibraryCoordinate, fqName: String): ApiSymbol {
        val symbols = index(coordinate).symbolsByFqName
        // Exact key first; then the first overload (keys are disambiguated with a `#n` suffix).
        return symbols[fqName]
            ?: symbols.entries.firstOrNull { it.key.substringBefore('#') == fqName }?.value
            ?: throw IllegalArgumentException("No declaration '$fqName' in $coordinate (try list_declarations)")
    }

    /** Reads one extracted source file; the fetch result is cache-marker-backed, so this is warm. */
    private suspend fun readSource(coordinate: LibraryCoordinate, relativePath: String): String {
        val extractedDir = fetcher.fetch(coordinate, repos).extractedDir
        val file = Path.of(extractedDir).resolve(relativePath).normalize()
        require(file.startsWith(Path.of(extractedDir).normalize())) { "Path escapes the source root: $relativePath" }
        if (!file.exists()) throw IllegalArgumentException("Source file not found on disk: $relativePath")
        return withContext(Dispatchers.IO) { file.readText() }
    }

    private fun LibraryIndex.summary(fromCache: Boolean): FetchSummary = FetchSummary(
        coordinate = coordinate,
        resolvedTargets = targets,
        sourceFileCount = files.size,
        packageCount = packages.size,
        fromCache = fromCache,
    )

    private companion object {
        const val MAX_SEARCH_RESULTS = 200
        const val MAX_DEPENDENCY_DEPTH = 5
        const val LATEST = "latest"
        const val FETCH_STEPS = 3
    }
}
