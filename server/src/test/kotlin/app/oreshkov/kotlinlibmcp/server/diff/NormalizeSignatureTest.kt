package app.oreshkov.kotlinlibmcp.server.diff

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [normalizeSignature]: what two renderings of one declaration must agree on, and — as important —
 * what it must not touch, since stripping too much would hide a real change.
 */
class NormalizeSignatureTest {

    @Test
    fun squashesWhitespace() {
        assertEquals("public fun f( x: Int, ): Unit", normalizeSignature("  public fun f(\n    x: Int,\n): Unit "))
    }

    @Test
    fun dropsLeadingAnnotationsOfEveryShape() {
        val shapes = listOf(
            "@JvmStatic",
            "@kotlin.jvm.JvmStatic",
            "@get:JvmName(\"name\")",
            "@`<error>`",
            "@kotlin.OptIn(markerClass = [UNRESOLVED_CLASS::class])",
            "@kotlin.Deprecated(message = \"see f(x) )\", replaceWith = kotlin.ReplaceWith(expression = \"g(\\\")\\\")\"))",
            "@Deprecated(\"\"\"raw ) text\"\"\") @OptIn(InternalAPI::class)",
            "@Sample(c = ')')",
        )
        for (annotations in shapes) {
            assertEquals("public fun f(): Unit", normalizeSignature("$annotations public fun f(): Unit"), annotations)
        }
    }

    @Test
    fun leavesAnnotationsItCannotParse() {
        // Over-reporting a change beats collapsing every unparseable signature onto one string.
        val unbalanced = "@Deprecated(\"open public fun f(): Unit"
        assertEquals(unbalanced, normalizeSignature(unbalanced))
    }

    @Test
    fun dropsExpectAndActualOnlyAmongTheModifiers() {
        assertEquals("public fun f(): Unit", normalizeSignature("public expect fun f(): Unit"))
        assertEquals("public class C", normalizeSignature("public actual class C"))
        assertEquals("public fun expect(actual: Int): Unit", normalizeSignature("public fun expect(actual: Int): Unit"))
        assertEquals("public val actual: Int", normalizeSignature("public actual val actual: Int"))
    }

    @Test
    fun keepsEverythingThatIsApi() {
        val signature = "public open suspend fun <T : kotlin.Any> f(x: T = default()): kotlin.collections.List<T>"
        assertEquals(signature, normalizeSignature(signature))
    }
}
