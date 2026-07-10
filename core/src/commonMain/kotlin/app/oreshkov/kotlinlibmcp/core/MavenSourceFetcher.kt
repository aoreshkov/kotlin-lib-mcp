package app.oreshkov.kotlinlibmcp.core

import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.KmpTarget
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.SourceFileRef
import app.oreshkov.kotlinlibmcp.util.MavenVersions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outcome of resolving and downloading a coordinate's sources: the per-target sources jars that
 * were resolved, the directory they were extracted into, and the files found inside. Paths are
 * plain [String]s — the JVM implementation owns all file IO.
 */
@Serializable
@SerialName("FetchResult")
public data class FetchResult(
    val coordinate: LibraryCoordinate,
    val resolvedTargets: List<KmpTarget>,
    val downloadedJars: List<String>,
    val extractedDir: String,
    val files: List<SourceFileRef> = emptyList(),
)

/**
 * An artifact's published versions from `maven-metadata.xml`: the full [versions] list plus the
 * repository's declared [release] (latest stable) and [latest] (newest overall) tags, which are the
 * canonical "latest" answers when a repository populates them.
 */
public data class VersionCatalog(
    val versions: List<String>,
    val release: String? = null,
    val latest: String? = null,
)

/**
 * Port for acquiring a library's **sources** from Maven repositories: variant resolution,
 * download/extract, version listing, and dependency-tree resolution. The JVM implementation
 * (Phase 03) uses a Ktor client + `.module`/`.pom` parsing; all methods suspend because they do
 * network and disk IO.
 */
public interface MavenSourceFetcher {

    /** Resolves the correct per-target sources jars, downloads and extracts them, and caches the result. */
    public suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult

    /** Reads `maven-metadata.xml` for an artifact: version list plus the `release`/`latest` tags. */
    public suspend fun fetchVersionCatalog(group: String, artifact: String, repos: List<String>): VersionCatalog

    /** Available versions for an artifact, newest-first by semantic version order. */
    public suspend fun listVersions(group: String, artifact: String, repos: List<String>): List<String> =
        fetchVersionCatalog(group, artifact, repos).versions
            .sortedWith(MavenVersions.VERSION_COMPARATOR.reversed())

    /** Resolves the dependency tree from `.pom`/`.module` to [maxDepth], deduplicating coordinates. */
    public suspend fun resolveDependencies(
        coordinate: LibraryCoordinate,
        repos: List<String>,
        maxDepth: Int,
    ): DependencyNode
}
