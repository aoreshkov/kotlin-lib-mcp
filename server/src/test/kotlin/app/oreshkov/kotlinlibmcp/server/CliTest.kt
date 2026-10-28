package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.testing.GoldenFiles
import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import app.oreshkov.kotlinlibmcp.server.tasks.TaskRecordStore
import io.modelcontextprotocol.kotlin.sdk.types.TaskStatus
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The command line, through the real parser and `Program`, in-process. Every run but `--help` and
 * `--version` must leave stdout empty: over stdio it is the protocol channel.
 */
class CliTest {

    private val golden = GoldenFiles(Path.of("src/test/resources/golden"), "golden.update")

    /** Runs the program with a `serve` that records what it was given instead of starting a server. */
    private fun parse(vararg args: String): Pair<RunResult, CliOptions?> {
        var captured: CliOptions? = null
        val result = Cli.program { options, _ ->
            captured = options
            ExitStatus.OK
        }.record(*args)
        return result to captured
    }

    private fun accepted(vararg args: String): CliOptions {
        val (result, options) = parse(*args)
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals("", result.stdout, "stdout belongs to the transport")
        return options ?: fail("serve was not called")
    }

    private fun refused(vararg args: String): String {
        val (result, options) = parse(*args)
        assertEquals(ExitStatus.USAGE, result.status, "${args.toList()} must be a usage error")
        assertNull(options, "serve must not run on a usage error")
        assertEquals("", result.stdout, "a usage error goes to stderr, never the protocol channel")
        return result.stderr
    }

    @Test
    fun helpPageIsPinned() {
        val result = Cli.program { _, _ -> fail("--help must not serve") }
            .record("--help", io = RecordingTerminalIo(width = 100))
        assertEquals(ExitStatus.OK, result.status)
        assertEquals("", result.stderr)
        result.assertPipeSafe()
        val page = GoldenFiles.helpFileName(listOf(Cli.root))
        golden.assertMatches(page, result.stdout)
        golden.assertNoOrphans(setOf(page))
    }

    @Test
    fun versionIsTheBuiltServerVersion() {
        val result = Cli.program { _, _ -> fail("--version must not serve") }.record("--version")
        assertEquals(ExitStatus.OK, result.status)
        assertEquals("$SERVER_NAME ${ServerVersion.value}\n", result.stdout)
    }

    @Test
    fun noArgumentsServesStdioWithEveryDefault() {
        assertEquals(CliOptions(), accepted())
    }

    @Test
    fun everyOptionReachesTheServer() {
        val options = accepted(
            "--transport", "http",
            "--port=8080",
            "--host", "0.0.0.0",
            "--allowed-host", "a.example.com",
            "--allowed-host", "b.example.com",
            "--allowed-origin", "https://a.example.com",
            "--cache-dir", "klm-cache",
            "--repo", "https://maven.google.com",
            "--repo", "https://repo.example.com/maven",
            "--no-maven-central",
            "--max-concurrent-fetches", "4",
            "--otel",
            "--tasks",
        )
        assertEquals(
            CliOptions(
                transport = TransportKind.HTTP,
                port = 8080,
                host = "0.0.0.0",
                allowedHosts = listOf("a.example.com", "b.example.com"),
                allowedOrigins = listOf("https://a.example.com"),
                config = ServerConfig(
                    cacheDir = Path.of("klm-cache"),
                    repos = listOf("https://maven.google.com", "https://repo.example.com/maven"),
                    mavenCentral = false,
                    maxConcurrentFetches = 4,
                    otel = true,
                    tasks = true,
                    transport = "http",
                ),
            ),
            options,
        )
    }

    @Test
    fun transportGivenAfterOtelStillNamesTheSpanAttribute() {
        assertEquals("http", accepted("--otel", "--transport", "http").config.transport)
    }

    @Test
    fun malformedValuesAreUsageErrors() {
        refused("--port", "0")
        refused("--port", "65536")
        refused("--port", "http")
        refused("--max-concurrent-fetches", "0")
        assertContains(refused("--transport", "sse"), "sse")
        refused("--cache-dir")
        assertContains(refused("--bogus"), "--bogus")
    }

    @Test
    fun httpOnlyOptionsAreRefusedWithStdio() {
        for (args in listOf(
            arrayOf("--port", "3000"),
            arrayOf("--transport", "stdio", "--host", "0.0.0.0"),
            arrayOf("--allowed-host", "mcp.example.com"),
            arrayOf("--allowed-origin", "https://mcp.example.com"),
        )) {
            assertContains(refused(*args), "requires --transport http", message = args.toList().toString())
        }
    }

