package app.oreshkov.kotlinlibmcp.server

import java.util.Properties

/**
 * The server's version, read once from the `mcp-version.properties` classpath resource that the
 * Gradle build bakes from `project.version` (see `generateVersionResource` in `server/build.gradle.kts`).
 * This is the single source of truth for the MCP `Implementation` version, so it can never drift
 * from `gradle.properties`. Falls back to [DEV_VERSION] when run from sources without the resource.
 */
internal object ServerVersion {
    /** Used when the generated resource is absent (e.g. running straight from `src` in an IDE). */
    const val DEV_VERSION: String = "0.0.0-dev"

    private const val RESOURCE = "/META-INF/kotlin-lib-mcp/mcp-version.properties"

    val value: String by lazy {
        ServerVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")?.takeIf { it.isNotBlank() }
        } ?: DEV_VERSION
    }
}
