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
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Thrown by read operations when a coordinate has no cached index yet. */
class LibraryNotFetchedException(coordinate: LibraryCoordinate) : Exception(
    "Library $coordinate is not fetched yet. Call fetch_library with coordinate \"$coordinate\" first."
)

/**
 * Raised when a `search_source` regex exhausts its per-line matching budget — in practice, when
 * it backtracks catastrophically. Surfaced by `guarded` as a tool error, phrased so the model can
 * fix the pattern itself.
 */
class SearchPatternTooExpensiveException(query: String) : Exception(
    "The regular expression '$query' is too expensive to evaluate on this source. Back-references " +
        "and nested quantifiers over long lines are the usual causes. Simplify it, or search for a " +
        "literal substring instead by omitting 'regex'."
)

/**
 * A read-only [CharSequence] view of [text] that aborts after [remaining] character reads.
 *
 * `java.util.regex` backtracks by re-reading characters, so a runaway pattern burns `charAt` calls
 * rather than blocking inside one. Counting them is the only guard that works here: matching never
 * suspends, so `withTimeout` cannot interrupt the loop — it is a tight, uncancellable CPU loop —
 * and capping the input length does not help either, because the blow-up is superlinear in it.
 *
 * Measured on JDK 21 rather than assumed, because the textbook picture is out of date. The classic
 * nested-quantifier examples are no longer exponential there: `(a+)+b` is roughly quadratic and
 * `(x+x+)+y` roughly quartic, so the latter needs a ~200-character line to reach this budget —
 * which is an unremarkable line length in real source. Back-references are the case that is still
 * genuinely exponential: `(a+)+\1b` exhausts the budget against 20 characters, in milliseconds.
 */
private class BoundedCharSequence(
    private val text: CharSequence,
    private val query: String,
    private var remaining: Int,
) : CharSequence {
    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (remaining-- <= 0) throw SearchPatternTooExpensiveException(query)
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BoundedCharSequence(text.subSequence(startIndex, endIndex), query, remaining)
}

/** A coarse [LibraryService.fetchLibrary] phase: [step] of [totalSteps], human-readable [message]. */
data class FetchProgress(val step: Int, val totalSteps: Int, val message: String)

/**
 * The versions worth offering a user for a version-less coordinate: [candidates] newest first, and
 * the [default] that would have been chosen silently (see [LibraryService.resolveCoordinate]).
 * [default] is always present in [candidates].
 */
