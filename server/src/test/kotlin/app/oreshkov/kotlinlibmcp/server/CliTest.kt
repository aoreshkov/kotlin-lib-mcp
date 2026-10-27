package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.testing.GoldenFiles
import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
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
     */
    @Test
    fun interruptReturnsOnlyOnceTheServerHasClosed() {
        val port = ServerSocket(0).use { it.localPort }
        val cacheDir = Files.createTempDirectory("cli-test-cache")
        val io = RecordingTerminalIo()
        var result: RunResult? = null
        val program = thread(name = "cli-test-server") {
            result = Cli.program(::serve).record(
                "--transport", "http", "--port", "$port", "--cache-dir", "$cacheDir", "--tasks",
                io = io,
            )
        }

        awaitListening(port)
        assertTrue(io.interrupt(), "serve must register an interrupt action")
        // Checked the moment the action returns: the JVM would halt here.
        assertFalse(listening(port), "the interrupt action returned before the http engine had stopped")
        program.join(SHUTDOWN_GRACE_SECONDS * 1_000)
        assertFalse(program.isAlive, "serve did not return after the interrupt")

        val run = result ?: fail("the program did not finish")
        assertEquals(ExitStatus.INTERRUPTED, run.status, run.stderr)
        assertEquals("", run.stdout, "stdout belongs to the transport")
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
