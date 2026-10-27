package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.Constraint
import app.oreshkov.clikit.Converters
import app.oreshkov.clikit.EnvironmentVariable
import app.oreshkov.clikit.Example
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.ExperimentalCliKitApi
import app.oreshkov.clikit.Option
import app.oreshkov.clikit.OptionValues
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.path
import app.oreshkov.kotlinlibmcp.server.transport.LOOPBACK_HOST
import java.nio.file.Path

internal const val DEFAULT_PORT = 3000

internal enum class TransportKind { STDIO, HTTP }

internal data class CliOptions(
    val transport: TransportKind = TransportKind.STDIO,
    val port: Int = DEFAULT_PORT,
    val host: String = LOOPBACK_HOST,
    val allowedHosts: List<String> = emptyList(),
    val allowedOrigins: List<String> = emptyList(),
    val config: ServerConfig = ServerConfig(),
)

/**
 * The server's command line, declared once: the same handles drive parsing and `--help`, and the
 * help page is pinned by a golden file (`CliTest`), so neither can drift from the other.
 *
 * **stdout belongs to the transport.** cli-kit writes there only for `--help` and `--version`, both
 * of which end the process before a transport starts. A handler must never write through
 * [CommandContext.output] or [CommandContext.io]'s `out`, or it corrupts the stdio protocol stream.
 */
internal object Cli {
    val transport: Option.Single<TransportKind> = Option.Single(
        "--transport",
        Converters.enum(TransportKind.entries),
        valueName = "name",
        help = "Transport to run",
        defaultDisplay = "stdio",
    )
    val port: Option.Single<Int> = Option.Single(
        "--port",
        Converters.intRange(1..65535),
        valueName = "int",
        help = "Port for the http transport",
        defaultDisplay = "$DEFAULT_PORT",
    )
    val host: Option.Single<String> = Option.Single(
        "--host",
        Converters.string,
        valueName = "addr",
        help = "Interface the http transport binds, loopback only by default.\n" +
            "Widen it (e.g. 0.0.0.0) only behind an authenticating proxy.",
        defaultDisplay = LOOPBACK_HOST,
    )
    val allowedHost: Option.Repeated<String> = Option.Repeated(
        "--allowed-host",
        Converters.string,
        valueName = "host",
        help = "Extra Host header the http transport accepts; repeatable. Without it, DNS-rebinding " +
            "protection admits localhost only.",
    )
    val allowedOrigin: Option.Repeated<String> = Option.Repeated(
        "--allowed-origin",
        Converters.string,
        valueName = "url",
        help = "Extra Origin the http transport accepts; repeatable",
    )
    val cacheDir: Option.Single<Path> = Option.Single(
        "--cache-dir",
        Converters.path,
        valueName = "path",
        help = "Cache directory",
        defaultDisplay = "OS cache dir + /kotlin-lib-mcp",
    )
    val repo: Option.Repeated<String> = Option.Repeated(
        "--repo",
        Converters.string,
        valueName = "url",
        help = "Extra Maven repository, besides Maven Central; repeatable",
    )
    val maxConcurrentFetches: Option.Single<Int> = Option.Single(
        "--max-concurrent-fetches",
        Converters.positiveInt,
        valueName = "int",
        help = "Libraries that may download and analyze at once; further fetch_library calls queue",
        defaultDisplay = "${LibraryService.DEFAULT_MAX_CONCURRENT_FETCHES}",
    )
    val forwardLogs: Option.Flag = Option.Flag(
        "--forward-logs-to-client",
        help = "Mirror logs to MCP clients via the (deprecated) logging capability; stdio only. Off " +
            "by default: logs go to stderr only, which the spec blesses for all stdio logging.",
    )
    val otel: Option.Flag = Option.Flag(
        "--otel",
        help = "Export traces for every MCP request over OTLP/HTTP. Off by default. Configured " +
            "entirely by the standard OTEL_* environment variables; defaults to protocol " +
            "http/protobuf and endpoint http://localhost:4318.",
    )
    val tasks: Option.Flag = Option.Flag(
        "--tasks",
        help = "Accept task-augmented tools/call (SEP-1686) for fetch_library, and answer " +
            "tasks/get, tasks/result, tasks/list and tasks/cancel. Off by default; works on both " +
            "transports. Records are stored under <cache-dir>/tasks and survive a restart; each " +
            "session sees only its own in tasks/list, and a recovered task is reachable by its exact " +
            "task id. See the README before exposing this beyond loopback.",
    )

