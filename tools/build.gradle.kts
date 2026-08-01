plugins {
    id("jvm-tool")
}

// Both generators write into source-controlled locations outside this module — the PNGs are
// committed artifacts, not build output — so these are explicit, on-demand tasks with no
// outputs declared and nothing wired into `assemble`.
val iconsDir = layout.projectDirectory.dir("../server/src/main/resources/icons").asFile
val assetsDir = layout.projectDirectory.dir("../assets").asFile
val toolClasspath = sourceSets["main"].runtimeClasspath

tasks.register<JavaExec>("generateIcons") {
    group = "assets"
    description = "Redraws the MCP icon PNGs into server/src/main/resources/icons/"
    mainClass = "app.oreshkov.kotlinlibmcp.tools.GenerateIconsKt"
    classpath = toolClasspath
    args(iconsDir.path)
}

tasks.register<JavaExec>("generateSocialPreview") {
    group = "assets"
    description = "Redraws assets/social-preview.png (the repository's social preview card)"
    mainClass = "app.oreshkov.kotlinlibmcp.tools.GenerateSocialPreviewKt"
    classpath = toolClasspath
    args(File(assetsDir, "social-preview.png").path)
}
