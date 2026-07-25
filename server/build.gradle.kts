plugins {
    id("jvm-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // OpenTelemetry — traces over OTLP/HTTP, inert unless `--otel` is passed.
    // See `hoistOpenTelemetry` below: these jars must be forced ahead of `kotlin-compiler`.
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
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kermit)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.opentelemetry.bom))
    testImplementation(libs.opentelemetry.sdk.testing)
}

/*
 * Classpath shadowing: `kotlin-compiler` (pulled in by `:core` for the Analysis API) is a fat jar
 * that bundles ~83 *stripped stubs* of an old, unrelocated `io.opentelemetry.api` (1.41.0) —
 * `Attributes`, `OpenTelemetry`, `Tracer`, `TracerProvider`, `LoggerProvider` are all empty
 * shells. Gradle resolves the real `opentelemetry-api` far behind `kotlin-compiler` on the runtime
 * classpath (shared transitives sort late, so declaration order alone does NOT fix it), and the
 * first copy wins — leaving `--otel` to die with `NoSuchMethodError: Attributes.empty()`.
 *
 * So hoist the genuine OTel jars to the front of every runtime classpath we hand to a JVM.
 * Same family of problem as the resource-template matcher; see .claude/rules/mcp-server.md.
 */
fun FileCollection.hoistOpenTelemetry(): FileCollection {
    val otel = filter { it.name.startsWith("opentelemetry-") }
    return otel.plus(minus(otel))
}

tasks.test { classpath = classpath.hoistOpenTelemetry() }
tasks.named<JavaExec>("run") { classpath = classpath.hoistOpenTelemetry() }
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath?.hoistOpenTelemetry() }

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
