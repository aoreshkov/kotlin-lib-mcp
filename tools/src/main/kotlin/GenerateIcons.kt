/*
 * Draws the MCP icons (SEP-973) that the server advertises on its serverInfo, tools, prompt,
 * resource and resource template. Nothing depends on this module; it exists so the PNG assets
 * under `server/src/main/resources/icons/` are reproducible and editable, and it is compiled by
 * `./gradlew build` only so it cannot rot.
 *
 * It also writes `assets/icon.svg`, the vector brand mark `server.json` and the README use, from
 * the same geometry as the `server` glyph — so the two cannot drift apart. Both outputs are
 * committed, and `GenerateIconsTest` fails when either no longer matches this file.
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

/** Design grid; every glyph below is expressed in these units, and it is the SVG's viewBox. */
internal const val GRID = 128

/**
 * Flat, not the gradient of the SVG: these bytes ride inline (base64 data: URI) in every
 * tools/list, and a flat field halves the PNG. Indistinguishable at 48px.
 */
internal val BRAND = Color(0x7F52FF)

/** The SVG's background, top-left to bottom-right. A paint, not geometry: the tile is [TILE]. */
internal val BRAND_GRADIENT = listOf(BRAND, Color(0x3B6CEB))

/** The rounded tile every glyph sits on. */
internal val TILE = rrect(0, 0, GRID, GRID, 28)

/** The glyph [svg] draws as `assets/icon.svg`. */
internal const val BRAND_GLYPH = "server"

/**
 * What a glyph draws with. Glyphs see only this, never a back-end, which is what lets the same
 * drawing become a PNG ([render]) and an SVG ([svg]).
 */
internal interface Pen {
    /** Outlines [shape] with round caps and joins, [width] grid units wide. */
    fun stroke(width: Int, shape: Prim)

    fun fill(shape: Prim)
}

/** A named glyph and the pixel sizes it is rasterized at. */
internal class Glyph(
    val name: String,
    val sizes: List<Int> = listOf(48),
    val draw: Pen.() -> Unit,
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
    // diff_versions: a removed line above an added one — the one diff mark that survives 48px.
    // Both bars are 64 wide and the plus is centred under the minus, so the pair reads as a unit;
    // the composition spans y 27..101, centred on the grid like every other glyph here.
    Glyph("diff") {
        stroke(10, line(32, 32, 96, 32))
        stroke(10, line(32, 76, 96, 76))
        stroke(10, line(64, 56, 64, 96))
    },
    // diff_api: diff_versions' minus-over-plus, inside get_api_signature's parentheses — a diff of
    // signatures. The marks shrink to 20 wide to clear the curves' inner edge at x 48..80.
    Glyph("apidiff") {
        stroke(9, curve(44, 32, 26, 64, 44, 96))
        stroke(9, curve(84, 32, 102, 64, 84, 96))
        stroke(8, line(54, 46, 74, 46))
        stroke(8, line(54, 76, 74, 76))
        stroke(8, line(64, 66, 64, 86))
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

/** Arguments: `<png dir> <svg file> [contact sheet png]`. */
fun main(args: Array<String>) {
    val out: Path = Path.of(args.getOrNull(0) ?: "server/src/main/resources/icons")
    val svgFile: Path = Path.of(args.getOrNull(1) ?: "assets/icon.svg")
    Files.createDirectories(out)
    for (glyph in GLYPHS) {
        for (size in glyph.sizes) {
            val file = out.resolve(pngName(glyph, size))
            ImageIO.write(render(glyph, size), "png", file.toFile())
            println("$file (${Files.size(file)} bytes)")
        }
    }
    Files.writeString(svgFile, svg(GLYPHS.single { it.name == BRAND_GLYPH }))
    println("$svgFile (${Files.size(svgFile)} bytes)")
    if (args.size > 2) {
        ImageIO.write(contactSheet(), "png", Path.of(args[2]).toFile())
        println("${args[2]} (contact sheet)")
    }
}

internal fun pngName(glyph: Glyph, size: Int) = "${glyph.name}-$size.png"

internal fun render(glyph: Glyph, size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.antialias()
    g.scale(size / GRID.toDouble(), size / GRID.toDouble())
    g.paint = BRAND
    g.fill(TILE.shape)
    g.paint = Color.WHITE
    AwtPen(g).(glyph.draw)()
    g.dispose()
    return image
}

/**
 * [glyph] as a standalone SVG document: the [TILE] under [BRAND_GRADIENT], then the glyph in white.
 * Plain shapes and presentation attributes only — no script, no external reference, no CSS — so
 * there is nothing for a client's SVG sanitizer to strip, which is the spec's caveat about SVG icons.
 */
internal fun svg(glyph: Glyph): String {
    val pen = SvgPen()
    pen.(glyph.draw)()
    val (from, to) = BRAND_GRADIENT
    return buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $GRID $GRID">""").append('\n')
        // SVG 2 asks a standalone document's root to carry a title: the accessible name.
        append("  <title>kotlin-lib-mcp</title>\n")
        append("  <defs>\n")
        append("""    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">""").append('\n')
        append("""      <stop offset="0" stop-color="${from.hex()}"/>""").append('\n')
        append("""      <stop offset="1" stop-color="${to.hex()}"/>""").append('\n')
        append("    </linearGradient>\n")
        append("  </defs>\n")
        append("  ${TILE.svg} fill=\"url(#bg)\"/>\n")
        // One group carries the paint every Pen operation shares, as AwtPen's BasicStroke does.
        append("""  <g fill="none" stroke="#FFFFFF" stroke-linecap="round" stroke-linejoin="round">""")
        append('\n')
        pen.elements.forEach { append("    ").append(it).append('\n') }
        append("  </g>\n")
        append("</svg>\n")
    }
}