    /** The http-only options: given with stdio they would be ignored, so they are a usage error instead. */
    private val httpOnly: List<Option> = listOf(port, host, allowedHost, allowedOrigin)

    // RequiresValue is cli-kit's one experimental API we use; opted into here only, not module-wide.
    @OptIn(ExperimentalCliKitApi::class)
    val root: CommandSpec = CommandSpec(
        "server",
        help = "kotlin-lib-mcp — MCP server exposing the sources of Maven-published Kotlin/Java libraries.",
        options = listOf(
            transport, port, host, allowedHost, allowedOrigin, cacheDir, repo, maxConcurrentFetches,
            forwardLogs, otel, tasks,
        ),
        examples = listOf(
            Example("server --transport stdio", "Serve one client over stdin/stdout (the default)"),
            Example("server --transport http --port 3000", "Endpoint: http://127.0.0.1:3000/mcp"),
            Example(
                "server --transport http --allowed-host mcp.example.com --allowed-origin https://mcp.example.com",
                "Also accept a reverse proxy's Host and Origin",
            ),
            Example(
                "server --cache-dir /tmp/klm --repo https://maven.google.com",
                "Cache elsewhere, and also search Google's Maven repository",
            ),
            Example("server --tasks", "Accept task-augmented fetch_library calls"),
            Example(
                "OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 server --otel",
                "Export a trace span per MCP request",
            ),
        ),
        epilogue = "Documentation: https://github.com/aoreshkov/kotlin-lib-mcp",
        constraints = httpOnly.map { Constraint.RequiresValue(it, transport, "http") },
        environment = listOf(
            EnvironmentVariable("OTEL_EXPORTER_OTLP_ENDPOINT", "With --otel: base endpoint; '/v1/traces' is appended"),
            EnvironmentVariable(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                "With --otel: per-signal endpoint, used as is, so spell out '/v1/traces'",
            ),
            EnvironmentVariable("OTEL_EXPORTER_OTLP_HEADERS", "With --otel: e.g. 'api-key=...' for a hosted collector"),
            EnvironmentVariable("OTEL_SERVICE_NAME", "With --otel: defaults to '$SERVER_NAME'"),
            EnvironmentVariable("OTEL_RESOURCE_ATTRIBUTES", "With --otel: e.g. 'deployment.environment=staging'"),
        ),
    )

    /** The parsed command line, with each omitted option at its default. */
    fun options(values: OptionValues): CliOptions {
        val transportKind = values[transport] ?: TransportKind.STDIO
        val defaults = ServerConfig()
        return CliOptions(
            transport = transportKind,
            port = values[port] ?: DEFAULT_PORT,
            host = values[host] ?: LOOPBACK_HOST,
            allowedHosts = values[allowedHost],
            allowedOrigins = values[allowedOrigin],
            config = defaults.copy(
                cacheDir = values[cacheDir] ?: defaults.cacheDir,
                repos = values[repo],
                maxConcurrentFetches = values[maxConcurrentFetches] ?: defaults.maxConcurrentFetches,
                forwardLogsToClient = values[forwardLogs],
                otel = values[otel],
                tasks = values[tasks],
                // The span attribute's name for the transport, from the final parsed value.
                transport = transportKind.name.lowercase(),
            ),
        )
    }

    /** The runnable program; [serve] is a parameter so tests run the real parser without starting a server. */
    fun program(serve: (CliOptions, CommandContext) -> Int): Program = Program(
        root,
        "$SERVER_NAME ${ServerVersion.value}",
        mapOf(root to CommandHandler { context -> serve(checked(options(context.values)), context) }),
    )

    /**
     * The rules a [Constraint] cannot state, as usage errors all the same. `RequiresValue` treats an
     * absent `--transport` as no match, so "requires stdio" would refuse the default launch.
     *
     * Log forwarding is stdio-only because the forwarder sends every record to every session: over
     * HTTP, one client would read another's task ids and the exception details `guarded` withholds.
     */
    private fun checked(options: CliOptions): CliOptions {
        if (options.transport == TransportKind.HTTP && options.config.forwardLogsToClient) {
            throw CommandFailure(
                ExitStatus.USAGE,
                "$forwardLogs requires --transport stdio: over http it would send every session's logs to every client",
            )
        }
        return options
    }
}
