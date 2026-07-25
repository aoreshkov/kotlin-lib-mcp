package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.kotlinlibmcp.core.FetchResult
import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.core.MavenSourceFetcher
import app.oreshkov.kotlinlibmcp.core.SourceAnalyzer
import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.model.DependencyNode
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification

/*
 * Offline collaborators for server tests: a fetcher that serves a canned version catalog and
 * analyzer/cache stand-ins that fail loudly if a test unexpectedly reaches them.
 */

internal class FakeFetcher(private val catalog: VersionCatalog) : MavenSourceFetcher {
    override suspend fun fetchVersionCatalog(group: String, artifact: String, repos: List<String>): VersionCatalog =
        catalog

    override suspend fun fetch(coordinate: LibraryCoordinate, repos: List<String>): FetchResult =
        throw UnsupportedOperationException("not used")

    override suspend fun resolveDependencies(
        coordinate: LibraryCoordinate,
        repos: List<String>,
        maxDepth: Int,
    ): DependencyNode = throw UnsupportedOperationException("not used")
}

internal object UnusedAnalyzer : SourceAnalyzer {
    override fun analyze(
        coordinate: LibraryCoordinate,
        sourceRoots: List<String>,
        classpathRoots: List<String>,
    ): LibraryIndex = throw UnsupportedOperationException("not used")
}

internal object UnusedCache : LibraryCache {
    override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? = null
    override suspend fun putIndex(index: LibraryIndex) = Unit
    override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String) = Unit
    override suspend fun list(): List<LibraryCoordinate> = emptyList()
    override suspend fun clear(coordinate: LibraryCoordinate) = Unit
    override suspend fun size(): Long = 0
}

/**
 * A [ClientConnection] that supplies only a [sessionId] — enough to invoke a registered tool's
 * handler directly (`RegisteredTool.handler` is an extension on this type). Everything else fails
 * loudly, so a test that unexpectedly talks back to the client is obvious.
 *
 * Outbound notifications are the exception: they are *recorded* rather than rejected, because
 * `notifications/progress` and `notifications/tasks/status` are a normal part of a tool call and
 * are worth asserting on. Inspect [notifications].
 */
internal class FakeConnection(override val sessionId: String = "test-session") : ClientConnection {
    private fun unused(): Nothing = throw UnsupportedOperationException("not used")

    val notifications: MutableList<ServerNotification> = mutableListOf()

    override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) {
        notifications += notification
    }
    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult = unused()
    override suspend fun createMessage(
        request: CreateMessageRequest,
        options: RequestOptions?,
    ): CreateMessageResult = unused()

    override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult = unused()
    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?,
    ): ElicitResult = unused()

    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?,
    ): ElicitResult = unused()

    override suspend fun createElicitation(request: ElicitRequest, options: RequestOptions?): ElicitResult = unused()
    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = unused()
    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = unused()
    override suspend fun sendResourceListChanged() = unused()
    override suspend fun sendToolListChanged() = unused()
    override suspend fun sendPromptListChanged() = unused()
    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) = unused()
}

/** A [LibraryService] wired entirely to the fakes above — no network, no analysis, no cache IO. */
internal fun fakeService(catalog: VersionCatalog = VersionCatalog(versions = emptyList())): LibraryService =
    LibraryService(
        fetcher = FakeFetcher(catalog),
        analyzer = UnusedAnalyzer,
        cache = UnusedCache,
        repos = emptyList(),
    )