private fun Color.hex() = "#%06X".format(rgb and 0xFFFFFF)

private class AwtPen(private val g: Graphics2D) : Pen {
    override fun stroke(width: Int, shape: Prim) {
        g.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape.shape)
    }

    override fun fill(shape: Prim) = g.fill(shape.shape)
}

private class SvgPen : Pen {
    val elements = mutableListOf<String>()

    override fun stroke(width: Int, shape: Prim) {
        elements += """${shape.svg} stroke-width="$width"/>"""
    }

    override fun fill(shape: Prim) {
        elements += """${shape.svg} fill="#FFFFFF" stroke="none"/>"""
    }
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

/**
 * One shape, written once and spelled for both back-ends: [shape] for AWT, [svg] as an SVG element
 * left open for the pen to append its paint attributes and close.
 */
internal class Prim(val shape: Shape, val svg: String)

internal fun line(x1: Int, y1: Int, x2: Int, y2: Int) = Prim(
    Line2D.Double(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble()),
    """<line x1="$x1" y1="$y1" x2="$x2" y2="$y2"""",
)

internal fun circle(cx: Int, cy: Int, r: Int) = Prim(
    Ellipse2D.Double((cx - r).toDouble(), (cy - r).toDouble(), (2 * r).toDouble(), (2 * r).toDouble()),
    """<circle cx="$cx" cy="$cy" r="$r"""",
)

/** AWT takes the corner's diameter, SVG its radius: [r] is the radius in both. */
internal fun rrect(x: Int, y: Int, w: Int, h: Int, r: Int) = Prim(
    RoundRectangle2D.Double(
        x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), (2 * r).toDouble(), (2 * r).toDouble(),
    ),
    """<rect x="$x" y="$y" width="$w" height="$h" rx="$r"""",
)

internal fun poly(vararg xy: Int): Prim {
    val path = Path2D.Double()
    path.moveTo(xy[0].toDouble(), xy[1].toDouble())
    for (i in 2 until xy.size step 2) {
        path.lineTo(xy[i].toDouble(), xy[i + 1].toDouble())
    }
    val d = xy.toList().chunked(2).withIndex()
        .joinToString(" ") { (i, p) -> "${if (i == 0) "M" else "L"}${p[0]} ${p[1]}" }
    return Prim(path, """<path d="$d"""")
}

internal fun curve(x1: Int, y1: Int, cx: Int, cy: Int, x2: Int, y2: Int): Prim {
    val path = Path2D.Double()
    path.moveTo(x1.toDouble(), y1.toDouble())
    path.quadTo(cx.toDouble(), cy.toDouble(), x2.toDouble(), y2.toDouble())
    return Prim(path, """<path d="M$x1 $y1 Q$cx $cy $x2 $y2"""")
}