    @Test
    fun logForwardingIsStdioOnly() {
        // Over http the forwarder would send every session's logs to every client.
        assertContains(
            refused("--transport", "http", "--forward-logs-to-client"),
            "--forward-logs-to-client requires --transport stdio",
        )
        assertEquals(true, accepted("--forward-logs-to-client").config.forwardLogsToClient)
        assertEquals(true, accepted("--transport", "stdio", "--forward-logs-to-client").config.forwardLogsToClient)
    }

    /**
     * The real `serve`, over http, stopped as SIGTERM would stop it. The interrupt action must not
     * return before the server has stopped and `close()` has run, because the JVM halts the moment
     * it does; and the handler must have written nothing to stdout.
     *
     * The engine stops in `runHttpServer`'s `finally`, *before* `close()`, so a closed port alone
     * would not show that `close()` ran. A task still in flight does: `close()` is what writes it to
     * disk as `failed`. Its `fetch_library` points at a repository that accepts the connection and
     * never answers, so the task is certain to be running when the interrupt comes, without the
     * test touching the network.
     */
    @Test
    fun interruptReturnsOnlyOnceTheServerHasClosed() {
        val port = ServerSocket(0).use { it.localPort }
        val cacheDir = Files.createTempDirectory("cli-test-cache")
        val io = RecordingTerminalIo()
        var result: RunResult? = null
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { silentRepo ->
            val program = thread(name = "cli-test-server") {
                result = Cli.program(::serve).record(
                    "--transport", "http", "--port", "$port", "--cache-dir", "$cacheDir", "--tasks",
                    "--repo", "http://127.0.0.1:${silentRepo.localPort}/",
                    io = io,
                )
            }

            awaitListening(port)
            val taskId = McpHttpClient(port).startStalledFetchTask()
            assertTrue(io.interrupt(), "serve must register an interrupt action")
            // Checked the moment the action returns: the JVM would halt here.
            assertFalse(listening(port), "the interrupt action returned before the http engine had stopped")
            val record = TaskRecordStore(cacheDir.resolve("tasks")).loadAll().single { it.taskId == taskId }
            assertEquals(TaskStatus.Failed, record.status, "the interrupt action returned before close() had run")
            assertEquals("Server shut down while this task was running", record.statusMessage)

            program.join(SHUTDOWN_GRACE_SECONDS * 1_000)
            assertFalse(program.isAlive, "serve did not return after the interrupt")
        }

        val run = result ?: fail("the program did not finish")
        assertEquals(ExitStatus.INTERRUPTED, run.status, run.stderr)
        assertEquals("", run.stdout, "stdout belongs to the transport")
    }

    /** Just enough of a Streamable HTTP client to leave one task running on the server. */
    private class McpHttpClient(port: Int) {
        private val http = HttpClient.newHttpClient()
        private val endpoint = URI("http://127.0.0.1:$port/mcp")
        private var sessionId: String? = null

        /** Initializes a session and starts a task-augmented `fetch_library`; returns its task id. */
        fun startStalledFetchTask(): String {
            post(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
                    """"capabilities":{},"clientInfo":{"name":"cli-test","version":"1"}}}""",
            )
            post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            val created = post(
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fetch_library",""" +
                    """"arguments":{"coordinate":"com.example:stalled:1.0.0"},"task":{"ttl":60000}}}""",
            )
            return TASK_ID.find(created)?.groupValues?.get(1) ?: fail("no task was created: $created")
        }

        /**
         * Sends one message and returns the first line that carries a JSON-RPC message (the SSE
         * `data:` line, or the whole body when the server answers in plain JSON), or "" for a
         * notification's 202. Read line by line, so a stream the server keeps open cannot hang it.
         */
        private fun post(body: String): String {
            val request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply { sessionId?.let { header("Mcp-Session-Id", it).header("MCP-Protocol-Version", "2025-11-25") } }
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
            assertTrue(response.statusCode() in 200..299, "HTTP ${response.statusCode()} for $body")
            response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
            return response.body().use { lines ->
                lines.map { it.removePrefix("data:").trim() }.filter { it.startsWith("{") }.findFirst().orElse("")
            }
        }

        private companion object {
            val TASK_ID = Regex(""""taskId"\s*:\s*"([^"]+)"""")
        }
    }

    private fun awaitListening(port: Int) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!listening(port)) {
            if (System.nanoTime() > deadline) fail("the server never listened on $port")
            Thread.sleep(50)
        }
    }

    private fun listening(port: Int): Boolean = try {
        Socket("127.0.0.1", port).close()
        true
    } catch (_: java.io.IOException) {
        false
    }
}
