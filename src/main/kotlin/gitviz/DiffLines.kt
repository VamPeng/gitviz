package gitviz

enum class DiffLineKind { HEADER, HUNK, ADDED, REMOVED, CONTEXT }

/** oldNo、newNo 是这一行在旧文件、新文件里的行号，没有对应行时为 null。 */
data class DiffLine(val text: String, val kind: DiffLineKind, val oldNo: Int? = null, val newNo: Int? = null)

/** 并排视图的一行：左边是旧文件，右边是新文件，没有对应行的一边为 null。 */
data class SplitRow(val left: DiffLine?, val right: DiffLine?)

private val HunkStart = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)""")

/**
 * 给单个文件的 diff 逐行分类。
 *
 * 第一个 @@ 之前都是文件头，之后才看行首的 +、-。不能只靠前缀判断：
 * 删掉一行 `-- 注释` 得到的 `--- 注释` 和文件头长得一样。
 */
fun parseDiffLines(diff: String): List<DiffLine> {
    var inHunks = false
    var oldNo = 0
    var newNo = 0
    return diff.lines().map { line ->
        val hunk = HunkStart.find(line)
        when {
            hunk != null -> {
                inHunks = true
                oldNo = hunk.groupValues[1].toInt()
                newNo = hunk.groupValues[2].toInt()
                DiffLine(line, DiffLineKind.HUNK)
            }
            !inHunks -> DiffLine(line, DiffLineKind.HEADER)
            line.startsWith("+") -> DiffLine(line, DiffLineKind.ADDED, newNo = newNo++)
            line.startsWith("-") -> DiffLine(line, DiffLineKind.REMOVED, oldNo = oldNo++)
            // “\ No newline at end of file” 不是文件里的一行，不占行号
            line.startsWith("\\") || line.isEmpty() -> DiffLine(line, DiffLineKind.CONTEXT)
            else -> DiffLine(line, DiffLineKind.CONTEXT, oldNo++, newNo++)
        }
    }
}

/** 把统一 diff 配成并排的行：连续的删除和新增按顺序一一对齐，上下文两边相同。 */
fun toSplitRows(lines: List<DiffLine>): List<SplitRow> {
    val rows = ArrayList<SplitRow>()
    val removed = ArrayList<DiffLine>()
    val added = ArrayList<DiffLine>()
    fun flush() {
        for (i in 0 until maxOf(removed.size, added.size)) {
            rows += SplitRow(removed.getOrNull(i), added.getOrNull(i))
        }
        removed.clear()
        added.clear()
    }
    for (line in lines) {
        when (line.kind) {
            DiffLineKind.REMOVED -> removed += line
            DiffLineKind.ADDED -> added += line
            else -> {
                flush()
                if (line.kind != DiffLineKind.HEADER) rows += SplitRow(line, line)
            }
        }
    }
    flush()
    return rows
}
