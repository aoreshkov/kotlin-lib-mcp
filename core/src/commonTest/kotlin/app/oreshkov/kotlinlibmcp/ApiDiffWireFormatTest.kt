package app.oreshkov.kotlinlibmcp

import app.oreshkov.kotlinlibmcp.dto.ApiChange
import app.oreshkov.kotlinlibmcp.dto.ApiChangeType
import app.oreshkov.kotlinlibmcp.dto.ApiDiff
import app.oreshkov.kotlinlibmcp.model.Deprecation
import app.oreshkov.kotlinlibmcp.model.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * `diff_api`'s result shape as a client sees it. The change types are filter values a caller sends
 * back, so their wire names are contract; and the server's encoder omits defaults, which is what
 * keeps a page of plain additions small.
 */
class ApiDiffWireFormatTest {

    @Test
    fun changeTypesAreLowercaseOnTheWire() {
        assertEquals(
            listOf("\"added\"", "\"removed\"", "\"changed\"", "\"deprecated\""),
            ApiChangeType.entries.map { Json.encodeToString(it) },
        )
    }

    @Test
    fun anAdditionCarriesOnlyWhatItHas() {
        val added = ApiChange("com.example.f", SymbolKind.FUNCTION, ApiChangeType.ADDED, toSignature = "public fun f()")
        assertEquals(
            """{"fqName":"com.example.f","kind":"function","change":"added","toSignature":"public fun f()"}""",
            Json.encodeToString(added),
        )
    }

    @Test
    fun aFullDiffRoundTrips() {
        val diff = ApiDiff(
            group = "com.example",
            artifact = "demo",
            fromVersion = "1.0",
            toVersion = "2.0",
            packageName = "com.example",
            added = 1,
            removed = 2,
            changed = 3,
            deprecated = 4,
            packagesAdded = listOf("com.example.fresh"),
            packagesRemoved = listOf("com.example.old"),
            packagesTruncated = true,
            changes = listOf(
                ApiChange(
                    fqName = "com.example.old",
                    kind = SymbolKind.CLASS,
                    change = ApiChangeType.CHANGED,
                    fromSignature = "public class Old",
                    toSignature = "public open class Old",
                    deprecation = Deprecation(Deprecation.Level.ERROR, "Use New", "New", listOf("com.example.New")),
                    approximate = true,
                ),
            ),
            totalCount = 10,
            truncated = true,
        )
        assertEquals(diff, Json.decodeFromString<ApiDiff>(Json.encodeToString(diff)))
    }
}
