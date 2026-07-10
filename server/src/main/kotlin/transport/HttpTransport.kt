package app.oreshkov.kotlinlibmcp.server.transport

import co.touchlab.kermit.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

/**
 * Runs [server] over the MCP Streamable HTTP transport on [port] and blocks until shutdown.
 * The SDK's DNS-rebinding protection is left at its (secure) default, which only admits
 * requests whose Host/Origin resolve to localhost — connect via `http://127.0.0.1:port/mcp`.
 */
fun runHttpServer(server: Server, port: Int) {
    Logger.withTag("HttpTransport").i { "MCP Streamable HTTP endpoint on http://127.0.0.1:$port/mcp" }
    embeddedServer(CIO, port = port) {
        mcpStreamableHttp { server }
    }.start(wait = true)
}
