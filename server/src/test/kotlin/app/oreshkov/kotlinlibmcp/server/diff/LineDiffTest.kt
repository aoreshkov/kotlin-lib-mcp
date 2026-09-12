package app.oreshkov.kotlinlibmcp.server.diff

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
