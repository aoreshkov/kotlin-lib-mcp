package app.oreshkov.kotlinlibmcp.tools

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The committed icon assets are what [GLYPHS] draws. Nothing else regenerates them — the build
 * only ever reads the committed files — so a glyph edited without `./gradlew :tools:generateIcons`,
 * or an asset replaced by hand, would otherwise ship unnoticed.
 */
class GenerateIconsTest {

    private val pngDir = File(assertNotNull(System.getProperty("icons.pngDir"), "icons.pngDir"))
    private val svgFile = File(assertNotNull(System.getProperty("icons.svgFile"), "icons.svgFile"))

    @Test
    fun everyCommittedPngHasAGlyphAndEveryGlyphItsPngs() {
        val drawn = GLYPHS.flatMap { glyph -> glyph.sizes.map { pngName(glyph, it) } }.toSortedSet()
        val committed = pngDir.list { _, name -> name.endsWith(".png") }.orEmpty().toSortedSet()
        assertEquals(drawn, committed, "PNGs in $pngDir vs what the generator draws")
    }

    @Test
    fun everyCommittedPngMatchesItsGlyph() {
        val mismatches = GLYPHS.flatMap { glyph ->
            glyph.sizes.mapNotNull { size ->
                val file = pngDir.resolve(pngName(glyph, size))
                if (!file.exists()) return@mapNotNull null // the set test above reports it
                val committed = assertNotNull(ImageIO.read(file), "$file: undecodable")
                mismatch(render(glyph, size), committed)?.let { "${file.name}: $it" }
            }
        }
        assertTrue(mismatches.isEmpty(), "${mismatches.joinToString("\n")}\n$REGENERATE")
    }

    @Test
    fun theBrandSvgIsTheServerGlyph() {
        // Exact, not tolerant: this is text, and `.gitattributes` checks it out with LF everywhere.
        val expected = svg(GLYPHS.single { it.name == BRAND_GLYPH })
        assertEquals(expected, svgFile.readText(), "$svgFile\n$REGENERATE")
    }

    /** `null` when every channel of every pixel is within [TOLERANCE], else what differs. */
    private fun mismatch(expected: BufferedImage, actual: BufferedImage): String? {
        if (expected.width != actual.width || expected.height != actual.height) {
            return "${actual.width}x${actual.height}, expected ${expected.width}x${expected.height}"
        }
        var worst = 0
        var worstAt = ""
        var over = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val delta = channelDelta(expected.getRGB(x, y), actual.getRGB(x, y))
            if (delta > TOLERANCE) over++
            if (delta > worst) {
                worst = delta
                worstAt = "($x,$y)"
            }
        }
        return if (over == 0) null else "$over pixel(s) off by more than $TOLERANCE, worst $worst at $worstAt"
    }

    /**
     * The largest per-channel difference of the premultiplied pixels — what each contributes once
     * composited. Unpremultiplied colour is unstable where alpha is near 0: the tile's anti-aliased
     * rim going from alpha 0 to 1 jumps from black to brand purple, an invisible change that would
     * read as a difference of 255.
     */
    private fun channelDelta(a: Int, b: Int): Int =
        (0 until 32 step 8).maxOf { shift -> abs(premultiplied(a, shift) - premultiplied(b, shift)) }

    private fun premultiplied(argb: Int, shift: Int): Int {
        val alpha = argb ushr 24
        val channel = argb ushr shift and 0xFF
        return if (shift == 24) alpha else (channel * alpha + 127) / 255
    }

    private companion object {
        /**
         * Per premultiplied channel, out of 255. Anti-aliased edge coverage is what legitimately
         * differs between AWT rasterizers. Measured when this was written: Temurin 21 reproduces
         * Corretto 25's PNGs exactly, and re-tuning Marlin's subpixel grid
         * (`-Dsun.java2d.renderer.subPixel_log2_X/_Y` from 8/3 to anywhere in 3..8 / 3..4) moves no
         * channel by more than 12. Nudging a whole glyph by half a grid unit moves one by at least
         * 32, by a full unit at least 65.
         */
        const val TOLERANCE = 24

        const val REGENERATE = "Run ./gradlew :tools:generateIcons and commit the result."
    }
}
