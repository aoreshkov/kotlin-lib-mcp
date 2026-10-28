import org.gradle.api.file.FileCollection

/*
 * Classpath shadowing: `kotlin-compiler` (pulled in by `:core` for the Analysis API) is a fat jar
 * that bundles old, *unrelocated* copies of libraries we also depend on for real. Gradle resolves
 * the genuine artifacts far behind `kotlin-compiler` on the runtime classpath (shared transitives
 * sort late, so declaration order alone does NOT fix it) and the first copy on the classpath wins.
 *
 * Two families bite us, both fatally and both only at runtime:
 *
 *  - `io.opentelemetry.api` 1.41.0 — ~83 *stripped stubs*; `Attributes`, `OpenTelemetry`, `Tracer`,
 *    `TracerProvider` and `LoggerProvider` are empty shells, so `--otel` dies with
 *    `NoSuchMethodError: Attributes.empty()`.
 *  - `kotlinx.collections.immutable` — 127 classes of a pre-0.5 release. kotlin-sdk 0.15.0 rewrote
 *    `Protocol` and `FeatureRegistry` around the newer `PersistentMap.putting`/`removing` names, so
 *    the stale copy makes even `Server.addTool` throw `NoSuchMethodError` at startup. (0.14.0 only
 *    tripped over this inside the SDK's `PathSegmentTemplateMatcher` — which is why
 *    `resources/SegmentTemplateMatcher.kt` exists.)
 *
 * So every module that runs `:core` in a JVM — `:server` and `:dashboard` — hoists the genuine jars
 * to the front of every runtime classpath it hands to one. Before adding any dependency
 * `kotlin-compiler` might also bundle, check with `unzip -l <kotlin-compiler.jar> | grep <package-path>`.
 * See .claude/rules/mcp-server.md.
 *
 * Both functions keep the prefix list local: this file compiles to a class, so a top-level `val`
 * would serialize fine, but the same list was once a build-script `val`, which the configuration
 * cache refuses (a script object reference) — keeping it local keeps a copy-paste back safe.
 */

/** The jars on this classpath whose classes `kotlin-compiler` also bundles, in stale copies. */
fun FileCollection.shadowedByKotlinCompiler(): FileCollection {
    val shadowed = listOf("opentelemetry-", "kotlinx-collections-immutable")
    return filter { jar -> shadowed.any { jar.name.startsWith(it) } }
}

/** This classpath with [shadowedByKotlinCompiler] moved to the front, order otherwise kept. */
fun FileCollection.hoistOverKotlinCompiler(): FileCollection {
    val genuine = shadowedByKotlinCompiler()
    return genuine.plus(minus(genuine))
}
