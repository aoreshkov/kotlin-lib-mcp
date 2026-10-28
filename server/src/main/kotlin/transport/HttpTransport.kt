package app.oreshkov.kotlinlibmcp.server.transport

import app.oreshkov.kotlinlibmcp.server.tasks.TaskStore
import app.oreshkov.kotlinlibmcp.server.tasks.installTaskHandlersOnEverySession
import co.touchlab.kermit.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** The SDK's DNS-rebinding protection default: only these Host values are admitted. */
private val LOCALHOST_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

/** Loopback interface: the secure default bind address (reachable only from this host). */
public const val LOOPBACK_HOST: String = "127.0.0.1"

/**
 * How often an otherwise-idle SSE stream emits a keep-alive. Comfortably under the 60s idle timeout
 * that nginx, ELB and most corporate proxies default to, without adding meaningful traffic.
 */
private val SSE_HEARTBEAT_PERIOD: Duration = 30.seconds

/**
 * How long a stop lets in-flight requests finish, and how long it waits in all: well inside the
 * interrupt grace `Main.kt` gives the whole shutdown, which still has `close()` to run after this.
 */
private const val STOP_GRACE_MILLIS = 1_000L
private const val STOP_TIMEOUT_MILLIS = 2_000L

/**
 * Builds — but does not start — the Ktor engine serving [server] over the MCP Streamable HTTP
 * transport on [host]:[port]. This is the one place `mcpStreamableHttp` is wired, so the headless
 * server ([runHttpServer]) and the dashboard bind, filter and heartbeat identically.
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
 *
 * An SSE heartbeat *is* configured, because the SDK's default is none: a `fetch_library` that
 * downloads and parses a large library can go minutes without emitting a frame, and idle
 * connections get culled by proxies and load balancers long before that. [SSE_HEARTBEAT_PERIOD]
 * keeps the stream demonstrably alive; the comment events it sends are ignored by SSE clients.
 *
 * Starting the engine binds the socket, so a busy port throws out of `start`/`startSuspend`. Ktor
 * has created the application by then, so a caller that outlives the failure should still `stop` it.
 */
fun createHttpEngine(
    server: Server,
    port: Int,
    host: String = LOOPBACK_HOST,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
): EmbeddedServer<*, *> {
    val log = Logger.withTag("HttpTransport")
    if (host != LOOPBACK_HOST) {
        log.w { "Binding to $host exposes the server beyond loopback — put authentication in front of it." }
    }
    if (allowedHosts.isNotEmpty()) log.i { "Additionally accepting Host: $allowedHosts" }
    if (allowedOrigins.isNotEmpty()) log.i { "Additionally accepting Origin: $allowedOrigins" }
    return embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(
            allowedHosts = (LOCALHOST_HOSTS + allowedHosts).takeIf { allowedHosts.isNotEmpty() },
            allowedOrigins = (LOCALHOST_HOSTS + allowedOrigins).takeIf { allowedOrigins.isNotEmpty() },
            // Non-null opts in: the SDK sends no heartbeat at all by default.
            sseHeartbeatConfig = { period = SSE_HEARTBEAT_PERIOD },
        ) { server }
    }
}

/**
 * Runs [server] over the MCP Streamable HTTP transport ([createHttpEngine]), bound to [host]:[port],
 * and suspends until cancelled, then stops the engine before returning. Cancellation is how the CLI
 * stops it on SIGTERM or Ctrl-C: a blocking `start(wait = true)` would ignore it, leaving the stop
 * to Ktor's own shutdown hook, which races `close()`.
 *
 * When [taskStore] is non-null (`--tasks`), task support is installed on every session this server
 * accepts. Unlike stdio there is no session object here to register on — see
 * [installTaskHandlersOnEverySession].
 */
suspend fun runHttpServer(
    server: Server,
    port: Int,
    host: String = LOOPBACK_HOST,
    allowedHosts: List<String> = emptyList(),
    allowedOrigins: List<String> = emptyList(),
    taskStore: TaskStore? = null,
) {
    Logger.withTag("HttpTransport").i { "MCP Streamable HTTP endpoint on http://$host:$port/mcp" }
    // Before the engine starts, so no connection can be accepted ahead of the hook.
    taskStore?.let { server.installTaskHandlersOnEverySession(it) }
    val engine = createHttpEngine(server, port, host, allowedHosts, allowedOrigins)
    // Not cancellable: Ktor's `startSuspend` is `withContext { start() }` around a blocking start, so
    // a cancel arriving after the socket binds makes it throw although the engine is up — skipping
    // the `finally` below and leaving the engine running past `close()`. A cancel that came during
    // the start is seen by `awaitCancellation` instead. Starting inside the `try` is no fix: CIO's
    // stop joins its lazy server job, which would start an engine that never was.
    try {
        withContext(NonCancellable) { engine.startSuspend(wait = false) }
    } catch (e: CancellationException) {
        // Never our own cancellation, under NonCancellable: CIO reports a failed bind (port in use)
        // as a `JobCancellationException` caused by the `BindException`. Thrown as is, `serve` takes
        // it for Ctrl-C and exits 130 having logged nothing; the cause makes it a logged failure.
        throw generateSequence<Throwable>(e) { it.cause }.firstOrNull { it !is CancellationException } ?: e
    }
    try {
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { engine.stopSuspend(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
    }
}
