package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.server.transport.LOOPBACK_HOST
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
      --host <addr>            Interface the http transport binds (default: $LOOPBACK_HOST, loopback
                               only). Widen (e.g. 0.0.0.0) only behind an authenticating proxy.
      --allowed-host <host>    Extra Host header the http transport accepts; repeatable
                               (default: localhost only, via DNS-rebinding protection)
      --allowed-origin <url>   Extra Origin the http transport accepts; repeatable
      --cache-dir <path>       Cache directory (default: OS cache dir + /kotlin-lib-mcp)
      --repo <url>             Extra Maven repository; repeatable (default: Maven Central)
      --forward-logs-to-client Mirror logs to MCP clients via the (deprecated) `logging` capability.
                               Off by default: logs go to stderr only, which the spec blesses for all
                               stdio logging.
      --otel                   Export traces for every MCP request over OTLP/HTTP. Off by default.
                               Configured entirely by the standard OTEL_* environment variables;
                               defaults to protocol http/protobuf and endpoint http://localhost:4318.
      --tasks                  Accept task-augmented tools/call (SEP-1686) for fetch_library, and
                               answer tasks/get, tasks/result, tasks/list and tasks/cancel. Off by
                               default. Works on both transports. Records are stored under
                               <cache-dir>/tasks and survive a restart; each session sees only its
                               own in tasks/list, and a recovered task is reachable by its exact
                               task id. See the README before exposing this beyond loopback.
      --help                   Show this help and exit

    Telemetry (--otel):
      OTEL_EXPORTER_OTLP_ENDPOINT     Base endpoint; '/v1/traces' is appended automatically.
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT  Per-signal endpoint, used AS-IS — spell out '/v1/traces'.
      OTEL_EXPORTER_OTLP_HEADERS      e.g. 'api-key=...' for a hosted collector.
      OTEL_SERVICE_NAME               Defaults to '$SERVER_NAME'.
      OTEL_RESOURCE_ATTRIBUTES        e.g. 'deployment.environment=staging'.

    Examples:
      server --transport stdio
      server --transport http --port 3000     # endpoint: http://127.0.0.1:3000/mcp
      server --transport http --allowed-host mcp.example.com:3000 --allowed-origin https://mcp.example.com
      server --transport stdio --cache-dir /tmp/klm --repo https://maven.google.com
      server --transport stdio --tasks
      server --transport http --port 3000 --tasks
      OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 server --transport stdio --otel
""".trimIndent()

private enum class TransportKind { STDIO, HTTP }

private data class CliOptions(
    val transport: TransportKind = TransportKind.STDIO,
    val port: Int = DEFAULT_PORT,
    val host: String = LOOPBACK_HOST,
    val allowedHosts: List<String> = emptyList(),
    val allowedOrigins: List<String> = emptyList(),
    val config: ServerConfig = ServerConfig(),
)

/** Tiny hand-rolled parser — seven flags don't warrant a dependency. [fail]s on anything unknown. */
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
            "--host" -> options = options.copy(host = value(arg))
            "--allowed-host" -> options =
                options.copy(allowedHosts = options.allowedHosts + value(arg))
            "--allowed-origin" -> options =
                options.copy(allowedOrigins = options.allowedOrigins + value(arg))
            "--cache-dir" -> options =
                options.copy(config = options.config.copy(cacheDir = Path.of(value(arg))))
            "--repo" -> options =
                options.copy(config = options.config.copy(repos = options.config.repos + value(arg)))
            "--forward-logs-to-client" -> options =
                options.copy(config = options.config.copy(forwardLogsToClient = true))
            "--otel" -> options = options.copy(config = options.config.copy(otel = true))
            "--tasks" -> options = options.copy(config = options.config.copy(tasks = true))
            else -> fail("Unknown option '$arg'")
        }
        i++
    }
    // Resolved last: --transport may appear after --otel, and the span attribute needs the final value.
    return options.copy(
        config = options.config.copy(transport = options.transport.name.lowercase())
    )
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
                TransportKind.STDIO -> runStdioServer(handle.server, handle.taskStore)
                TransportKind.HTTP -> runHttpServer(
                    server = handle.server,
                    port = options.port,
                    host = options.host,
                    allowedHosts = options.allowedHosts,
                    allowedOrigins = options.allowedOrigins,
                    taskStore = handle.taskStore,
                )
            }
        }
    }
}
