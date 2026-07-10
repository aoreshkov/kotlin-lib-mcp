plugins {
    id("compose-desktop-app")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":server")) // reuses McpServerFactory/LibraryService — UI stays thin
    implementation(compose.desktop.currentOs)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.logback.classic) // compile dep too: the log pane's Logback appender
    implementation(libs.slf4j.api)
    implementation(libs.kermit)
}

compose.desktop {
    application {
        mainClass = "app.oreshkov.kotlinlibmcp.dashboard.AppKt"
    }
}
