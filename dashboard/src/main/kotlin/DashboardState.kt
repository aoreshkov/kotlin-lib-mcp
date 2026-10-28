package app.oreshkov.kotlinlibmcp.dashboard

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.server.HTTP_TRANSPORT
import app.oreshkov.kotlinlibmcp.server.McpServerFactory
import app.oreshkov.kotlinlibmcp.server.McpServerHandle
import app.oreshkov.kotlinlibmcp.server.ServerConfig
import app.oreshkov.kotlinlibmcp.server.transport.LOOPBACK_HOST
import app.oreshkov.kotlinlibmcp.server.transport.createHttpEngine
import co.touchlab.kermit.Logger
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The in-process MCP server's lifecycle state, rendered by the control bar. */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data class Starting(val port: Int) : ServerStatus
    data class Running(val endpoint: String) : ServerStatus
    data object Stopping : ServerStatus
    /** The last start failed — typically a port already in use. Startable again, like [Stopped]. */
    data class Failed(val message: String) : ServerStatus
}

/** What the cache browser shows, published as one value so the count and the size always agree. */
data class CacheSnapshot(val entries: List<LibraryCoordinate> = emptyList(), val sizeBytes: Long = 0)

/**
 * View-model of the dashboard: `StateFlow`s the UI collects plus the actions it triggers. No action
 * blocks or throws on the caller's thread — the UI thread — and none lets a failure escape: each
 * runs on [scope] over [io] and reports through a flow and the log. No fetch/analyze logic lives
 * here; everything delegates to the shared [McpServerHandle] built by `McpServerFactory`.
 *
 * The embedded server runs over Streamable HTTP: a windowed app owns its stdin/stdout, so stdio
 * (where the protocol IS stdout) is only meaningful for the headless `server` binary. That is also
 * why [handle] is built for [HTTP_TRANSPORT]: its clients are network clients, so `fetch_library`
 * must not tell them local paths, nor `guarded` reveal internal error messages.
 *
 * [cache] is [handle]'s cache unless a test substitutes one.
 */
class DashboardState(
    private val scope: CoroutineScope,
    private val handle: McpServerHandle = McpServerFactory.create(ServerConfig(transport = HTTP_TRANSPORT)),
    private val cache: LibraryCache = handle.cache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val log = Logger.withTag("Dashboard")

    /** Serializes start, stop and shutdown, so two clicks can never build two engines. */
    private val engineLock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null // guarded by engineLock

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _prewarmStatus = MutableStateFlow("")
    val prewarmStatus: StateFlow<String> = _prewarmStatus.asStateFlow()

    private val _cache = MutableStateFlow(CacheSnapshot())
    val cacheSnapshot: StateFlow<CacheSnapshot> = _cache.asStateFlow()

    /**
     * Refresh requests, drained by one coroutine. A single consumer means listings are taken and
     * published strictly one after another, so an older listing can never land on top of a newer
     * one; conflation means a burst of requests costs one extra pass, which still starts after the
     * last of them.
     */
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(io) {
            refreshRequests.consumeEach {
                attempt("Reading the cache") {
                    _cache.value = CacheSnapshot(cache.list().sortedBy(LibraryCoordinate::toString), cache.size())
                }
            }
        }
        refreshCache()
    }

    /**
     * Binds the server to loopback [port] off the UI thread. A bind failure — the port is taken, by
     * the headless server on its default 3000 for one — ends in [ServerStatus.Failed], not a throw.
     */
    fun startServer(port: Int): Job = scope.launch(io) {
        engineLock.withLock {
            if (engine != null) return@withLock
            _serverStatus.value = ServerStatus.Starting(port)
            val candidate = createHttpEngine(handle.server, port)
            try {
                // Blocking, on purpose: unlike `startSuspend` it cannot be interrupted between the
                // bind and the return, which would leave a live engine nobody holds.
                candidate.start(wait = false)
            } catch (e: Exception) {
                // No re-throwing `CancellationException` arm, unlike everywhere else: CIO reports a
                // failed bind *as* one (a `JobCancellationException` caused by the `BindException`),
                // and re-throwing it would end this job silently with the status stuck at Starting.
                // `start` is blocking, so it is never this coroutine's own cancellation.
                val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it !is CancellationException } ?: e
                log.w(cause) { "Could not start the in-process MCP server on port $port" }
                candidate.stop(0, 0) // Ktor created the application before the bind failed
                _serverStatus.value = ServerStatus.Failed("Port $port: ${cause.message ?: cause::class.simpleName}")
                return@withLock
            }
            engine = candidate
            val endpoint = "http://$LOOPBACK_HOST:$port/mcp"
            _serverStatus.value = ServerStatus.Running(endpoint)
            log.i { "In-process MCP server listening on $endpoint" }
        }
    }

    fun stopServer(): Job = scope.launch(io) { engineLock.withLock { stopEngine() } }

    /** Caller holds [engineLock]. Bounded: an open SSE stream can hold the engine only until the timeout. */
    private fun stopEngine() {
        val running = engine ?: return
        _serverStatus.value = ServerStatus.Stopping
        running.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
        engine = null
        _serverStatus.value = ServerStatus.Stopped
        log.i { "In-process MCP server stopped" }
    }

    /** Pre-warms [coordinateText] through the same fetch→analyze→cache path `fetch_library` uses. */
    fun prewarm(coordinateText: String) {
        val coordinate = LibraryCoordinate.parseOrNull(coordinateText.trim())
        if (coordinate == null) {
            _prewarmStatus.value = "Invalid coordinate (expected group:artifact:version)"
            return
        }
        _prewarmStatus.value = "Fetching $coordinate …"
        scope.launch(io) {
            try {
                val summary = handle.service.fetchLibrary(coordinate)
                _prewarmStatus.value = "OK: ${summary.sourceFileCount} files, ${summary.packageCount} packages" +
                    if (summary.fromCache) " (from cache)" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Pre-warm of $coordinate failed" }
                _prewarmStatus.value = "Failed: ${e.message}"
            } finally {
                refreshCache()
            }
        }
    }

    fun refreshCache() {
        refreshRequests.trySend(Unit)
    }

    fun clear(coordinate: LibraryCoordinate): Job = scope.launch(io) {
        try {
            attempt("Clearing $coordinate") { cache.clear(coordinate) }
        } finally {
            refreshCache()
        }
    }

    /** Clears every entry it can: one that fails (a file Windows still has open) does not stop the rest. */
    fun clearAll(): Job = scope.launch(io) {
        try {
            attempt("Listing the cache") { cache.list() }?.forEach { coordinate ->
                attempt("Clearing $coordinate") { cache.clear(coordinate) }
            }
        } finally {
            refreshCache()
        }
    }

    /** Stops the server, waiting for it, then closes [handle]. Call once, off the UI thread. */
    suspend fun shutdown() {
        withContext(io) { engineLock.withLock { stopEngine() } }
        refreshRequests.close()
        handle.close()
    }

    /** Runs [block], logging an ordinary failure as [what] and returning `null`; cancellation still propagates. */
    private inline fun <T> attempt(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "$what failed" }
        null
    }

    private companion object {
        /** Shorter than the headless server's: nobody is waiting on a dashboard's in-flight call. */
        const val STOP_GRACE_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
