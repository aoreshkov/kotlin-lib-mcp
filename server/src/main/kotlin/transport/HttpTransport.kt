package app.oreshkov.kotlinlibmcp.server.transport

import co.touchlab.kermit.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

/** The SDK's DNS-rebinding protection default: only these Host values are admitted. */
private val LOCALHOST_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

/** Loopback interface: the secure default bind address (reachable only from this host). */
public const val LOOPBACK_HOST: String = "127.0.0.1"

/**
 * Runs [server] over the MCP Streamable HTTP transport, bound to [host]:[port], and blocks until
 * shutdown.
 *
 * [host] defaults to loopback so the socket is unreachable from the network — the Host/Origin
 * allowlist below only filters *headers*, it does not restrict which interface the server listens
 * on, so binding to `0.0.0.0` would expose the endpoint on the LAN. Widen [host] (e.g. `0.0.0.0`)
 * only behind an authenticating reverse proxy.
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
    host: String = LOOPBACK_HOST,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
) {
    val log = Logger.withTag("HttpTransport")
    log.i { "MCP Streamable HTTP endpoint on http://$host:$port/mcp" }
    if (host != LOOPBACK_HOST) {
        log.w { "Binding to $host exposes the server beyond loopback — put authentication in front of it." }
    }
    if (allowedHosts.isNotEmpty()) log.i { "Additionally accepting Host: $allowedHosts" }
    if (allowedOrigins.isNotEmpty()) log.i { "Additionally accepting Origin: $allowedOrigins" }
    embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(
            allowedHosts = (LOCALHOST_HOSTS + allowedHosts).takeIf { allowedHosts.isNotEmpty() },
            allowedOrigins = (LOCALHOST_HOSTS + allowedOrigins).takeIf { allowedOrigins.isNotEmpty() },
        ) { server }
    }.start(wait = true)
}
