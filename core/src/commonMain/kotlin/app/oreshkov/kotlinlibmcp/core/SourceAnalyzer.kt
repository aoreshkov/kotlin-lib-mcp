package app.oreshkov.kotlinlibmcp.core

import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex

/**
 * Port that turns extracted `.kt` sources into a structured [LibraryIndex].
 *
 * Intentionally **synchronous and pure**: the JVM implementation (Phase 04) wraps the Kotlin
 * Analysis API, which is version-fragile CPU-bound work — callers offload it to a background
 * dispatcher. Keeping the Analysis API fully behind this interface is what lets it be swapped for
 * a PSI-only or metadata-only path without touching the rest of the codebase.
 */
public interface SourceAnalyzer {

    /**
     * Analyzes the given source roots, using [classpathRoots] as best-effort binary roots for type
     * resolution, and returns the assembled index for [coordinate]. Missing classpath entries
     * reduce resolution quality (more `bestEffort` symbols) rather than failing the analysis.
     */
    public fun analyze(
        coordinate: LibraryCoordinate,
        sourceRoots: List<String>,
        classpathRoots: List<String>,
    ): LibraryIndex
}
