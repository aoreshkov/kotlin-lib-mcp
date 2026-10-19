package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.onInterrupt
import app.oreshkov.clikit.runAndExit
import app.oreshkov.kotlinlibmcp.server.transport.runHttpServer
import app.oreshkov.kotlinlibmcp.server.transport.runStdioServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * How long a SIGTERM or Ctrl-C waits for the server to stop and [McpServerHandle.close] to finish:
 * the JVM halts as soon as the interrupt hook returns. Well under Docker's 10 s `stop` grace, and
 * under the wait an MCP stdio client allows before following SIGTERM with SIGKILL.
 */
internal const val SHUTDOWN_GRACE_SECONDS = 5L

/**
 * `runAndExit` ends the process with the handler's status rather than returning from `main`. That
 * exit is load-bearing: the Analysis API leaves a non-daemon IntelliJ pooled thread behind, so
 * returning would keep the JVM (and a `docker run --rm` container) alive after the first library
 * analyzed, while MCP's stdio shutdown expects the server to exit once the client closes its stdin.
 */
fun main(args: Array<String>): Unit = Cli.program(::serve).runAndExit(args)

/**
 * Runs the server until the transport ends (stdio: the client closes stdin) or the process is
 * interrupted. Writes nothing to stdout itself: see [Cli].
 *
 * On SIGTERM or Ctrl-C the interrupt action cancels the run and waits for the `finally` below,
 * which comes after `close()`, so tasks are persisted and buffered spans flushed before the JVM halts.
 * Waiting for the serving coroutine alone would not do: `close()` runs after it, on this thread.
 * The action is registered around `create` and `close()` too, so an interrupt at any point stops
 * the same way.
 */
internal fun serve(options: CliOptions, context: CommandContext): Int {
    val stopped = CountDownLatch(1)
    return try {
        runBlocking {
            val run = coroutineContext.job
            context.onInterrupt {
                run.cancel()
                stopped.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
            }.use {
                McpServerFactory.create(options.config).use { handle -> runTransport(options, handle) }
            }
        }
        ExitStatus.OK
    } catch (_: CancellationException) {
        // Interrupted, and closed by now. The JVM is already shutting down, so the status it exits
        // with is the platform's (143 for SIGTERM, 130 for SIGINT), whatever this answers.
        ExitStatus.INTERRUPTED
    } catch (e: Throwable) {
        // Logged rather than left to cli-kit, whose bug report points at a --verbose this server lacks.
        LoggerFactory.getLogger("Main").error("server failed", e)
        ExitStatus.SOFTWARE
    } finally {
        stopped.countDown()
    }
}

private suspend fun runTransport(options: CliOptions, handle: McpServerHandle) {
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
