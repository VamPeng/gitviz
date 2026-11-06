package gitviz

enum class Choice { OURS, THEIRS, BOTH }

sealed interface ConflictPart

data class SameLines(val lines: List<String>) : ConflictPart

/** 一个冲突块。行里保留原来的行尾，比如 Windows 换行的 \r。 */
data class ConflictHunk(val ours: List<String>, val theirs: List<String>, val base: List<String>? = null, val original: List<String> = emptyList()) : ConflictPart {
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

private fun isMarker(line: String, mark: Char, width: Int): Boolean {
    val text = line.trimEnd('\r')
    return text.length >= width && text.take(width).all { it == mark } && (text.length == width || text[width] == ' ')
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
    var base: ArrayList<String>? = null
    var width = 7
    var original = ArrayList<String>()
    var oursLabel = ""
    var theirsLabel = ""
    var section = Section.SAME

    for (line in text.split('\n')) {
        if (section != Section.SAME) original += line
        when {
            section == Section.SAME && line.takeWhile { it == '<' }.length >= 7 && isMarker(line, '<', line.takeWhile { it == '<' }.length) -> {
                if (same.isNotEmpty()) parts += SameLines(same)
                same = ArrayList()
                ours = ArrayList()
                theirs = ArrayList()
                base = null
                original = arrayListOf(line)
                width = line.takeWhile { it == '<' }.length
                oursLabel = line.trimEnd('\r').drop(width + 1)
                section = Section.OURS
            }
            section == Section.SAME -> same += line
            section == Section.OURS && isMarker(line, '|', width) -> { section = Section.BASE; base = ArrayList() }
            (section == Section.OURS || section == Section.BASE) && isMarker(line, '=', width) -> section = Section.THEIRS
            section == Section.OURS -> ours += line
            section == Section.BASE -> base?.add(line)
            section == Section.THEIRS && isMarker(line, '>', width) -> {
                theirsLabel = line.trimEnd('\r').drop(width + 1)
                parts += ConflictHunk(ours, theirs, base, original.toList())
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

/** Keep unresolved blocks byte-for-byte when moving a partial choice into the editor. */
fun conflictDraftText(file: ConflictFile, choices: Map<Int, Choice>): String {
    var index = 0
    return file.parts.flatMap { part -> when (part) {
        is SameLines -> part.lines
        is ConflictHunk -> choices[index++]?.let(part::lines) ?: part.original
    } }.joinToString("\n")
}
