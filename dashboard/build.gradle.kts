plugins {
    id("compose-desktop-app")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":server")) // reuses McpServerFactory/LibraryService — UI stays thin
    implementation(compose.desktop.currentOs)
    implementation(libs.mcp.kotlinSdkServer) // `Server`, the type `createHttpEngine` takes
    implementation(libs.ktor.server.core) // `EmbeddedServer`, the type it returns
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.logback.classic) // compile dep too: the log pane's Logback appender
    implementation(libs.slf4j.api)
    implementation(libs.kermit)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The dashboard runs `:core` in-process, so `kotlin-compiler`'s stale bundled copies would shadow
// the real jars here exactly as in `:server` — `McpServerFactory.create()` would throw
// `NoSuchMethodError`. See build-logic's KotlinCompilerShadowing.kt.
tasks.test { classpath = classpath.hoistOverKotlinCompiler() }

val runtimeJars = files(tasks.jar).plus(sourceSets.main.get().runtimeClasspath.filter { it.name.endsWith(".jar") })

// Compose hands `run` and the uber jar the classpath in the order given, but jpackage ignores that
// order and sorts the jars by file name, which puts `kotlin-compiler-…` ahead of
// `kotlinx-collections-immutable-…` and `opentelemetry-…`. So the shadowed jars go in under names
// that sort first, and every Compose task gets the same classpath.
val hoistedJarsDir = layout.buildDirectory.dir("hoisted-jars")
val hoistJars = tasks.register<Sync>("hoistJarsOverKotlinCompiler") {
    from(runtimeJars.shadowedByKotlinCompiler())
    into(hoistedJarsDir)
    rename { "0-$it" }
}

compose.desktop {
    application {
        mainClass = "app.oreshkov.kotlinlibmcp.dashboard.AppKt"
        disableDefaultConfiguration()
        mainJar = tasks.jar.flatMap { it.archiveFile }
        fromFiles(
            fileTree(hoistedJarsDir) { builtBy(hoistJars) },
            runtimeJars.minus(runtimeJars.shadowedByKotlinCompiler()),
        )
    }
}
