plugins {
    id("jvm-tool")
}

dependencies {
    testImplementation(libs.kotlin.test)
}

// The generator writes into source-controlled locations outside this module — the PNGs and the
// SVG are committed artifacts, not build output — so this is an explicit, on-demand task with no
// outputs declared and nothing wired into `assemble`. `test` is what keeps them honest.
val iconsDir = layout.projectDirectory.dir("../server/src/main/resources/icons")
val brandSvg = layout.projectDirectory.file("../assets/icon.svg")

tasks.register<JavaExec>("generateIcons") {
    group = "assets"
    description = "Redraws the MCP icon PNGs into server/src/main/resources/icons/ and assets/icon.svg"
    mainClass = "app.oreshkov.kotlinlibmcp.tools.GenerateIconsKt"
    classpath = sourceSets["main"].runtimeClasspath
    args(iconsDir.asFile.path, brandSvg.asFile.path)
}

/**
 * Hands `GenerateIconsTest` the committed assets as tracked inputs, so the test reruns when one
 * changes. Relative path sensitivity keeps the build-cache entry relocatable, which a plain
 * `systemProperty` carrying an absolute path would not.
 */
class CommittedIcons(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val pngDir: File,
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val svgFile: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dicons.pngDir=$pngDir", "-Dicons.svgFile=$svgFile")
}

tasks.test {
    jvmArgumentProviders += CommittedIcons(iconsDir.asFile, brandSvg.asFile)
}
