package app.oreshkov.kotlinlibmcp.server.diff

/**
 * A line-level unified diff, bounded so no input can make it expensive.
 *
 * Two bounds, because the cost has two shapes:
 *  - [MAX_FILE_LINES] caps how big a file may be to diff at all. The sources this server fetches
 *    include generated files of tens of thousands of lines, and a diff of one is neither cheap to
 *    compute nor useful to read.
 *  - [MAX_CELLS] caps the LCS table *after* the common prefix and suffix are trimmed. Trimming is
 *    what makes real edits cheap — a one-line change to a 5000-line file leaves a table of a few
 *    cells — while a wholesale rewrite is refused rather than quietly costing O(n·m).
 *
 * Both refusals return `null`, which the caller reports as "changed, diff omitted" with the cheap
 * line counts still filled in. Refusing to answer expensively is the same bargain `search_source`
 * makes with its match budget.
 */
internal object LineDiff {

    /** Longest file this will diff. Beyond it, callers report the change without hunks. */
    const val MAX_FILE_LINES: Int = 20_000

    /**
     * Largest LCS table, in cells, after trimming. 1M cells is a 1000×1000 changed region — far
     * more than any reviewable edit — and costs 4 MB of `Int`, which is bounded per call because
     * only the requested page of files is ever diffed.
     */
    const val MAX_CELLS: Int = 1_000_000

    /**
     * Unified-diff hunks for [from] → [to] with [contextLines] of context, or `null` when either
     * bound above is exceeded.
     */
    fun diff(from: List<String>, to: List<String>, contextLines: Int): LineDiffResult? {
        if (from.size > MAX_FILE_LINES || to.size > MAX_FILE_LINES) return null

        // Trim the matching ends. Real edits touch a small middle, and everything below is sized
        // by what is left, so this is the difference between cheap and quadratic.
        var prefix = 0
        while (prefix < from.size && prefix < to.size && from[prefix] == to[prefix]) prefix++
        var suffix = 0
        while (
            suffix < from.size - prefix &&
            suffix < to.size - prefix &&
            from[from.size - 1 - suffix] == to[to.size - 1 - suffix]
        ) {
            suffix++
        }

        val a = from.subList(prefix, from.size - suffix)
        val b = to.subList(prefix, to.size - suffix)
        if (a.size.toLong() * b.size.toLong() > MAX_CELLS) return null

        val ops = buildList {
            repeat(prefix) { add(Op(' ', from[it])) }
            addAll(editScript(a, b))
            repeat(suffix) { add(Op(' ', from[from.size - suffix + it])) }
        }
        return LineDiffResult(
            hunks = hunks(ops, contextLines),
            added = ops.count { it.tag == '+' },
            removed = ops.count { it.tag == '-' },
        )
    }

    /** One diff line: `' '` common, `'-'` only in the old file, `'+'` only in the new one. */
    private data class Op(val tag: Char, val text: String)

    /**
     * Classic LCS table, walked forward into an edit script.
     *
     * `lcs[i][j]` is the longest common subsequence of `a[i..]` and `b[j..]`, flattened into one
     * `IntArray` — a 2-D array of rows costs more in allocation than the comparisons themselves.
     */
    private fun editScript(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val width = m + 1
        val lcs = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i * width + j] = if (a[i] == b[j]) {
                    lcs[(i + 1) * width + (j + 1)] + 1
                } else {
                    maxOf(lcs[(i + 1) * width + j], lcs[i * width + (j + 1)])
                }
            }
        }

        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    ops += Op(' ', a[i]); i++; j++
                }
                // Prefer the branch that keeps the longer common subsequence; ties delete first,
                // so a replacement reads as `-old` then `+new` the way every diff tool prints it.
                lcs[(i + 1) * width + j] >= lcs[i * width + (j + 1)] -> {
                    ops += Op('-', a[i]); i++
                }
                else -> {
                    ops += Op('+', b[j]); j++
                }
            }
        }
        while (i < n) ops += Op('-', a[i++])
        while (j < m) ops += Op('+', b[j++])
        return ops
    }

    /** Groups changed lines into hunks, padding each with up to [contextLines] unchanged lines. */
    private fun hunks(ops: List<Op>, contextLines: Int): List<Hunk> {
        val changed = ops.indices.filter { ops[it].tag != ' ' }
        if (changed.isEmpty()) return emptyList()

        // Line numbers each op sits at, so a hunk header can be written without re-scanning.
        val fromLineAt = IntArray(ops.size)
        val toLineAt = IntArray(ops.size)
        var fromLine = 1
        var toLine = 1
        ops.forEachIndexed { index, op ->
            fromLineAt[index] = fromLine
            toLineAt[index] = toLine
            if (op.tag != '+') fromLine++
            if (op.tag != '-') toLine++
        }

        // Merge runs of changes whose context windows touch, so adjacent edits share one hunk.
        val groups = mutableListOf<IntRange>()
        var start = changed.first()
        var end = changed.first()
        for (index in changed.drop(1)) {
            if (index - end <= contextLines * 2) end = index else {
                groups += start..end
                start = index
                end = index
            }
        }
        groups += start..end

        return groups.map { group ->
            val first = maxOf(0, group.first - contextLines)
            val last = minOf(ops.size - 1, group.last + contextLines)
            val slice = ops.subList(first, last + 1)
            Hunk(
                fromStart = fromLineAt[first],
                fromCount = slice.count { it.tag != '+' },
                toStart = toLineAt[first],
                toCount = slice.count { it.tag != '-' },
                lines = slice.map { "${it.tag}${it.text}" },
            )
        }
    }
}

/** One unified-diff hunk; [lines] carry their own `' '`/`'-'`/`'+'` prefix. */
internal data class Hunk(
    val fromStart: Int,
    val fromCount: Int,
    val toStart: Int,
    val toCount: Int,
    val lines: List<String>,
) {
    /** The `@@ -a,b +c,d @@` header, so a client can render or re-apply this as a real patch. */
    val header: String get() = "@@ -$fromStart,$fromCount +$toStart,$toCount @@"
}

/** [hunks] plus the line counts, which stay meaningful even when hunks are dropped for size. */
internal data class LineDiffResult(val hunks: List<Hunk>, val added: Int, val removed: Int)
