package app.oreshkov.kotlinlibmcp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reads a text fixture from `jvmTest/resources/fixtures/`. */
internal fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.classLoader.getResource("fixtures/$name")) {
        "Missing test fixture: fixtures/$name"
    }.readText()

/** Builds a zip/jar in memory — binary fixtures are never checked into the repo. */
internal fun zipBytes(entries: Map<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/** [MockEngine] serving [routes] keyed by full URL; anything else is a 404. */
internal fun mockRepoEngine(routes: Map<String, ByteArray>): MockEngine =
    MockEngine { request ->
        val body = routes[request.url.toString()]
        if (body != null) {
            respond(ByteReadChannel(body), HttpStatusCode.OK)
        } else {
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.NotFound)
        }
    }
