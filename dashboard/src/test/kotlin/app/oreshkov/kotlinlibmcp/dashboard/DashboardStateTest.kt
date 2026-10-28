package app.oreshkov.kotlinlibmcp.dashboard

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.server.HTTP_TRANSPORT
import app.oreshkov.kotlinlibmcp.server.McpServerFactory
import app.oreshkov.kotlinlibmcp.server.McpServerHandle
import app.oreshkov.kotlinlibmcp.server.ServerConfig
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.assertContains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * The dashboard's actions run off the UI thread and report rather than throw. Building the real
 * [McpServerHandle] here also pins the classpath hoist: on this module's test runtime classpath
 * without it, `McpServerFactory.create()` dies with `NoSuchMethodError` before any test body runs.
 */
class DashboardStateTest {

    private val cacheDir = Files.createTempDirectory("dashboard-test-cache")
    private val handle: McpServerHandle =
        McpServerFactory.create(ServerConfig(cacheDir = cacheDir, transport = HTTP_TRANSPORT))

    /** Whatever escaped a dashboard coroutine — always expected to be empty. */
    private val escaped = CopyOnWriteArrayList<Throwable>()

    private fun scopeOn(dispatcher: CoroutineDispatcher) =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> escaped += e })

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() {
        handle.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun startingOnABusyPortEndsInFailedInsteadOfThrowing() = runBlocking {
        val scope = scopeOn(Dispatchers.Default)
        ServerSocket(0, 50, LOOPBACK).use { taken ->
            val state = DashboardState(scope, handle)
            withTimeout(TIMEOUT_MILLIS) { state.startServer(taken.localPort).join() }

            val status = assertIs<ServerStatus.Failed>(state.serverStatus.value)
            // The bind error itself, not CIO's `JobCancellationException` wrapper around it.
            assertContains(status.message, "already in use", ignoreCase = true)
            assertTrue(escaped.isEmpty(), "escaped: $escaped")
            state.shutdown()
        }
        scope.cancel()
    }

    @Test
    fun startBindsTheLoopbackPortAndStopReleasesIt() = runBlocking {
        val scope = scopeOn(Dispatchers.Default)
        val port = ServerSocket(0, 50, LOOPBACK).use { it.localPort }
        val state = DashboardState(scope, handle)

        withTimeout(TIMEOUT_MILLIS) { state.startServer(port).join() }
        assertEquals(ServerStatus.Running("http://127.0.0.1:$port/mcp"), state.serverStatus.value)
        Socket(LOOPBACK, port).close()

        withTimeout(TIMEOUT_MILLIS) { state.stopServer().join() }
        assertEquals(ServerStatus.Stopped, state.serverStatus.value)
        assertFailsWith<IOException> { Socket(LOOPBACK, port).close() }

        state.shutdown()
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceUntilIdle
    @Test
    fun aSlowListingNeverLandsOnTopOfANewerOne() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = scopeOn(io)
        val cache = FakeCache(A, B)
        val firstListing = CompletableDeferred<Unit>()
        cache.onList = { call -> if (call == 1) firstListing.await() }
        val state = DashboardState(scope, handle, cache, io)
        runCurrent() // the initial refresh has read [A, B] and is stalled before publishing it

        state.clear(B)
        runCurrent() // B is gone, and a refresh asked for
        firstListing.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(A), state.cacheSnapshot.value.entries)
        assertTrue(escaped.isEmpty(), "escaped: $escaped")
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearAllGoesPastAnEntryThatFailsAndStillRefreshes() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = scopeOn(io)
        val cache = FakeCache(A, B, C)
        cache.failToClear = B
        val state = DashboardState(scope, handle, cache, io)
        advanceUntilIdle()

        state.clearAll()
        advanceUntilIdle()

        assertEquals(listOf(B), state.cacheSnapshot.value.entries)
        assertTrue(escaped.isEmpty(), "escaped: $escaped")
        scope.cancel()
    }

    /** A cache of bare coordinates. [list] snapshots *before* [onList] runs, as a slow disk scan would. */
    private class FakeCache(vararg initial: LibraryCoordinate) : LibraryCache {
        private val entries = initial.toMutableList()
        private var listCalls = 0
        var onList: suspend (call: Int) -> Unit = {}
        var failToClear: LibraryCoordinate? = null

        override suspend fun list(): List<LibraryCoordinate> {
            val snapshot = entries.toList()
            onList(++listCalls)
            return snapshot
        }

        override suspend fun clear(coordinate: LibraryCoordinate) {
            if (coordinate == failToClear) throw IOException("$coordinate is in use")
            entries -= coordinate
        }

        override suspend fun size(): Long = entries.size * 1_000L
        override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = null
        override suspend fun putIndex(index: LibraryIndex) = error("not used")
        override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = error("not used")
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val TIMEOUT_MILLIS = 10_000L
        val A = LibraryCoordinate("org.example", "a", "1.0")
        val B = LibraryCoordinate("org.example", "b", "1.0")
        val C = LibraryCoordinate("org.example", "c", "1.0")
    }
}
