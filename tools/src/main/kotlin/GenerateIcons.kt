/*
 * Draws the MCP icons (SEP-973) that the server advertises on its serverInfo, tools, prompt,
 * resource and resource template. Nothing depends on this module; it exists so the PNG assets
 * under `server/src/main/resources/icons/` are reproducible and editable, and it is compiled by
 * `./gradlew build` only so it cannot rot.
 *
 * Run, from the repo root:
 *
 *     ./gradlew :tools:generateIcons
 *
 * PNG is deliberate: the MCP spec requires icon-rendering clients to support image/png, while
 * image/svg+xml is only a SHOULD and carries an executable-content caveat clients may refuse.
 * Every glyph is drawn on a 128-unit grid and rasterized straight to the target size, so the
 * geometry below — not a downscaled bitmap — is the source of truth.
 */

package app.oreshkov.kotlinlibmcp.tools

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Design grid; every glyph below is expressed in these units. Matches assets/icon.svg. */
internal const val GRID = 128

/**
 * Flat, not the gradient of assets/icon.svg: these bytes ride inline (base64 data: URI) in
 * every tools/list, and a flat field halves the PNG. Indistinguishable at 48px.
 */
internal val BRAND = Color(0x7F52FF)

/** A named glyph and the pixel sizes it is rasterized at. */
internal class Glyph(
    val name: String,
    val sizes: List<Int> = listOf(48),
    val draw: Graphics2D.() -> Unit,
)

internal val GLYPHS: List<Glyph> = listOf(
    // Server brand mark: magnifier over code. Also rendered at 96 — connector UIs show it big.
    Glyph("server", listOf(48, 96)) {
        stroke(8, circle(58, 56, 26))
        stroke(10, line(77, 75, 98, 96))
        stroke(6, poly(52, 46, 42, 56, 52, 66))
        stroke(6, poly(64, 46, 74, 56, 64, 66))
    },
    // fetch_library: download into a tray.
    Glyph("fetch") {
        stroke(10, line(64, 26, 64, 72))
        stroke(10, poly(44, 56, 64, 76, 84, 56))
        stroke(10, line(34, 98, 94, 98))
    },
    // list_packages: a closed box.
    Glyph("packages") {
        stroke(9, rrect(28, 34, 72, 62, 8))
        stroke(9, line(28, 58, 100, 58))
        stroke(9, line(64, 34, 64, 58))
    },
    // list_declarations: a bulleted list.
    Glyph("declarations") {
        for (y in listOf(42, 64, 86)) {
            fill(circle(34, y, 6))
            stroke(9, line(54, y, 98, y))
        }
    },
    // get_api_signature: a call signature — parentheses around a value.
    Glyph("signature") {
        stroke(9, curve(50, 32, 32, 64, 50, 96))
        stroke(9, curve(78, 32, 96, 64, 78, 96))
        fill(circle(64, 64, 8))
    },
    // get_kdoc: a documented page.
    Glyph("kdoc") {
        stroke(9, rrect(34, 24, 60, 80, 10))
        stroke(7, line(50, 50, 78, 50))
        stroke(7, line(50, 66, 78, 66))
        stroke(7, line(50, 82, 68, 82))
    },
    // get_source: raw code.
    Glyph("source") {
        stroke(10, poly(54, 36, 30, 64, 54, 92))
        stroke(10, poly(74, 36, 98, 64, 74, 92))
    },
    // search_source: a magnifier — deliberately echoes the brand mark, minus the code chevrons.
    Glyph("search") {
        stroke(10, circle(56, 54, 26))
        stroke(12, line(76, 74, 100, 98))
    },
    // get_dependencies: a dependency tree.
    Glyph("dependencies") {
        stroke(7, line(64, 34, 64, 64))
        stroke(7, line(36, 64, 92, 64))
        stroke(7, line(36, 64, 36, 88))
        stroke(7, line(92, 64, 92, 88))
        fill(circle(64, 28, 11))
        fill(circle(36, 96, 11))
        fill(circle(92, 96, 11))
    },
    // list_versions: a stack of releases.
    Glyph("versions") {
        fill(rrect(28, 32, 72, 18, 8))
        fill(rrect(28, 56, 72, 18, 8))
        fill(rrect(28, 80, 72, 18, 8))
    },
    // get_latest_version: the newest one, on top.
    Glyph("latest") {
        stroke(10, line(64, 96, 64, 36))
        stroke(10, poly(40, 60, 64, 34, 88, 60))
    },
    // explain_public_api prompt: a spoken explanation.
    Glyph("prompt") {
        stroke(9, rrect(24, 26, 80, 58, 16))
        stroke(9, poly(44, 84, 44, 106, 66, 84))
    },
    // Library index resource + template: the parsed API, as a book.
    Glyph("index") {
        stroke(9, rrect(30, 24, 68, 80, 10))
        stroke(9, line(48, 24, 48, 104))
        stroke(7, line(62, 50, 84, 50))
        stroke(7, line(62, 70, 84, 70))
    },
)

