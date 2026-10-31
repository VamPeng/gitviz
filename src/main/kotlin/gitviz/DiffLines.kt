package gitviz

enum class DiffLineKind { HEADER, HUNK, ADDED, REMOVED, CONTEXT }

data class DiffLine(val text: String, val kind: DiffLineKind)

/**
 * 给单个文件的 diff 逐行分类。
 *
 * 第一个 @@ 之前都是文件头，之后才看行首的 +、-。不能只靠前缀判断：
 * 删掉一行 `-- 注释` 得到的 `--- 注释` 和文件头长得一样。
 */
fun parseDiffLines(diff: String): List<DiffLine> {
    var inHunks = false
    return diff.lines().map { line ->
        val kind = when {
            line.startsWith("@@") -> {
                inHunks = true
                DiffLineKind.HUNK
            }
            !inHunks -> DiffLineKind.HEADER
            line.startsWith("+") -> DiffLineKind.ADDED
            line.startsWith("-") -> DiffLineKind.REMOVED
            else -> DiffLineKind.CONTEXT
        }
        DiffLine(line, kind)
    }
}
