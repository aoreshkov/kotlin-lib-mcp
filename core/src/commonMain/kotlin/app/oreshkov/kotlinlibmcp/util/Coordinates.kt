package app.oreshkov.kotlinlibmcp.util

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate

/**
 * Pure coordinate <-> Maven-layout helpers, shared between `commonMain` and the JVM fetcher
 * (Phase 03). No IO and no platform types so they stay testable in `commonTest`.
 */
public object Coordinates {

    /**
     * Maven repository directory for a coordinate, relative to a repo root, e.g.
     * `io.ktor:ktor-client-core:3.5.1` → `io/ktor/ktor-client-core/3.5.1`. Always `/`-separated
     * (URL path segments), never the OS file separator.
     */
    public fun repositoryPath(coordinate: LibraryCoordinate): String {
        val groupPath = coordinate.group.replace('.', '/')
        return "$groupPath/${coordinate.artifact}/${coordinate.version}"
    }

    /** [repositoryPath] joined with an artifact file name, e.g. `…/3.5.1/ktor-client-core-3.5.1.pom`. */
    public fun artifactPath(coordinate: LibraryCoordinate, fileName: String): String =
        "${repositoryPath(coordinate)}/$fileName"

    /**
     * Directory for an artifact's `maven-metadata.xml` (version listing), which lives one level
     * above the version dirs: `io/ktor/ktor-client-core`.
     */
    public fun metadataPath(group: String, artifact: String): String =
        "${group.replace('.', '/')}/$artifact/maven-metadata.xml"
}
