/*
 * Draws `assets/social-preview.png`, the repository's social preview card — the image GitHub
 * shows on the github.com/mcp registry listing, in search results, and in link unfurls. Without
 * one, those surfaces fall back to a generic auto-generated card.
 *
 * Run, from the repo root:
 *
 *     ./gradlew :tools:generateSocialPreview
 *
 * 1280x640 is GitHub's recommended size (minimum 640x320, maximum 1 MB). Uploading it is manual:
 * Settings -> General -> Social preview. There is no API for it.
 *
 * The mark is the `server` glyph from GenerateIcons.kt, rendered at card scale rather than
 * redrawn, so the card and the icons the server advertises can never drift apart.
 *
 * Caveat: the text is set in the logical SANS_SERIF family, so the exact letterforms depend on
 * the fonts installed on the machine that runs this. The committed PNG is the artifact of record;
 * a regeneration elsewhere may differ slightly. That is accepted, not engineered around.
 */

package app.oreshkov.kotlinlibmcp.tools

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

private const val WIDTH = 1280
private const val HEIGHT = 640

/** Generous margins: GitHub crops this card differently per surface. */
private const val MARGIN = 120

private val BACKGROUND = Color(0x17171A)
private val TITLE_COLOR = Color(0xFFFFFF)
private val BODY_COLOR = Color(0xC9C5D3)
private val FOOTER_COLOR = Color(0x8A8595)

private const val TITLE = "kotlin-lib-mcp"
private val TAGLINE = listOf(
    "Give your AI agent the real sources of any",
    "Maven-published Kotlin/Java library.",
)
private const val FOOTER = "MCP server  ·  Kotlin Analysis API  ·  KMP-aware"

fun main(args: Array<String>) {
    val out = Path.of(args.firstOrNull() ?: "assets/social-preview.png")
    out.parent?.let { Files.createDirectories(it) }
    ImageIO.write(renderCard(), "png", out.toFile())
    println("$out (${Files.size(out)} bytes, ${WIDTH}x$HEIGHT)")
}

private fun renderCard(): BufferedImage {
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.antialias()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    g.paint = BACKGROUND
    g.fillRect(0, 0, WIDTH, HEIGHT)

    // Brand rule along the bottom edge — the only saturated area besides the mark.
    g.paint = BRAND
    g.fillRect(0, HEIGHT - 8, WIDTH, 8)

    val mark = 224
    val markY = (HEIGHT - mark) / 2
    g.drawImage(render(GLYPHS.first { it.name == "server" }, mark), MARGIN, markY, null)

    val textLeft = MARGIN + mark + 72
    val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 82)
    val bodyFont = Font(Font.SANS_SERIF, Font.PLAIN, 36)
    val footerFont = Font(Font.SANS_SERIF, Font.PLAIN, 24)

    // Lay the block out from its measured height so it stays optically centred against the mark
    // whatever the host's fonts do to the metrics. `y` tracks the top edge of the next line;
    // each drawString adds that line's ascent to get its baseline.
    val titleMetrics = g.getFontMetrics(titleFont)
    val bodyMetrics = g.getFontMetrics(bodyFont)
    val footerMetrics = g.getFontMetrics(footerFont)
    val blockHeight =
        titleMetrics.height + 28 + TAGLINE.size * bodyMetrics.height + 36 + footerMetrics.height
    var y = (HEIGHT - blockHeight) / 2

    // Metrics depend on the host's fonts, so check the safe area rather than trusting a
    // hand-tuned string length. Fails the task instead of shipping a clipped card.
    val widest = maxOf(
        titleMetrics.stringWidth(TITLE),
        TAGLINE.maxOf { bodyMetrics.stringWidth(it) },
        footerMetrics.stringWidth(FOOTER),
    )
    check(textLeft + widest <= WIDTH - MARGIN) {
        "Text overflows the safe area by ${textLeft + widest - (WIDTH - MARGIN)}px — " +
            "shorten a line or drop a font size"
    }

    g.font = titleFont
    g.paint = TITLE_COLOR
    g.drawString(TITLE, textLeft, y + titleMetrics.ascent)
    y += titleMetrics.height + 28

    g.font = bodyFont
    g.paint = BODY_COLOR
    for (line in TAGLINE) {
        g.drawString(line, textLeft, y + bodyMetrics.ascent)
        y += bodyMetrics.height
    }

    y += 36
    g.font = footerFont
    g.paint = FOOTER_COLOR
    g.drawString(FOOTER, textLeft, y + footerMetrics.ascent)

    g.dispose()
    return image
}
