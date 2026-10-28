plugins {
    id("jvm-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // OpenTelemetry — traces over OTLP/HTTP, inert unless `--otel` is passed.
    // See `hoistOverKotlinCompiler` (build-logic): these jars must be forced ahead of `kotlin-compiler`.
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.autoconfigure)
    implementation(libs.opentelemetry.extension.kotlin) {
        // Since 1.67.0 it imports `kotlin-bom` as a platform, whose constraints would drag
        // `kotlin-compiler` past the version the Analysis API jars need. `core` pins that compiler
        // `strictly`, so this would fail resolution rather than ship — drop the BOM, keep the stdlib.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-bom")
    }
    implementation(libs.opentelemetry.exporter.otlp) {
        // We only speak http/protobuf; the default OkHttp sender would drag in OkHttp *and* a
        // second Kotlin stdlib. `opentelemetry-exporter-sender-jdk` below covers it with no deps.
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    runtimeOnly(libs.opentelemetry.sender.jdk)

    implementation(project(":core"))
    implementation(libs.cliKit)
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
    testImplementation(libs.cliKit.testing)
}

// `kotlin-compiler` bundles stale, unrelocated copies of jars we depend on for real; without this
// even `Server.addTool` throws `NoSuchMethodError`. See build-logic's KotlinCompilerShadowing.kt.
tasks.test { classpath = classpath.hoistOverKotlinCompiler() }
tasks.named<JavaExec>("run") { classpath = classpath.hoistOverKotlinCompiler() }
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath?.hoistOverKotlinCompiler() }

// Single source of truth for the MCP `Implementation` version: bake `project.version`
// (from gradle.properties) into a classpath resource read at startup, so the advertised
// server version can never drift from the build. See `ServerVersion` in the server sources.
val generateVersionResource = tasks.register<WriteProperties>("generateVersionResource") {
    destinationFile = layout.buildDirectory.file("generated/version/mcp-version.properties")
    property("version", providers.provider { project.version.toString() })
}
tasks.processResources {
    from(generateVersionResource) { into("META-INF/kotlin-lib-mcp") }
}

// Let the version test assert the resource matches the build's project.version, and
// AnalysisApiClasspathTest that the runtime kotlin-compiler is the one the Analysis API expects.
tasks.test {
    systemProperty("kotlin-lib-mcp.expectedVersion", project.version.toString())
    systemProperty("kotlin-lib-mcp.expectedKotlinVersion", libs.versions.kotlin.get())
    // `./gradlew :server:test -Dgolden.update=true` rewrites the pinned --help page; read the diff.
    systemProperty("golden.update", providers.systemProperty("golden.update").getOrElse("false"))
    // RegistryManifestTest runs server.json's packageArguments through the parser.
    inputs.file(layout.projectDirectory.file("../server.json")).withPathSensitivity(PathSensitivity.NONE)
}

application {
    mainClass = "app.oreshkov.kotlinlibmcp.server.MainKt"
    // The JVM's unified logging writes to *stdout* by default — and stdout is the stdio protocol
    // channel. An unusable AOT cache, for one, prints `[error][aot] …` lines there before `main`
    // runs. Route every JVM log line to stderr instead, for every launch of the distribution.
    //
    // cli-kit makes FFM downcalls on Windows only (VT escapes at a console; telling a pipe from a
    // file after a failed stdout write). JEP 472 has a classpath app's launcher grant that up front;
    // without it JDK 24+ warns on stderr, and a future JDK will deny it.
    applicationDefaultJvmArgs = listOf(
        "-Xlog:disable",
        "-Xlog:all=warning:stderr",
        "--enable-native-access=ALL-UNNAMED",
    )
}
