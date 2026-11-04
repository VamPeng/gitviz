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

/** 文件权限的变化，old、new 是 git 的八进制模式，例如 100644。 */
data class ModeChange(val old: String, val new: String) {
    val text: String
        get() {
            val note = when {
                old == "100644" && new == "100755" -> "加上可执行权限"
                old == "100755" && new == "100644" -> "去掉可执行权限"
                new == "120000" -> "改成符号链接"
                old == "120000" -> "符号链接改成普通文件"
                else -> null
            }
            return "权限 $old → $new" + (note?.let { "（$it）" }.orEmpty())
        }
}

/** 从文件头的 old mode、new mode 两行读出权限变化，没有变化时返回 null。 */
fun modeChange(lines: List<DiffLine>): ModeChange? {
    val header = lines.filter { it.kind == DiffLineKind.HEADER }.map { it.text }
    val old = header.firstOrNull { it.startsWith("old mode ") }?.removePrefix("old mode ")?.trim()
    val new = header.firstOrNull { it.startsWith("new mode ") }?.removePrefix("new mode ")?.trim()
    return if (old != null && new != null && old != new) ModeChange(old, new) else null
}

/** diff 里没有任何 @@ 段时，说明为什么没有可显示的行。 */
fun emptyDiffMessage(lines: List<DiffLine>): String {
    val header = lines.filter { it.kind == DiffLineKind.HEADER }.map { it.text }
    modeChange(lines)?.let { return "仅${it.text}，内容没有变化" }
    return when {
        header.any { it.startsWith("Binary files") || it == "GIT binary patch" } -> "二进制文件，无法显示改动"
        header.any { it.startsWith("rename from ") } -> "仅重命名，内容没有变化"
        header.any { it.startsWith("copy from ") } -> "复制的文件，内容没有变化"
        else -> "没有可显示的文本改动"
    }
}