data class VersionOptions(val candidates: List<String>, val default: String)

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
    /**
     * Whether `fetch_library` may report the on-disk source root (`FetchSummary.extractedDir`).
     *
     * True only for stdio, where the client launched this process and shares its filesystem, so the
     * path is both usable and already within the caller's reach. Over HTTP the caller may be on
     * another machine: the path would be useless to it and would disclose the server's layout for
     * no benefit, so it stays `null`.
     */
    private val exposeLocalPaths: Boolean = false,
) {
    private val log = Logger.withTag("LibraryService")

    /** One in-flight fetch of one coordinate at a time; see [withFetchGate]. */
    private class FetchGate {
        val mutex = Mutex()

        /** Callers holding or waiting on [mutex]; guarded by `ConcurrentHashMap.compute`. */
        var holders: Int = 0
    }

    private val fetchGates = ConcurrentHashMap<LibraryCoordinate, FetchGate>()

    /** Coordinates with a fetch in flight. Visible for tests, which assert the map is pruned. */
    internal val inFlightFetchCount: Int get() = fetchGates.size

    /**
     * Warms the cache for [coordinate]: download sources, analyze, persist the index. Idempotent.
     * [onProgress] is invoked at each phase boundary (never on a warm cache hit).
     *
     * Concurrent calls for the *same* coordinate do the work once: the second caller waits on
     * [withFetchGate] and then finds the index the first one cached, so it reports `fromCache`
     * (truthfully — by the time it returned, that is where the index came from) without repeating
     * the download or the Analysis API pass, which is the expensive half. Different coordinates
     * never wait on each other. See [withFetchGate] for why this is a gate rather than a shared
     * `Deferred` of the in-flight result.
     */
    suspend fun fetchLibrary(
        coordinate: LibraryCoordinate,
        onProgress: suspend (FetchProgress) -> Unit = {},
    ): FetchSummary {
        // Fast path: a warm cache never touches the gate map at all.
        cache.get(coordinate)?.let { return it.summary(fromCache = true) }
        return withFetchGate(coordinate) {
            // Re-check under the gate. Whoever held it before us may have cached exactly the index
            // we were about to compute; without this the gate would serialize the duplicates
            // instead of eliminating them.
            cache.get(coordinate)?.let { return@withFetchGate it.summary(fromCache = true) }
            fetchUncached(coordinate, onProgress)
        }
    }

    private suspend fun fetchUncached(
        coordinate: LibraryCoordinate,
        onProgress: suspend (FetchProgress) -> Unit,
    ): FetchSummary {
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
        maxResults: Int = DEFAULT_DECLARATION_RESULTS,
        offset: Int = 0,
    ): DeclarationList {
        val wanted: Set<Visibility> = when (visibility?.lowercase() ?: "public") {
            "public" -> setOf(Visibility.PUBLIC)
            "internal" -> setOf(Visibility.INTERNAL)
            "all" -> Visibility.entries.toSet()
            else -> throw IllegalArgumentException("visibility must be one of: public, internal, all")
        }
        val matching = index(coordinate).symbolsByFqName.values.filter { symbol ->
            symbol.visibility in wanted &&
                (packageName == null || symbol.sourceRef?.file?.packageName == packageName)
        }
        // Bounded page: a large library must not flood the model's context in one response.
        val cap = maxResults.coerceIn(1, MAX_DECLARATION_RESULTS)
        val start = offset.coerceAtLeast(0)
        val page = matching.drop(start).take(cap)
        return DeclarationList(
            coordinate = coordinate,
            packageName = packageName,
            declarations = page,
            totalCount = matching.size,
            truncated = start + page.size < matching.size,
        )
    }

    suspend fun getSignature(coordinate: LibraryCoordinate, fqName: String): SignatureResult =
        SignatureResult(symbol(coordinate, fqName))

    suspend fun getKDoc(coordinate: LibraryCoordinate, fqName: String): KDocResult =
        KDocResult(fqName, symbol(coordinate, fqName).kdoc)

    /**
     * Raw source of a whole file (by index-relative [path]) or a single declaration (by [fqName]),
     * as a bounded page of at most [maxLines] lines beginning at [startLine].
     *
     * **Why this is paged at all.** A result whose size is a function of the *library* rather than
     * the *arguments* will eventually be handed a library that blows the caller's context: the
     * whole-file branch used to return the file entire, and the sources this server fetches include
     * single generated files of 1.7 MB (`JsAstProtoBuf.java` in `kotlin-compiler-embeddable`) —
     * roughly six times the 272 KB overflow that the unpaged `list_declarations` produced before
     * paging was added to it. Every tool result is also emitted twice, as text *and* as
     * `structuredContent`, so the wire cost is double what the page itself measures.
     *
     * [startLine] is 1-based and absolute within the file, so it matches the `startLine` in the
     * result and the line numbers `search_source` reports. For a declaration it defaults to the
     * declaration's own first line rather than the file's.
     */
    suspend fun getSource(
        coordinate: LibraryCoordinate,
        path: String?,
        fqName: String?,
        maxLines: Int = DEFAULT_SOURCE_LINES,
        startLine: Int? = null,
    ): SourceResult {
        val index = index(coordinate)
        return when {
            path != null -> {
                val file = index.files.find { it.path == path }
                    ?: throw IllegalArgumentException("No source file '$path' in $coordinate (see fetch_library/list_packages)")
                page(file.path, readSource(coordinate, file.path), firstLine = 1, startLine, maxLines)
            }
            fqName != null -> {
                val symbol = symbol(coordinate, fqName)
                val ref = symbol.sourceRef
                    ?: throw IllegalArgumentException("Declaration '$fqName' has no recorded source location")
                val text = readSource(coordinate, ref.file.path)
                val end = ref.endOffset?.coerceAtMost(text.length) ?: text.length
                // The slice starts at the declaration, so its first line *is* `ref.line`.
                page(
                    path = ref.file.path,
                    text = text.substring(ref.offset.coerceIn(0, end), end),
                    firstLine = ref.line,
                    startLine = startLine,
                    maxLines = maxLines,
                )
            }
            else -> throw IllegalArgumentException("Provide either 'path' or 'fqName'")
        }
    }

    /**
     * Cuts [text] — whose first line is numbered [firstLine] — down to at most [maxLines] lines
     * from [startLine].
     *
     * [MAX_SOURCE_CHARS] is a second, independent bound. Lines are the right unit for source, but
     * a line cap alone is not a size cap: generated and minified sources reach thousands of
     * characters per line, so a page within the line budget can still be enormous. Hitting either
     * bound sets `truncated`.
     */
    private fun page(
        path: String,
        text: String,
        firstLine: Int,
        startLine: Int?,
        maxLines: Int,
    ): SourceResult {
        val lines = text.lines()
        val skip = ((startLine ?: firstLine) - firstLine).coerceIn(0, maxOf(lines.size - 1, 0))
        val taken = lines.drop(skip).take(maxLines.coerceIn(1, MAX_SOURCE_LINES))
        val content = taken.joinToString("\n")
        val clipped = content.take(MAX_SOURCE_CHARS)
        return SourceResult(
            path = path,
            content = clipped,
            startLine = firstLine + skip,
            totalLines = lines.size,
            truncated = skip + taken.size < lines.size || clipped.length < content.length,
        )
    }

    suspend fun searchSource(
        coordinate: LibraryCoordinate,
        query: String,
        regex: Boolean,
        maxResults: Int,
    ): SearchResults {
        val index = index(coordinate)
        val cap = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val pattern = if (regex) compileSearchPattern(query) else null
        val hits = mutableListOf<SearchHit>()
        var truncated = false
        // Resolved once for the whole scan, not once per file: every `fetch` re-reads and
        // re-deserializes the on-disk cache marker even on a warm hit, so resolving it inside
        // the loop cost one file read + one JSON parse per source file in the library.
        val root = sourceRoot(coordinate)

        outer@ for (file in index.files) {
            val lines = readSourceUnder(root, file.path).lineSequence()
            for ((lineIndex, line) in lines.withIndex()) {
                val matches = when (pattern) {
                    null -> line.contains(query)
                    else -> pattern.containsMatchIn(BoundedCharSequence(line, query, MATCH_BUDGET_PER_LINE))
                }
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
     * The choice [resolveCoordinate] would otherwise make silently, plus the alternatives worth
     * offering: the newest [limit] versions, newest first, with the default guaranteed present.
     *
     * Deliberately one catalog fetch — the same single request [resolveCoordinate] makes — so
     * asking the user costs no more network than not asking.
     */
    suspend fun versionOptions(group: String, artifact: String, limit: Int = DEFAULT_VERSION_OPTIONS): VersionOptions {
        val catalog = fetcher.fetchVersionCatalog(group, artifact, repos)
        val default = latestStableOf(catalog)
            ?: latestOverallOf(catalog)
            ?: throw IllegalArgumentException(
                "No versions found for $group:$artifact to resolve 'latest'. Check the coordinate and repositories."
            )
        val newest = catalog.versions
            .sortedWith(MavenVersions.VERSION_COMPARATOR.reversed())
            .take(limit.coerceAtLeast(1))
        // The default can fall outside the window when it came from the `<release>` tag rather than
        // the version list; offering a picker that omits the default would be indefensible.
        val candidates = if (default in newest) newest else listOf(default) + newest
        return VersionOptions(candidates = candidates, default = default)
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

    /**
     * Runs [body] as the only in-flight fetch of [coordinate], releasing the gate afterwards.
     *
     * **Why a gate and not a shared `Deferred` of the in-flight fetch.** Handing the second caller
     * the first one's `Deferred` is the usual de-duplication trick, and it is wrong here: since SDK
     * 0.15.0 an inbound `notifications/cancelled` really does cancel a `tools/call` handler, and the
     * whole point of that is to stop the download. A shared `Deferred` ties both callers to the
     * first one's fate — cancel the first and the second fails with it — while detaching the work
     * into a service-scoped coroutine so it survives would mean a withdrawn `fetch_library` keeps
     * downloading, which is exactly the property `ConcurrentDispatchTest` pins. A gate has neither
     * problem: each caller runs the work in its *own* coroutine, so cancelling one only releases the
     * gate and the next waiter does the work itself.
     *
     * **Why the map cannot grow without bound.** `coordinate` is caller-supplied, so a client that
     * asks for a million bogus coordinates must not leave a million mutexes behind. Every entry is
     * reference-counted and the last leaver removes it. The count is a plain `Int` because it is
     * only ever read or written inside [ConcurrentHashMap.compute], which holds the bin lock for
     * the duration — the map is the mutual exclusion, so no atomic is needed.
     */
    private suspend fun <T> withFetchGate(coordinate: LibraryCoordinate, body: suspend () -> T): T {
        val gate = fetchGates.compute(coordinate) { _, existing ->
            (existing ?: FetchGate()).also { it.holders++ }
        }!!
        try {
            return gate.mutex.withLock { body() }
        } finally {
            fetchGates.compute(coordinate) { _, existing ->
                // Returning null removes the entry. `existing` is always the gate we incremented:
                // an entry is only ever dropped by its last holder, which is us when this hits 0.
                existing?.let { if (--it.holders > 0) it else null }
            }
        }
    }

    /**
     * Compiles a caller-supplied `search_source` pattern.
     *
     * `search_source` takes its regex straight from the model's arguments, so the pattern is
     * length-capped and a syntax error becomes an argument error the model can act on rather than
     * a raw `PatternSyntaxException`. Runtime cost is bounded separately, at match time, by
     * [BoundedCharSequence] — a length cap alone would not help, since backtracking is exponential.
     */
    private fun compileSearchPattern(query: String): Regex {
        require(query.length <= MAX_SEARCH_PATTERN_LENGTH) {
            "Regular expression is too long (${query.length} characters, limit $MAX_SEARCH_PATTERN_LENGTH)"
        }
        return runCatching { Regex(query) }.getOrElse {
            throw IllegalArgumentException("Invalid regular expression '$query': ${it.message}")
        }
    }

    private suspend fun symbol(coordinate: LibraryCoordinate, fqName: String): ApiSymbol {
        val symbols = index(coordinate).symbolsByFqName
        // Exact key first; then the first overload (keys are disambiguated with a `#n` suffix).
        return symbols[fqName]
            ?: symbols.entries.firstOrNull { it.key.substringBefore('#') == fqName }?.value
            ?: throw IllegalArgumentException("No declaration '$fqName' in $coordinate (try list_declarations)")
    }

    /**
     * Normalized on-disk root of [coordinate]'s extracted sources.
     *
     * Costs one [MavenSourceFetcher.fetch]. That is a warm cache hit in the steady state, but not
     * a free one — the JVM fetcher reads and JSON-deserializes the cache marker every time — so
     * callers that read many files must resolve the root once and use [readSourceUnder].
     */
    private suspend fun sourceRoot(coordinate: LibraryCoordinate): Path =
        Path.of(fetcher.fetch(coordinate, repos).extractedDir).normalize()

    /** Reads one extracted source file beneath an already-resolved [root]. */
    private suspend fun readSourceUnder(root: Path, relativePath: String): String {
        val file = root.resolve(relativePath).normalize()
        require(file.startsWith(root)) { "Path escapes the source root: $relativePath" }
        if (!file.exists()) throw IllegalArgumentException("Source file not found on disk: $relativePath")
        return withContext(Dispatchers.IO) { file.readText() }
    }

    /** Reads one extracted source file; the fetch result is cache-marker-backed, so this is warm. */
    private suspend fun readSource(coordinate: LibraryCoordinate, relativePath: String): String =
        readSourceUnder(sourceRoot(coordinate), relativePath)

    private suspend fun LibraryIndex.summary(fromCache: Boolean): FetchSummary = FetchSummary(
        coordinate = coordinate,
        resolvedTargets = targets,
        sourceFileCount = files.size,
        packageCount = packages.size,
        fromCache = fromCache,
        extractedDir = localSourceRoot(coordinate),
    )

    /**
     * The extracted source root to report to the client, or `null` when [exposeLocalPaths] is off.
     *
     * Resolution failure is swallowed on purpose: this is a convenience field on an otherwise
     * successful fetch, and a library that is cached and analyzed is still perfectly usable through
     * every other tool if its on-disk root cannot be named right now.
     */
    private suspend fun localSourceRoot(coordinate: LibraryCoordinate): String? =
        if (!exposeLocalPaths) null else runCatching { sourceRoot(coordinate).toString() }.getOrNull()

    private companion object {
        const val MAX_SEARCH_RESULTS = 200

        /** Upper bound on a caller-supplied regex; far above any pattern a search needs. */
        const val MAX_SEARCH_PATTERN_LENGTH = 1_000

        /**
         * Character reads one line may cost the matcher. A linear pattern spends roughly the
         * line's length; anything that reaches a million is backtracking, and burns through this
         * in microseconds. See [BoundedCharSequence].
         */
        const val MATCH_BUDGET_PER_LINE = 1_000_000
        /**
         * A default page of source: comfortably more than a typical declaration, and roughly
         * 20 KB of ordinary Kotlin — small enough that an unprefixed `get_source` is never the
         * call that blows a context window.
         */
        const val DEFAULT_SOURCE_LINES = 500
        const val MAX_SOURCE_LINES = 5_000

        /** Hard ceiling on a page regardless of its line count; see `page`. */
        const val MAX_SOURCE_CHARS = 100_000
        const val MAX_DECLARATION_RESULTS = 500
        const val DEFAULT_DECLARATION_RESULTS = 100
        const val MAX_DEPENDENCY_DEPTH = 5
        const val LATEST = "latest"

        /**
         * How many versions [versionOptions] offers. A picker is a dropdown a human reads: enough
         * to cover the recent history of an actively-released artifact, few enough to scan.
         */
        const val DEFAULT_VERSION_OPTIONS = 12
        const val FETCH_STEPS = 3
    }
}
