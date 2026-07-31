plugins {
    `kotlin-dsl`
}

// Precompiled convention plugins apply other plugins by id WITHOUT a version, so those
// plugins' gradle-plugin artifacts must be on this build's classpath.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.composeCompiler.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
}
