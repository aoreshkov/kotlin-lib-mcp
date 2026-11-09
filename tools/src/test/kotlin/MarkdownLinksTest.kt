package app.oreshkov.kotlinlibmcp.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Relative links between the repository's Markdown files resolve, down to the heading. Moving a
 * section or renaming a heading breaks a link without any other part of the build noticing.
 * External URLs are not checked, so another site's outage cannot fail a build.
 */
class MarkdownLinksTest {

    private val root = File("..").canonicalFile // Gradle runs a module's tests from its directory

    private val markdownFiles: List<File> = run {
        val process = ProcessBuilder(
            "git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.md",
        ).directory(root).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val listing = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "git ls-files failed")
        listing.split('\u0000').filter(String::isNotEmpty).map(root::resolve).filter(File::exists)
    }

    private val anchors = mutableMapOf<File, Set<String>>()

    @Test
    fun scansTheRepositorysMarkdown() {
        assertTrue(root.resolve("README.md") in markdownFiles, "README.md not in $markdownFiles")
    }

    @Test
    fun relativeLinksAndAnchorsResolve() {
        val broken = markdownFiles.flatMap { file ->
            relativeLinksIn(file).mapNotNull { link -> problemWith(link, file)?.let { "${file.relativeTo(root)}: $it" } }
        }
        assertTrue(broken.isEmpty(), broken.joinToString("\n"))
    }

    /** The repository's homepage URL on GitHub is this anchor, and nothing in the tree links to it. */
    @Test
    fun readmeKeepsTheAnchorTheHomepageLinksTo() {
        assertTrue("quick-start-no-build-required" in anchorsOf(root.resolve("README.md")))
    }

    private fun relativeLinksIn(file: File): List<String> {
        val text = file.readText().replace(FENCED_CODE, "").replace(INLINE_CODE, "")
        return (INLINE_LINK.findAll(text) + REFERENCE_LINK.findAll(text))
            .map { it.groupValues[1] }
            .filterNot { SCHEME.containsMatchIn(it) }
            .toList()
    }

    private fun problemWith(link: String, from: File): String? {
        val path = link.substringBefore('#')
        val anchor = link.substringAfter('#', "")
        val target = when {
            path.isEmpty() -> from
            path.startsWith("/") -> root.resolve(path.drop(1))
            else -> from.parentFile.resolve(path)
        }
        return when {
            !target.exists() -> "$link: no such file"
            anchor.isNotEmpty() && target.extension == "md" && anchor !in anchorsOf(target) -> "$link: no such heading"
            else -> null
        }
    }

    private fun anchorsOf(file: File): Set<String> = anchors.getOrPut(file) {
        val seen = mutableMapOf<String, Int>()
        HEADING.findAll(file.readText().replace(FENCED_CODE, "")).map { heading ->
            val slug = slugOf(heading.groupValues[1])
            val repeats = seen.merge(slug, 1, Int::plus)!! - 1
            if (repeats == 0) slug else "$slug-$repeats"
        }.toSet()
    }

    /** GitHub's rule: lowercase, markup and punctuation dropped, spaces become hyphens. */
    private fun slugOf(heading: String): String = heading.trim().lowercase()
        .replace(LINK_MARKUP, "$1")
        .replace(HEADING_PUNCTUATION, "")
        .replace(' ', '-')

    private companion object {
        val FENCED_CODE = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
        val INLINE_CODE = Regex("`[^`\n]*`")
        val INLINE_LINK = Regex("""]\(([^)\s]+)""")
        val REFERENCE_LINK = Regex("""^\[[^]]+]:\s+(\S+)""", RegexOption.MULTILINE)
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        val HEADING = Regex("""^#{1,6}\s+(.+)$""", RegexOption.MULTILINE)
        val LINK_MARKUP = Regex("""\[([^]]*)]\([^)]*\)""")
        val HEADING_PUNCTUATION = Regex("""[^\p{L}\p{N}_ -]""")
    }
}
