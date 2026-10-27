package app.oreshkov.kotlinlibmcp.server.diff

import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line diff itself: that it finds the *small* change in a large file rather than reporting a
 * wholesale replacement, and that it refuses the inputs that would make it expensive.
 */
class LineDiffTest {

    private fun lines(vararg text: String) = text.toList()

    @Test
    fun aOneLineEditBecomesOneHunkWithContext() {
        val before = lines("a", "b", "c", "d", "e", "f", "g")
        val after = lines("a", "b", "c", "D", "e", "f", "g")

        val result = requireNotNull(LineDiff.diff(before, after, contextLines = 1))

        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        val hunk = result.hunks.single()
        assertEquals(listOf(" c", "-d", "+D", " e"), hunk.lines)
        // 3 old lines (c, d, e) from line 3; 3 new lines (c, D, e) from line 3.
        assertEquals("@@ -3,3 +3,3 @@", hunk.header)
    }

    @Test
    fun anUnchangedFileHasNoHunks() {
        val same = lines("a", "b", "c")

        val result = requireNotNull(LineDiff.diff(same, same, contextLines = 3))

        assertEquals(emptyList(), result.hunks)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun distantEditsBecomeSeparateHunksAndNearbyOnesShareOne() {
        val before = (1..40).map { "line $it" }
        val far = before.toMutableList().apply {
            this[2] = "changed near the top"
            this[37] = "changed near the bottom"
        }

        assertEquals(2, requireNotNull(LineDiff.diff(before, far, contextLines = 2)).hunks.size)

        val near = before.toMutableList().apply {
            this[10] = "first"
            this[12] = "second"
        }
        // Two changes three lines apart, with two lines of context each: the windows overlap, so
        // emitting two hunks would print the same context twice.
        assertEquals(1, requireNotNull(LineDiff.diff(before, near, contextLines = 2)).hunks.size)

        // Two unchanged lines between the changes and one line of context each: the windows touch
        // without overlapping, which is still one hunk, as `diff -U1` prints it.
        val touching = before.toMutableList().apply {
            this[10] = "first"
            this[13] = "second"
        }
        val hunk = requireNotNull(LineDiff.diff(before, touching, contextLines = 1)).hunks.single()
        assertEquals("@@ -10,6 +10,6 @@", hunk.header)
    }

    @Test
    fun adjacentChangedLinesShareOneHunkEvenWithoutContext() {
        val before = lines("a", "b", "c", "d")
        val after = lines("a", "B", "C", "d")

        val hunk = requireNotNull(LineDiff.diff(before, after, contextLines = 0)).hunks.single()

        assertEquals("@@ -2,2 +2,2 @@", hunk.header)
        assertEquals(listOf("-b", "-c", "+B", "+C"), hunk.lines)
    }

    @Test
    fun aSmallEditInALargeFileStaysCheap() {
        // The point of trimming the common prefix and suffix: the LCS table is sized by what is
        // left, so this is a handful of cells, not 10_000 x 10_000. Without trimming this test
        // would exceed MAX_CELLS and return null.
        val before = (1..10_000).map { "line $it" }
        val after = before.toMutableList().apply { this[5_000] = "edited" }

        val result = requireNotNull(LineDiff.diff(before, after, contextLines = 3))

        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(1, result.hunks.size)
        assertTrue(result.hunks.single().lines.any { it == "+edited" })
    }

    @Test
    fun pureInsertionAndPureDeletionAreReportedAsSuch() {
        val before = lines("a", "b")
        val after = lines("a", "x", "y", "b")

        val inserted = requireNotNull(LineDiff.diff(before, after, contextLines = 1))
        assertEquals(2, inserted.added)
        assertEquals(0, inserted.removed)

        val deleted = requireNotNull(LineDiff.diff(after, before, contextLines = 1))
        assertEquals(0, deleted.added)
        assertEquals(2, deleted.removed)

        // With no context one side of the hunk is empty, and an empty side names the line *after
        // which* the change sits: x,y go in after old line 1, and come out after new line 1.
        assertEquals("@@ -1,0 +2,2 @@", requireNotNull(LineDiff.diff(before, after, contextLines = 0)).hunks.single().header)
        assertEquals("@@ -2,2 +1,0 @@", requireNotNull(LineDiff.diff(after, before, contextLines = 0)).hunks.single().header)
        // ...which at the top of the file is line 0.
        assertEquals("@@ -0,0 +1,1 @@", requireNotNull(LineDiff.diff(before, lines("x", "a", "b"), 0)).hunks.single().header)
    }

    // --- the hunks are a real patch ---

    @Test
    fun zeroContextHunksApplyWithGit() {
        val ten = (1..10).map { "line $it" }
        val cases = mapOf(
            "insertion" to (lines("a", "b") to lines("a", "x", "y", "b")),
            "insertion at the top" to (lines("a", "b") to lines("x", "a", "b")),
            "insertion at the end" to (lines("a", "b") to lines("a", "b", "x")),
            "deletion" to (lines("a", "x", "y", "b") to lines("a", "b")),
            "deletion at the top" to (lines("x", "a", "b") to lines("a", "b")),
            "several hunks" to (
                ten to ten.toMutableList().apply {
                    this[7] = "line 8 edited"
                    removeAt(4)
                    add(2, "inserted")
                }
            ),
        )

        for ((name, case) in cases) {
            val (before, after) = case
            val diff = requireNotNull(LineDiff.diff(before, after, contextLines = 0))
            assertEquals(after, applyWithGit(before, diff), "$name: git apply produced a different file")
        }
    }

    @Test
    fun hunksWithContextApplyWithGit() {
        val before = (1..30).map { "line $it" }
        val after = before.toMutableList().apply {
            this[3] = "line 4 edited"
            add(20, "inserted")
        }

        val diff = requireNotNull(LineDiff.diff(before, after, contextLines = 3))

        assertEquals(after, applyWithGit(before, diff))
    }

    /**
     * Writes [before] and [diff]'s hunks to a scratch directory, applies them with `git apply` and
     * returns the patched file. `--unidiff-zero` makes git trust the line numbers alone, so this
     * checks the headers rather than letting context matching paper over a wrong start line.
     */
    private fun applyWithGit(before: List<String>, diff: LineDiffResult): List<String> {
        val dir = createTempDirectory("line-diff-apply")
        try {
            dir.resolve("f.txt").writeText(before.joinToString("\n", postfix = "\n"))
            val patch = buildString {
                append("--- a/f.txt\n+++ b/f.txt\n")
                for (hunk in diff.hunks) {
                    append(hunk.header).append('\n')
                    hunk.lines.forEach { append(it).append('\n') }
                }
            }
            dir.resolve("change.patch").writeText(patch)
            val git = ProcessBuilder("git", "apply", "--unidiff-zero", "change.patch")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
            val output = git.inputStream.bufferedReader().readText()
            assertEquals(0, git.waitFor(), "git apply rejected the patch:\n$patch\n$output")
            return dir.resolve("f.txt").readText().replace("\r\n", "\n").removeSuffix("\n").split("\n")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- the bounds ---

    @Test
    fun aFileLongerThanTheLimitIsRefusedRatherThanDiffed() {
        val huge = (1..LineDiff.MAX_FILE_LINES + 1).map { "line $it" }

        assertNull(
            LineDiff.diff(huge, huge.toMutableList().apply { this[0] = "x" }, contextLines = 3),
            "past MAX_FILE_LINES the caller reports the change without hunks",
        )
    }

    @Test
    fun aWholesaleRewriteIsRefusedInsteadOfCostingQuadraticTime() {
        // Nothing in common, so prefix/suffix trimming saves nothing and the table would be the
        // full n x m. Refusing is the same bargain search_source makes with its match budget.
        val before = (1..2_000).map { "old $it" }
        val after = (1..2_000).map { "new $it" }

        assertNull(LineDiff.diff(before, after, contextLines = 3))
    }

    @Test
    fun aRewriteJustInsideTheCellBudgetIsStillDiffed() {
        // 900 x 900 = 810_000 cells, under MAX_CELLS — the bound rejects the expensive case
        // without also rejecting a merely large one.
        val before = (1..900).map { "old $it" }
        val after = (1..900).map { "new $it" }

        val result = requireNotNull(LineDiff.diff(before, after, contextLines = 0))

        assertEquals(900, result.added)
        assertEquals(900, result.removed)
    }
}
