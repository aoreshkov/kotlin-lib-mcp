package app.oreshkov.kotlinlibmcp.server

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Option
import app.oreshkov.clikit.testing.record
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `server.json`'s `packageArguments` are command-line surface published to the MCP registry: a
 * registry client builds the container's command line from them, so a renamed option or a changed
 * default breaks every installation without a compile error. These tests run what a client would
 * build through the real parser.
 */
class RegistryManifestTest {

    private val packageArguments: List<JsonObject> =
        Json.parseToJsonElement(Files.readString(Path.of("../server.json"))).jsonObject
            .getValue("packages").jsonArray.single().jsonObject
            .getValue("packageArguments").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content

    private val JsonObject.name: String get() = string("name") ?: fail("a package argument without a name: $this")

    /** Runs the parser as `serve` would see it, failing on anything but a clean, stdout-free accept. */
    private fun accepted(vararg args: String): CliOptions {
        var captured: CliOptions? = null
        val result = Cli.program { options, _ ->
            captured = options
            ExitStatus.OK
        }.record(*args)
        assertEquals(ExitStatus.OK, result.status, "${args.toList()}: ${result.stderr}")
        assertEquals("", result.stdout, "stdout belongs to the transport")
        return captured ?: fail("serve was not called")
    }

    @Test
    fun everyPackageArgumentIsADeclaredOption() {
        assertTrue(packageArguments.isNotEmpty())
        packageArguments.forEach { argument ->
            assertEquals("named", argument.string("type"), "the server takes no positional arguments: $argument")
            val option = Cli.root.options.singleOrNull { it.long == argument.name }
                ?: fail("${argument.name} is in server.json but not an option of the server")
            assertEquals(
                option is Option.Repeated<*>,
                argument["isRepeated"]?.jsonPrimitive?.boolean ?: false,
                "${argument.name}: isRepeated must say whether the parser accumulates it",
            )
        }
    }

    @Test
    fun anAdvertisedDefaultIsTheServersOwn() {
        val withDefaults = packageArguments.filter { it.string("default") != null }
        assertTrue(withDefaults.isNotEmpty())
        withDefaults.forEach { argument ->
            // A client may pass the default explicitly; doing so must change nothing.
            assertEquals(accepted(), accepted(argument.name, argument.string("default")!!), argument.name)
        }
    }

    @Test
    fun aRegistryLaunchServesStdio() {
        // The arguments follow the image name, so they replace the Dockerfile's CMD `--transport stdio`:
        // the transport the manifest declares has to be the parser's default.
        val example = { argument: JsonObject ->
            argument.string("default") ?: argument.string("placeholder") ?: fail("${argument.name}: no example value")
        }
        // The schema calls a named argument `--flag={value}`; clients also emit it as two words.
        val joined = packageArguments.map { "${it.name}=${example(it)}" }
        val separate = packageArguments.flatMap { listOf(it.name, example(it)) }
        val repo = example(packageArguments.single { it.name == "--repo" })
        listOf(joined, separate).forEach { args ->
            val options = accepted(*args.toTypedArray())
            assertEquals(TransportKind.STDIO, options.transport, "$args")
            assertEquals(listOf(repo), options.config.repos, "$args")
        }
    }
}
