plugins {
    `kotlin-dsl`
}

// Precompiled convention plugins apply other plugins by id WITHOUT a version, so those
// plugins' gradle-plugin artifacts must be on this build's classpath.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.bcv.gradle.plugin)
}
