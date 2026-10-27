@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.fetch

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * `.pom` and `maven-metadata.xml` come from the repository, so a DOCTYPE in one must not make the
 * parser read a local file. Two `setProperty` calls in [XmlSupport.newReader] are the whole defence;
 * these tests are what notices if a refactor drops them, since the JDK's StAX default expands
 * external entities.
 */
class PomParserTest {

    private val tempDir: Path = createTempDirectory("klm-xxe-test")
    private val secret = tempDir.resolve("secret.txt").apply { writeText(SECRET) }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private val doctype = """<!DOCTYPE root [<!ENTITY x SYSTEM "${secret.toUri()}">]>"""

    @Test
    fun aPomCannotPullInALocalFileThroughAnExternalEntity() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            $doctype
            <project>
              <groupId>&x;</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
            </project>
        """.trimIndent()

        val parsed = runCatching { PomParser.parse(xml) }.getOrNull()

        assertFalse(SECRET in parsed.toString(), "external entity expanded into $parsed")
    }

    @Test
    fun mavenMetadataCannotPullInALocalFileThroughAnExternalEntity() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            $doctype
            <metadata>
              <versioning>
                <release>&x;</release>
                <versions><version>&x;</version></versions>
              </versioning>
            </metadata>
        """.trimIndent()

        val parsed = runCatching { MavenMetadataParser.parse(xml) }.getOrNull()

        assertFalse(SECRET in parsed.toString(), "external entity expanded into $parsed")
    }

    private companion object {
        const val SECRET = "xxe-canary-7f3a"
    }
}
