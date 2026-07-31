plugins {
    id("jvm-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // OpenTelemetry — traces over OTLP/HTTP, inert unless `--otel` is passed.
    // See `hoistOverKotlinCompiler` below: these jars must be forced ahead of `kotlin-compiler`.
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.autoconfigure)
    implementation(libs.opentelemetry.extension.kotlin)
    implementation(libs.opentelemetry.exporter.otlp) {
        // We only speak http/protobuf; the default OkHttp sender would drag in OkHttp *and* a
        // second Kotlin stdlib. `opentelemetry-exporter-sender-jdk` below covers it with no deps.
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    runtimeOnly(libs.opentelemetry.sender.jdk)

    implementation(project(":core"))
    implementation(libs.mcp.kotlinSdkServer)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kermit)

    implementation(libs.slf4j.api)
    implementation(libs.kotlinLogging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.opentelemetry.bom))
    testImplementation(libs.opentelemetry.sdk.testing)
}

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
 * So hoist the genuine jars to the front of every runtime classpath we hand to a JVM. Before adding
 * any dependency `kotlin-compiler` might also bundle, check with
 * `unzip -l <kotlin-compiler.jar> | grep <package-path>`. See .claude/rules/mcp-server.md.
 */
fun FileCollection.hoistOverKotlinCompiler(): FileCollection {
    // Kept local: a script-level `val` captured in this filter is a Gradle script object
    // reference, which the configuration cache refuses to serialize.
    val shadowed = listOf("opentelemetry-", "kotlinx-collections-immutable")
    val genuine = filter { jar -> shadowed.any { jar.name.startsWith(it) } }
    return genuine.plus(minus(genuine))
}

tasks.test { classpath = classpath.hoistOverKotlinCompiler() }
tasks.named<JavaExec>("run") { classpath = classpath.hoistOverKotlinCompiler() }
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath?.hoistOverKotlinCompiler() }

// Single source of truth for the MCP `Implementation` version: bake `project.version`
// (from gradle.properties) into a classpath resource read at startup, so the advertised
// server version can never drift from the build. See `ServerVersion` in the server sources.
val generateVersionResource by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/version/mcp-version.properties")
    property("version", providers.provider { project.version.toString() })
}
tasks.processResources {
    from(generateVersionResource) { into("META-INF/kotlin-lib-mcp") }
}

// Let the version test assert the resource matches the build's project.version.
tasks.test {
    systemProperty("kotlin-lib-mcp.expectedVersion", project.version.toString())
}

application {
    mainClass = "app.oreshkov.kotlinlibmcp.server.MainKt"
}
