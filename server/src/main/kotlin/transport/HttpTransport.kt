package app.oreshkov.kotlinlibmcp.server.transport

import co.touchlab.kermit.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

/** The SDK's DNS-rebinding protection default: only these Host values are admitted. */
private val LOCALHOST_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

/**
 * Runs [server] over the MCP Streamable HTTP transport on [port] and blocks until shutdown.
 *
 * With no [allowedHosts]/[allowedOrigins] the SDK's DNS-rebinding protection stays at its
 * (secure) default, which only admits requests whose Host/Origin resolve to localhost —
 * connect via `http://127.0.0.1:port/mcp`. Passing extra hosts *appends* to the localhost
 * defaults (the SDK would otherwise replace them); comparison is hostname-only, so entries
 * need no port. The SDK also caps POST bodies (4 MiB default) and supports an `eventStore`
 * for SSE resumability; both are left at their defaults here.
 */
fun runHttpServer(
    server: Server,
    port: Int,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
) {
    val log = Logger.withTag("HttpTransport")
    log.i { "MCP Streamable HTTP endpoint on http://127.0.0.1:$port/mcp" }
    if (allowedHosts.isNotEmpty()) log.i { "Additionally accepting Host: $allowedHosts" }
    if (allowedOrigins.isNotEmpty()) log.i { "Additionally accepting Origin: $allowedOrigins" }
    embeddedServer(CIO, port = port) {
        mcpStreamableHttp(
            allowedHosts = (LOCALHOST_HOSTS + allowedHosts).takeIf { allowedHosts.isNotEmpty() },
            allowedOrigins = (LOCALHOST_HOSTS + allowedOrigins).takeIf { allowedOrigins.isNotEmpty() },
        ) { server }
    }.start(wait = true)
}
