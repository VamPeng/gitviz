package gitviz

/**
 * 分支图中的一行。lane 是列号，从 0 开始。
 *
 * - passThrough：从上到下直穿这一行的线（与本行提交无关）
 * - incoming：从上方汇入本行节点的线（这些线原本在等待本提交）
 * - outgoing：从本行节点向下延伸到父提交所在列的线
 */
class GraphRow(
    val node: Int,
    val passThrough: IntArray,
    val incoming: IntArray,
    val outgoing: IntArray,
    val width: Int,
)

object GraphLayout {

    /**
     * 调整成拓扑序，其余保持原来的顺序。
     * 提交时间不准时，父提交可能排在子提交前面，这时把它推迟到最后一个子提交之后。
     */
    fun topoSort(commits: List<CommitInfo>): List<CommitInfo> {
        val byId = commits.associateBy { it.id }
        // 每个提交还有多少个子提交没输出
        val waiting = HashMap<String, Int>()
        for (c in commits) {
            for (parent in c.parents.distinct()) {
                if (parent in byId) waiting.merge(parent, 1, Int::plus)
            }
        }

        val result = ArrayList<CommitInfo>(commits.size)
        val delayed = HashSet<String>()
        val ready = ArrayDeque<CommitInfo>()
        for (c in commits) {
            ready.addLast(c)
            while (ready.isNotEmpty()) {
                val next = ready.removeLast()
                if (waiting.getOrDefault(next.id, 0) > 0) {
                    delayed += next.id
                    continue
                }
                result += next
                for (parent in next.parents.distinct()) {
                    val left = (waiting[parent] ?: continue) - 1
                    waiting[parent] = left
                    if (left == 0 && delayed.remove(parent)) ready.addLast(byId.getValue(parent))
                }
            }
        }
        return result
    }

    /** commits 必须是拓扑序（子提交在父提交之前）。 */
    fun compute(commits: List<CommitInfo>): List<GraphRow> {
        // lanes[i] = 第 i 列正在等待的提交 id，null 表示空闲
        val lanes = ArrayList<String?>()
        val rows = ArrayList<GraphRow>(commits.size)

        fun freeSlot(exclude: Int = -1): Int {
            val i = lanes.indices.firstOrNull { lanes[it] == null && it != exclude }
            if (i != null) return i
            lanes += null
            return lanes.lastIndex
        }

        for (c in commits) {
            val incoming = lanes.indices.filter { lanes[it] == c.id }
            val node = incoming.firstOrNull() ?: freeSlot()
            val passThrough = lanes.indices.filter { lanes[it] != null && lanes[it] != c.id }
            val widthBefore = lanes.size

            // 汇入本节点的列都释放掉，本节点所在列改为等待第一个父提交
            for (i in incoming) lanes[i] = null
            val outgoing = ArrayList<Int>()
            c.parents.forEachIndexed { index, parent ->
                if (index == 0) {
                    lanes[node] = parent
                    outgoing += node
                } else {
                    // 合并提交的其他父提交：已有列在等它就连过去，否则开新列
                    val existing = lanes.indexOf(parent)
                    val lane = if (existing >= 0) existing else freeSlot(exclude = node).also { lanes[it] = parent }
                    outgoing += lane
                }
            }

            rows += GraphRow(
                node = node,
                passThrough = passThrough.toIntArray(),
                incoming = incoming.toIntArray(),
                outgoing = outgoing.distinct().toIntArray(),
                width = maxOf(widthBefore, lanes.size, node + 1),
            )

            while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.lastIndex)
        }
        return rows
    }
}
