plugins {
    id("jvm-tool")
}

// The generator writes into a source-controlled location outside this module — the PNGs are
// committed artifacts, not build output — so this is an explicit, on-demand task with no
// outputs declared and nothing wired into `assemble`.
val iconsDir = layout.projectDirectory.dir("../server/src/main/resources/icons").asFile

tasks.register<JavaExec>("generateIcons") {
    group = "assets"
    description = "Redraws the MCP icon PNGs into server/src/main/resources/icons/"
    mainClass = "app.oreshkov.kotlinlibmcp.tools.GenerateIconsKt"
    classpath = sourceSets["main"].runtimeClasspath
    args(iconsDir.path)
}
