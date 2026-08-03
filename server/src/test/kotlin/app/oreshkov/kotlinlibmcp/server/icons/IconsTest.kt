package app.oreshkov.kotlinlibmcp.server.icons

import app.oreshkov.kotlinlibmcp.server.SERVER_NAME
import app.oreshkov.kotlinlibmcp.server.serverInfo
import io.modelcontextprotocol.kotlin.sdk.types.Icon
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SEP-973 icon assets and what the spec requires of them. [Glyph.icons] degrades to `null` when
 * a PNG is missing so a packaging slip cannot stop the server from starting — which is exactly why
 * that slip has to fail here instead.
 */
class IconsTest {

    /**
     * Inlined base64 rides in every `tools/list`, so a swapped-in asset that is an order of
     * magnitude larger should trip a test rather than quietly bloat every listing. Current glyphs
     * are ~600 B (~800 B encoded); the 96px brand mark is the largest.
     */
    private val maxEncodedBytes = 4 * 1024

    private val sizeHint = Regex("""\d+x\d+""")

    /** The PNG signature clients are told to sniff instead of trusting `mimeType`. */
    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun everyGlyphResolvesToItsAssets() {
        Glyph.entries.forEach { glyph ->
            val icons = assertNotNull(glyph.icons, "${glyph.name}: icon assets missing from resources")
            assertTrue(icons.isNotEmpty(), "${glyph.name}: empty icon list")
        }
        // Only the server mark ships a second size; the rest are single 48px tiles.
        assertEquals(2, assertNotNull(Glyph.Server.icons).size)
        Glyph.entries.filter { it != Glyph.Server }.forEach { glyph ->
            assertEquals(1, assertNotNull(glyph.icons).size, "${glyph.name}: unexpected icon count")
        }
    }

    @Test
    fun everyIconIsAnInlinePngDataUri() {
        // The spec requires icon-rendering clients to support image/png and lets `src` be a data:
        // URI — the combination that needs no origin, no fetch and no network.
        allIcons().forEach { (glyph, icon) ->
            assertTrue(
                icon.src.startsWith("data:image/png;base64,"),
                "$glyph: src must be an inline PNG data URI, was '${icon.src.take(40)}…'",
            )
            assertEquals("image/png", icon.mimeType, "$glyph: mimeType")
            // Absent theme means "usable with any theme" — the glyphs are white on a brand tile.
            assertNull(icon.theme, "$glyph: icons should not claim a theme")
        }
    }

    @Test
    fun everyIconDecodesToAPngOfTheSizeItAdvertises() {
        // Clients are told to treat the declared MIME type as advisory and sniff the magic bytes,
        // and to pick an icon by its `sizes` hint — so both must actually match the payload.
        allIcons().forEach { (glyph, icon) ->
            val bytes = Base64.getDecoder().decode(icon.src.substringAfter("base64,"))
            assertContentEquals(pngMagic, bytes.copyOf(4), "$glyph: not a PNG (magic bytes)")

            val hint = assertNotNull(icon.sizes, "$glyph: missing sizes").single()
            assertTrue(sizeHint.matches(hint), "$glyph: '$hint' is not a WxH size hint")

            val image = assertNotNull(ImageIO.read(bytes.inputStream()), "$glyph: undecodable PNG")
            assertEquals("${image.width}x${image.height}", hint, "$glyph: sizes hint vs actual PNG")
        }
    }

    @Test
    fun iconPayloadsStaySmallEnoughToInlineInEveryListing() {
        allIcons().forEach { (glyph, icon) ->
            assertTrue(
                icon.src.length <= maxEncodedBytes,
                "$glyph: ${icon.src.length} B encoded exceeds the ${maxEncodedBytes} B budget",
            )
        }
    }

    @Test
    fun serverInfoCarriesDisplayBranding() {
        val info = serverInfo()
        assertEquals(SERVER_NAME, info.name)
        assertEquals(Glyph.Server.icons, info.icons)
        // Mirrors server.json's registry entry, so a client sees the same identity either way.
        assertEquals("Kotlin & Java Library Sources", info.title)
        assertEquals("https://github.com/aoreshkov/kotlin-lib-mcp", info.websiteUrl)
    }

    private fun allIcons(): List<Pair<String, Icon>> = Glyph.entries.flatMap { glyph ->
        glyph.icons.orEmpty().map { "${glyph.name}${it.sizes?.single()?.let { s -> "@$s" }.orEmpty()}" to it }
    }
}
