package app.oreshkov.kotlinlibmcp.server.elicitation

import app.oreshkov.kotlinlibmcp.core.VersionCatalog
import app.oreshkov.kotlinlibmcp.server.FakeConnection
import app.oreshkov.kotlinlibmcp.server.LibraryService
import app.oreshkov.kotlinlibmcp.server.fakeService
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestFormParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.TitledSingleSelectEnumSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The version picker: when it is offered, what it looks like on the wire, and how each of the
 * spec's three response actions is honored.
 *
 * The elicitation gate is driven through the internal `canElicit` seam rather than a real session,
 * because `ServerSession.clientCapabilities` is only ever populated by a live `initialize`
 * handshake. The capability *predicate* is tested directly below, and the session lookup that
 * joins the two is one expression at the single call site.
 */
class VersionElicitationTest {

    private val group = "io.ktor"
    private val artifact = "ktor-client-core"

    /** A realistic catalog: several stable releases, one newer pre-release, one older line. */
    private val catalog = VersionCatalog(
        versions = listOf("3.4.0", "3.5.0", "3.5.1", "3.6.0-beta-1", "2.3.12"),
        release = "3.5.1",
        latest = "3.6.0-beta-1",
    )

    private fun service(c: VersionCatalog = catalog): LibraryService = fakeService(c)

    private fun answering(action: ElicitResult.Action, version: String? = null) = FakeConnection().apply {
        elicitationResponder = {
            ElicitResult(
                action = action,
                content = version?.takeIf { action == ElicitResult.Action.Accept }
                    ?.let { buildJsonObject { put("version", it) } },
            )
        }
    }

    private fun ElicitRequest.formParams() = params as ElicitRequestFormParams

    private fun ElicitRequest.versionSchema() =
        formParams().requestedSchema.properties.getValue("version") as TitledSingleSelectEnumSchema

    // --- when the picker is offered ---

    @Test
    fun aVersionLessCoordinateAsksTheUserWhichVersionToFetch() = runTest {
        val connection = answering(ElicitResult.Action.Accept, "3.4.0")

        val resolved = connection.resolveCoordinate(
            canElicit = true,
            service = service(),
            group = group,
            artifact = artifact,
            versionSpec = null,
        )

        assertEquals("3.4.0", resolved.version)
        assertEquals(1, connection.elicitations.size)
    }

    @Test
    fun anExplicitLatestAlsoAsks() = runTest {
        val connection = answering(ElicitResult.Action.Accept, "3.5.0")

        val resolved = connection.resolveCoordinate(true, service(), group, artifact, "latest")

        assertEquals("3.5.0", resolved.version)
    }

    @Test
    fun aConcreteVersionIsNeverSecondGuessed() = runTest {
        // The FakeConnection has no responder, so any elicitation attempt would throw.
        val connection = FakeConnection()

        val resolved = connection.resolveCoordinate(true, service(), group, artifact, "3.4.0")

        assertEquals("3.4.0", resolved.version)
        assertTrue(connection.elicitations.isEmpty())
    }

    @Test
    fun aClientWithoutElicitationKeepsTheSilentLatestStablePick() = runTest {
        val connection = FakeConnection()

        val resolved = connection.resolveCoordinate(
            canElicit = false,
            service = service(),
            group = group,
            artifact = artifact,
            versionSpec = null,
        )

        // Byte-for-byte the pre-elicitation behavior: latest *stable*, not the newer beta.
        assertEquals("3.5.1", resolved.version)
        assertTrue(connection.elicitations.isEmpty())
    }

    @Test
    fun aSingleVersionIsNotWorthAsking() = runTest {
        val connection = FakeConnection()
        val only = VersionCatalog(versions = listOf("1.0.0"), release = "1.0.0")

        val resolved = connection.resolveCoordinate(true, service(only), group, artifact, null)

        // A one-option dropdown is pure friction.
        assertEquals("1.0.0", resolved.version)
        assertTrue(connection.elicitations.isEmpty())
    }

    // --- what the request looks like on the wire ---

