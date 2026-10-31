package gitviz

enum class Choice { OURS, THEIRS, BOTH }

sealed interface ConflictPart

data class SameLines(val lines: List<String>) : ConflictPart

/** 一个冲突块。行里保留原来的行尾，比如 Windows 换行的 \r。 */
data class ConflictHunk(val ours: List<String>, val theirs: List<String>) : ConflictPart {
    fun lines(choice: Choice): List<String> = when (choice) {
        Choice.OURS -> ours
        Choice.THEIRS -> theirs
        Choice.BOTH -> ours + theirs
    }
}

/** 一个文件的冲突内容。oursLabel、theirsLabel 取自标记行，比如 HEAD 和分支名。 */
data class ConflictFile(val parts: List<ConflictPart>, val oursLabel: String, val theirsLabel: String) {
    val hunks: List<ConflictHunk> get() = parts.filterIsInstance<ConflictHunk>()
}

private enum class Section { SAME, OURS, BASE, THEIRS }

private fun isMarker(line: String, mark: Char): Boolean {
    val text = line.trimEnd('\r')
    return text.length >= 7 && text.take(7).all { it == mark } && (text.length == 7 || text[7] == ' ')
}

/**
 * 解析工作区文件里的冲突标记。没有标记或者标记不完整时返回 null，
 * 这时不能按块处理，只能整个文件选一边，免得写坏文件。
 * 只有在冲突块内部才认 ======= 和 >>>>>>>，普通文件里可能真有这样的行。
 */
fun parseConflict(text: String): ConflictFile? {
    val parts = ArrayList<ConflictPart>()
    var same = ArrayList<String>()
    var ours = ArrayList<String>()
    var theirs = ArrayList<String>()
    var oursLabel = ""
    var theirsLabel = ""
    var section = Section.SAME

    for (line in text.split('\n')) {
        when {
            section == Section.SAME && isMarker(line, '<') -> {
                if (same.isNotEmpty()) parts += SameLines(same)
                same = ArrayList()
                ours = ArrayList()
                theirs = ArrayList()
                oursLabel = line.trimEnd('\r').drop(8)
                section = Section.OURS
            }
            section == Section.SAME -> same += line
            section == Section.OURS && isMarker(line, '|') -> section = Section.BASE // diff3 样式的共同祖先，不需要显示
            (section == Section.OURS || section == Section.BASE) && isMarker(line, '=') -> section = Section.THEIRS
            section == Section.OURS -> ours += line
            section == Section.BASE -> Unit
            section == Section.THEIRS && isMarker(line, '>') -> {
                theirsLabel = line.trimEnd('\r').drop(8)
                parts += ConflictHunk(ours, theirs)
                section = Section.SAME
            }
            else -> theirs += line
        }
    }
    if (section != Section.SAME) return null
    if (same.isNotEmpty()) parts += SameLines(same)
    if (parts.none { it is ConflictHunk }) return null
    return ConflictFile(parts, oursLabel, theirsLabel)
}

/** 按选择拼出解决后的文件内容。choices 的下标对应 hunks，要求每一块都已选好。 */
fun resolveConflict(file: ConflictFile, choices: Map<Int, Choice>): String {
    var hunk = 0
    return file.parts.flatMap { part ->
        when (part) {
            is SameLines -> part.lines
            is ConflictHunk -> part.lines(choices.getValue(hunk++))
        }
    }.joinToString("\n")
}
