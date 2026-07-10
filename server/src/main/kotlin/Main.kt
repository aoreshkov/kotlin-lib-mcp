package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.server.transport.runHttpServer
import app.oreshkov.kotlinlibmcp.server.transport.runStdioServer
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val DEFAULT_PORT = 3000

private val USAGE = """
    kotlin-lib-mcp — MCP server exposing the sources of Maven-published Kotlin/Java libraries.

    Usage: server [options]

    Options:
      --transport stdio|http   Transport to run (default: stdio)
      --port <int>             Port for the http transport (default: $DEFAULT_PORT)
      --cache-dir <path>       Cache directory (default: OS cache dir + /kotlin-lib-mcp)
      --repo <url>             Extra Maven repository; repeatable (default: Maven Central)
      --help                   Show this help and exit

    Examples:
      server --transport stdio
      server --transport http --port 3000     # endpoint: http://127.0.0.1:3000/mcp
      server --transport stdio --cache-dir /tmp/klm --repo https://maven.google.com
""".trimIndent()

private enum class TransportKind { STDIO, HTTP }

private data class CliOptions(
    val transport: TransportKind = TransportKind.STDIO,
    val port: Int = DEFAULT_PORT,
    val config: ServerConfig = ServerConfig(),
)

/** Tiny hand-rolled parser — five flags don't warrant a dependency. [fail]s on anything unknown. */
private fun parseArgs(args: Array<String>): CliOptions {
    var options = CliOptions()
    var i = 0

    fun value(flag: String): String {
        if (i + 1 >= args.size) fail("Missing value for $flag")
        return args[++i]
    }

    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                println(USAGE)
                exitProcess(0)
            }
            "--transport" -> options = when (val t = value(arg)) {
                "stdio" -> options.copy(transport = TransportKind.STDIO)
                "http" -> options.copy(transport = TransportKind.HTTP)
                else -> fail("Unknown transport '$t' (expected stdio or http)")
            }
            "--port" -> {
                val port = value(arg).toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: fail("Invalid --port (expected 1-65535)")
                options = options.copy(port = port)
            }
            "--cache-dir" -> options =
                options.copy(config = options.config.copy(cacheDir = Path.of(value(arg))))
            "--repo" -> options =
                options.copy(config = options.config.copy(repos = options.config.repos + value(arg)))
            else -> fail("Unknown option '$arg'")
        }
        i++
    }
    return options
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message\n\n$USAGE")
    exitProcess(2)
}

fun main(args: Array<String>) {
    val options = parseArgs(args)
    runBlocking {
        McpServerFactory.create(options.config).use { handle ->
            when (options.transport) {
                TransportKind.STDIO -> runStdioServer(handle.server)
                TransportKind.HTTP -> runHttpServer(handle.server, options.port)
            }
        }
    }
}
