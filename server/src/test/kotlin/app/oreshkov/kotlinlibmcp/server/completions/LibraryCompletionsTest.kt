package app.oreshkov.kotlinlibmcp.server.completions

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import app.oreshkov.kotlinlibmcp.model.PackageInfo
import app.oreshkov.kotlinlibmcp.server.FakeTransport
import app.oreshkov.kotlinlibmcp.server.handshake
import app.oreshkov.kotlinlibmcp.server.resources.LIBRARY_INDEX_URI_TEMPLATE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequestParams.Argument
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplateReference
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LibraryCompletionsTest {

    private val ktorCore351 = LibraryCoordinate("io.ktor", "ktor-client-core", "3.5.1")
    private val ktorCore340 = LibraryCoordinate("io.ktor", "ktor-client-core", "3.4.0")
    private val ktorCio351 = LibraryCoordinate("io.ktor", "ktor-client-cio", "3.5.1")
    private val annotations = LibraryCoordinate("org.jetbrains", "annotations", "24.0.0")

    private val cached = listOf(ktorCore351, ktorCore340, ktorCio351, annotations)

    // --- coordinateSegmentCompletions (resource-template variables) ---

    @Test
    fun groupCompletionIsPrefixFilteredAndDeduplicated() {
        val result = coordinateSegmentCompletions(Argument("group", "io"), emptyMap(), cached)
        assertEquals(listOf("io.ktor"), result)
    }

    @Test
    fun emptyValueReturnsAllDistinctGroupsSorted() {
        val result = coordinateSegmentCompletions(Argument("group", ""), emptyMap(), cached)
        assertEquals(listOf("io.ktor", "org.jetbrains"), result)
    }

    @Test
    fun artifactCompletionIsNarrowedByResolvedGroup() {
        val result = coordinateSegmentCompletions(
            Argument("artifact", "ktor-client"),
            mapOf("group" to "io.ktor"),
            cached,
        )
        assertEquals(listOf("ktor-client-cio", "ktor-client-core"), result)
    }

    @Test
    fun versionCompletionIsNarrowedByGroupAndArtifact() {
        val result = coordinateSegmentCompletions(
            Argument("version", "3."),
            mapOf("group" to "io.ktor", "artifact" to "ktor-client-core"),
            cached,
        )
        assertEquals(listOf("3.4.0", "3.5.1"), result)
    }

    @Test
    fun unknownSegmentNameYieldsNoCompletions() {
        val result = coordinateSegmentCompletions(Argument("bogus", "x"), emptyMap(), cached)
        assertEquals(emptyList(), result)
    }

    // --- promptArgCompletions (explain_public_api arguments) ---

    @Test
    fun coordinateArgCompletesFullCoordinateStrings() = runTest {
        val result = promptArgCompletions(
            Argument("coordinate", "io.ktor:ktor-client-c"),
            emptyMap(),
            FakeCache(cached),
        )
        assertEquals(
            listOf(
                "io.ktor:ktor-client-cio:3.5.1",
                "io.ktor:ktor-client-core:3.4.0",
                "io.ktor:ktor-client-core:3.5.1",
            ),
            result,
        )
    }

    @Test
    fun packageArgIsScopedToCoordinateInContext() = runTest {
        val cache = FakeCache(
            cached,
            packages = mapOf(ktorCore351 to listOf("io.ktor.client", "io.ktor.client.engine", "io.ktor.util")),
        )
        val result = promptArgCompletions(
            Argument("package", "io.ktor.client"),
            mapOf("coordinate" to "io.ktor:ktor-client-core:3.5.1"),
            cache,
        )
        assertEquals(listOf("io.ktor.client", "io.ktor.client.engine"), result)
    }

    @Test
    fun packageArgWithoutCoordinateContextYieldsNoCompletions() = runTest {
        val result = promptArgCompletions(Argument("package", "io"), emptyMap(), FakeCache(cached))
        assertEquals(emptyList(), result)
    }

    @Test
    fun packageArgWithUnparseableCoordinateYieldsNoCompletions() = runTest {
        val result = promptArgCompletions(
            Argument("package", "io"),
            mapOf("coordinate" to "not-a-coordinate"),
            FakeCache(cached),
        )
        assertEquals(emptyList(), result)
    }

    // --- registration: the handler has to reach every session, not just the newest ---

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun everySessionAnswersCompletionComplete() = runTest {
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(completions = EmptyJsonObject),
                handlerCoroutineContext = StandardTestDispatcher(testScheduler),
            ),
        )
        server.registerLibraryCompletions(FakeCache(cached))

        val alice = FakeTransport()
        val aliceSession = server.createSession(alice)
        val bob = FakeTransport()
        val bobSession = server.createSession(bob)
        alice.handshake()
        bob.handshake()

        val request = CompleteRequest(
            CompleteRequestParams(
                argument = Argument("group", "io"),
                ref = ResourceTemplateReference(uri = LIBRARY_INDEX_URI_TEMPLATE),
            )
        ).toJSON()

        alice.deliver(request.copy(id = RequestId(2L)))
        bob.deliver(request.copy(id = RequestId(2L)))
        runCurrent()

        // A session left unconfigured would answer -32601 Method not found instead, and its client
        // would silently lose autocomplete for its whole lifetime.
        for ((name, transport) in listOf("alice" to alice, "bob" to bob)) {
            val result = assertNotNull(transport.responseTo(2), "$name got no completion response")
            assertEquals(listOf("io.ktor"), assertIs<CompleteResult>(result.result).completion.values)
        }

        aliceSession.close()
        bobSession.close()
    }
}

/** In-memory cache serving canned coordinates and (optionally) per-coordinate package lists. */
private class FakeCache(
    coordinates: List<LibraryCoordinate>,
    packages: Map<LibraryCoordinate, List<String>> = emptyMap(),
) : LibraryCache {
    private val indexes: Map<LibraryCoordinate, LibraryIndex> = coordinates.associateWith { coordinate ->
        LibraryIndex(
            coordinate = coordinate,
            packages = packages[coordinate].orEmpty().map { PackageInfo(name = it, declarationCount = 0) },
            fetchedAt = Instant.fromEpochSeconds(0),
        )
    }

    override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = indexes[coordinate]
    override suspend fun putIndex(index: LibraryIndex) = Unit
    override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
    override suspend fun list(): List<LibraryCoordinate> = indexes.keys.toList()
    override suspend fun clear(coordinate: LibraryCoordinate) = Unit
    override suspend fun size(): Long = indexes.size.toLong()
}