fun main(args: Array<String>) {
    val out: Path = Path.of(args.firstOrNull() ?: "server/src/main/resources/icons")
    Files.createDirectories(out)
    for (glyph in GLYPHS) {
        for (size in glyph.sizes) {
            val file = out.resolve("${glyph.name}-$size.png")
            ImageIO.write(render(glyph, size), "png", file.toFile())
            println("$file (${Files.size(file)} bytes)")
        }
    }
    if (args.size > 1) {
        ImageIO.write(contactSheet(), "png", Path.of(args[1]).toFile())
        println("${args[1]} (contact sheet)")
    }
}

internal fun render(glyph: Glyph, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.antialias()
    g.scale(size / GRID.toDouble(), size / GRID.toDouble())
    g.paint = BRAND
    g.fill(rrect(0, 0, GRID, GRID, 28))
    g.paint = Color.WHITE
    glyph.draw(g)
    g.dispose()
    return image
}

/** All glyphs at 48px on a neutral background, for eyeballing the set as a whole. */
private fun contactSheet(): BufferedImage {
    val cell = 64
    val cols = 5
    val rows = (GLYPHS.size + cols - 1) / cols
    val sheet = BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    g.paint = Color(0x2A2A2E)
    g.fillRect(0, 0, sheet.width, sheet.height)
    GLYPHS.forEachIndexed { i, glyph ->
        g.drawImage(render(glyph, 48), (i % cols) * cell + 8, (i / cols) * cell + 8, null)
    }
    g.dispose()
    return sheet
}

// --- shape helpers, all in 128-unit design space ---

internal fun Graphics2D.antialias() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

internal fun Graphics2D.stroke(width: Int, shape: Shape) {
    // setStroke, not the synthetic `stroke` property: this function shadows that name.
    setStroke(BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
    draw(shape)
}

internal fun line(x1: Int, y1: Int, x2: Int, y2: Int): Shape =
    Line2D.Double(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble())

internal fun circle(cx: Int, cy: Int, r: Int): Shape =
    Ellipse2D.Double((cx - r).toDouble(), (cy - r).toDouble(), (2 * r).toDouble(), (2 * r).toDouble())

internal fun rrect(x: Int, y: Int, w: Int, h: Int, r: Int): Shape =
    RoundRectangle2D.Double(
        x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), (2 * r).toDouble(), (2 * r).toDouble(),
    )

internal fun poly(vararg xy: Int): Shape {
    val path = Path2D.Double()
    path.moveTo(xy[0].toDouble(), xy[1].toDouble())
    for (i in 2 until xy.size step 2) {
        path.lineTo(xy[i].toDouble(), xy[i + 1].toDouble())
    }
    return path
}

internal fun curve(x1: Int, y1: Int, cx: Int, cy: Int, x2: Int, y2: Int): Shape {
    val path = Path2D.Double()
    path.moveTo(x1.toDouble(), y1.toDouble())
    path.quadTo(cx.toDouble(), cy.toDouble(), x2.toDouble(), y2.toDouble())
    return path
}
