package app.oreshkov.kotlinlibmcp.server

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `kotlin-compiler` jar the server *runs* with must be the exact version the Analysis API
 * `-for-ide` jars were built against — the catalog's `kotlin`. A mismatch never fails a compile: it
 * fails every `fetch_library` at runtime (`NoClassDefFoundError: ClsKotlinBinaryClassCache` for
 * 2.4.10 jars against a 2.4.20+ compiler).
 *
 * This has to be checked here, not in `core`: the drift came from a *server* dependency.
 * `opentelemetry-extension-kotlin` 1.67.0 imports `kotlin-bom` 2.4.21, whose constraints outranked
 * `core`'s 2.4.10 in conflict resolution, so `core`'s own test classpath stayed correct while the
 * shipped one did not. `core` now declares the version `strictly`; this pins that it holds.
 */
class AnalysisApiClasspathTest {

    @Test
    fun runtimeKotlinCompilerMatchesTheAnalysisApiVersion() {
        val expected = System.getProperty("kotlin-lib-mcp.expectedKotlinVersion") ?: return
        // Reflection: `kotlin-compiler` is on `core`'s implementation scope, not our compile classpath.
        val actual = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion")
            .getField("VERSION").get(null) as String
        assertEquals(expected, actual, "kotlin-compiler on the runtime classpath must match the Analysis API jars")
    }
}
