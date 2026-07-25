package app.oreshkov.kotlinlibmcp.server.icons

import io.modelcontextprotocol.kotlin.sdk.types.Icon
import java.util.Base64

/*
 * SEP-973 icons for everything this server exposes: the implementation itself, every tool, the
 * prompt, and the library-index resource/template.
 *
 * Two deliberate choices, both from the spec's `icons` section:
 *
 *  - **`data:` URIs, not `https://`.** The spec tells consumers to "verify that icon URIs are from
 *    the same origin as the server" and to fetch them without credentials. A stdio server has no
 *    origin, so any remote URL is third-party by construction — a tracking beacon a strict client
 *    is entitled to refuse. Inlining the bytes also keeps the icons working offline and inside the
 *    container image, with no fetch at all.
 *  - **PNG, not SVG.** Icon-rendering clients MUST support `image/png`; `image/svg+xml` is only a
 *    SHOULD and the spec warns that SVG may carry executable content, so clients may sanitize or
 *    drop it. `assets/icon.svg` stays the vector brand asset (server.json, README); the protocol
 *    ships the format every client can render.
 *
 * The bytes are inline in every `tools/list`, so the glyphs are kept small (~600 B each, ~800 B
 * base64) — see `assets/icons/GenerateIcons.java`, which draws and regenerates them.
 */

/**
 * A glyph shipped under `server/src/main/resources/icons/`, exposed as the `icons` list of the MCP
 * object it belongs to. [sizes] are the pixel sizes the PNG is rasterized at, and become the
 * spec's `WxH` size hints.
 */
internal enum class Glyph(private val sizes: List<Int> = listOf(48)) {
    /** The server's own mark, on `serverInfo`. Also at 96 — connector UIs render it large. */
    Server(listOf(48, 96)),
    Fetch,
    Packages,
    Declarations,
    Signature,
    KDoc,
    Source,
    Search,
    Dependencies,
    Versions,
    Latest,
    Prompt,
    Index,
    ;

    /** Resource path of this glyph at [size], e.g. `/icons/fetch-48.png`. */
    fun resourcePath(size: Int): String = "/icons/${name.lowercase()}-$size.png"

    /**
     * The `icons` value for this glyph, or `null` if any of its PNGs is missing — icons are
     * decoration, and a packaging slip must never stop the server from starting. `IconsTest` fails
     * loudly instead.
     */
    val icons: List<Icon>? by lazy {
        sizes.mapNotNull { size -> load(size) }.takeIf { it.size == sizes.size }
    }

    private fun load(size: Int): Icon? {
        val path = resourcePath(size)
        val bytes = javaClass.getResourceAsStream(path)?.use { it.readBytes() } ?: return null
        return Icon(
            src = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}",
            // Redundant with the data: URI's own media type, and deliberately so: the spec has
            // clients treat the declared type as advisory and verify it against the magic bytes.
            mimeType = "image/png",
            sizes = listOf("${size}x$size"),
            // No `theme`: the glyphs are white-on-brand tiles, legible against light and dark
            // alike, and an absent theme means "usable with any theme".
        )
    }
}