    @Test
    fun thePickerIsAFormModeSingleSelectWithTitledOptions() = runTest {
        val connection = answering(ElicitResult.Action.Accept, "3.5.1")

        connection.resolveCoordinate(true, service(), group, artifact, null)
        val request = connection.elicitations.single()

        // Form mode, not URL mode: a public version number is not sensitive information.
        assertEquals("form", request.formParams().mode)

        val schema = request.versionSchema()
        // SEP-1330's titled form is `oneOf` with {const, title} — not the deprecated enumNames.
        assertEquals(
            listOf("3.6.0-beta-1", "3.5.1", "3.5.0", "3.4.0", "2.3.12"),
            schema.oneOf.map { it.const },
        )
        assertEquals("3.5.1 — latest stable", schema.oneOf.single { it.const == "3.5.1" }.title)
        assertEquals("3.6.0-beta-1 — pre-release", schema.oneOf.single { it.const == "3.6.0-beta-1" }.title)
        assertEquals("3.4.0", schema.oneOf.single { it.const == "3.4.0" }.title)

        // Clients that honor defaults pre-select what the silent path would have chosen.
        assertEquals("3.5.1", schema.default)
        assertEquals(listOf("version"), request.formParams().requestedSchema.required)
    }

    @Test
    fun theSchemaStaysFlatAndCarriesNoUrl() = runTest {
        val connection = answering(ElicitResult.Action.Accept, "3.5.1")

        connection.resolveCoordinate(true, service(), group, artifact, null)
        val request = connection.elicitations.single()
        val params = request.formParams()

        // Elicitation schemas are restricted to flat objects of primitive properties.
        assertEquals(1, params.requestedSchema.properties.size)
        assertEquals("object", params.requestedSchema.type)
        // Servers SHOULD NOT put clickable URLs in any form-mode field.
        assertTrue("http" !in params.message)
        assertTrue(params.requestedSchema.properties.values.none { "http" in it.toString() })
        // No task in scope, so nothing to relate this request to.
        assertNull(params.meta)
    }

    // --- the three response actions ---

    @Test
    fun acceptFetchesTheChosenVersion() = runTest {
        val connection = answering(ElicitResult.Action.Accept, "2.3.12")

        assertEquals("2.3.12", connection.resolveCoordinate(true, service(), group, artifact, null).version)
    }

    @Test
    fun declineFallsBackToLatestStable() = runTest {
        val connection = answering(ElicitResult.Action.Decline)

        // "Don't ask me, just pick one" — the same answer the silent path gives.
        assertEquals("3.5.1", connection.resolveCoordinate(true, service(), group, artifact, null).version)
    }

    @Test
    fun cancelDownloadsNothingAndSaysHowToAskAgain() = runTest {
        val connection = answering(ElicitResult.Action.Cancel)

        val error = assertFailsWith<VersionSelectionDismissedException> {
            connection.resolveCoordinate(true, service(), group, artifact, null)
        }
        // Dismissal is not consent, so the model is told to re-ask unambiguously rather than
        // having a download it never confirmed happen anyway.
        assertTrue("fetch_library" in error.message.orEmpty())
    }

    // --- hostile / broken clients ---

    @Test
    fun anAcceptedValueThatWasNeverOfferedIsDiscarded() = runTest {
        val connection = answering(ElicitResult.Action.Accept, "../../etc/passwd")

        // The value reaches a Maven URL path, so only our own oneOf list is trusted.
        assertEquals("3.5.1", connection.resolveCoordinate(true, service(), group, artifact, null).version)
    }

    @Test
    fun anAcceptWithNoContentFallsBack() = runTest {
        val connection = FakeConnection().apply {
            elicitationResponder = { ElicitResult(action = ElicitResult.Action.Accept, content = JsonObject(emptyMap())) }
        }

        assertEquals("3.5.1", connection.resolveCoordinate(true, service(), group, artifact, null).version)
    }

    @Test
    fun aClientThatFailsTheElicitationDoesNotFailTheFetch() = runTest {
        val connection = FakeConnection().apply {
            elicitationResponder = { error("client exploded") }
        }

        // A client that advertised a capability it cannot honor gets the silent default; breaking
        // fetch_library over a failed question would be a worse outcome than not asking.
        assertEquals("3.5.1", connection.resolveCoordinate(true, service(), group, artifact, null).version)
    }

    // --- the capability predicate ---

    @Test
    fun formSupportIsDerivedFromTheDeclaredElicitationModes() {
        // Absent capability: never ask.
        assertEquals(false, (null as ClientCapabilities.Elicitation?).supportsForm)
        // Empty object means form only, per the spec's backwards-compatibility rule.
        assertEquals(true, ClientCapabilities.Elicitation().supportsForm)
        assertEquals(true, ClientCapabilities.Elicitation(form = JsonObject(emptyMap())).supportsForm)
        assertEquals(
            true,
            ClientCapabilities.Elicitation(
                form = JsonObject(emptyMap()),
                url = JsonObject(emptyMap()),
            ).supportsForm,
        )
        // URL-only: servers MUST NOT send a mode the client did not declare.
        assertEquals(false, ClientCapabilities.Elicitation(url = JsonObject(emptyMap())).supportsForm)
    }
}
