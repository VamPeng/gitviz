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
    /** 本行节点所在的线。下面几组和上面同名的数组一一对应。 */
    val nodeTrack: Track = Track(0, 0),
    val passTracks: List<Track> = emptyList(),
    val incomingTracks: List<Track> = emptyList(),
    val outgoingTracks: List<Track> = emptyList(),
)

/**
 * 图上的一条线。color 是调色板下标；line 是线的编号，同一条线从分支顶端一路往下都是同一个编号，
 * 用来在图上只高亮一条分支。颜色会重复（调色板只有几种），编号不会。
 */
data class Track(val color: Int, val line: Int)

/** 调色板里的颜色数，要和 Palette 的 lanes 个数一致。 */
const val LANE_COLOR_COUNT = 8

/**
 * 分支名 → 颜色下标。分支图和侧边栏都从这里取色，所以同一个分支在两处同色。
 * 本地分支 feature 和远程 origin/feature 视为同一个分支。
 */
class BranchColors(refs: List<RefTarget>) {
    private val byName: Map<String, Int>
    // 提交 id → 挂在上面的分支。还没加载到的提交也查得到，翻页前就能定下线的颜色
    private val byCommit: Map<String, List<RefLabel>> = refs.groupBy({ it.commitId }, { it.label })

    init {
        val names = refs.mapNotNull { shortName(it.label) }.distinct()
        val current = refs.firstOrNull { it.label.kind == RefKind.BRANCH && it.label.isCurrent }?.label?.name
        val order = names.sortedWith(
            compareBy({ it != current }, { it !in setOf("main", "master") }, { it.lowercase() }),
        )
        byName = order.withIndex().associate { (i, n) -> n to i % LANE_COLOR_COUNT }
    }

    /** 参与着色的分支名；标签、储藏、HEAD 不参与。 */
    private fun shortName(label: RefLabel): String? = when (label.kind) {
        RefKind.BRANCH -> label.name
        RefKind.REMOTE -> label.name.substringAfter('/', "").takeIf { it.isNotEmpty() && it != "HEAD" }
        else -> null
    }

    fun index(label: RefLabel): Int? = shortName(label)?.let { byName[it] }

    /** 提交上挂着的分支里该用哪个颜色：当前分支优先，其次本地，最后远程。 */
    fun indexFor(commit: CommitInfo): Int? = indexFor(commit.refs)

    /** 同上，按提交 id 查，提交本身不用已经加载。 */
    fun indexFor(commitId: String): Int? = byCommit[commitId]?.let(::indexFor)

    private fun indexFor(labels: List<RefLabel>): Int? =
        (labels.filter { it.kind == RefKind.BRANCH }.sortedByDescending { it.isCurrent } +
            labels.filter { it.kind == RefKind.REMOTE })
            .firstNotNullOfOrNull { index(it) }

    val used: Set<Int> get() = byName.values.toSet()

    companion object {
        val None = BranchColors(emptyList())
    }
}

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
            for (parent in c.graphParents.distinct()) {
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
                for (parent in next.graphParents.distinct()) {
                    val left = (waiting[parent] ?: continue) - 1
                    waiting[parent] = left
                    if (left == 0 && delayed.remove(parent)) ready.addLast(byId.getValue(parent))
                }
            }
        }
        return result
    }

    /** commits 必须是拓扑序（子提交在父提交之前）。 */
    fun compute(commits: List<CommitInfo>, colors: BranchColors = BranchColors.None): List<GraphRow> =
        GraphBuilder(colors).add(commits)
}

/**
 * 一段一段地排分支图：每次 [add] 接着上次停下的地方往下排，前面排好的行不再动。
 * 翻页时只排新读到的提交，不用每页都从头排一遍。
 *
 * 不是线程安全的，同一时间只能有一个线程调用。
 */
class GraphBuilder(private val colors: BranchColors = BranchColors.None) {
    // lanes[i] = 第 i 列正在等待的提交 id，null 表示空闲；tracks[i] 是这一列上那条线
    private val lanes = ArrayList<String?>()
    private val tracks = ArrayList<Track?>()
    private val rows = ArrayList<GraphRow>()
    private val named = colors.used
    private var nextLine = 0
    private var nextFresh = 0

    /** 已经排了多少行。 */
    val size: Int get() = rows.size

    /**
     * 接着排 commits，返回到目前为止的全部行（一份快照，之后再 add 也不会变）。
     * commits 接在之前排过的后面，合起来必须是拓扑序。
     */
    fun add(commits: List<CommitInfo>): List<GraphRow> {
        rows.ensureCapacity(rows.size + commits.size)

        for (c in commits) {
            val incoming = lanes.indices.filter { lanes[it] == c.id }
            val nodeTrack = incoming.firstOrNull()?.let { tracks[it] } ?: newTrack(colors.indexFor(c))
            val node = incoming.firstOrNull() ?: freeSlot()
            val passThrough = lanes.indices.filter { lanes[it] != null && lanes[it] != c.id }
            val passTracks = passThrough.map { tracks[it]!! }
            val incomingTracks = incoming.map { tracks[it]!! }
            val widthBefore = lanes.size

            // 汇入本节点的列都释放掉，本节点所在列改为等待第一个父提交
            for (i in incoming) { lanes[i] = null; tracks[i] = null }
            val outgoing = LinkedHashMap<Int, Track>()
            c.graphParents.forEachIndexed { index, parent ->
                if (index == 0) {
                    lanes[node] = parent
                    tracks[node] = nodeTrack
                    outgoing.putIfAbsent(node, nodeTrack)
                } else {
                    // 合并提交的其他父提交：已有列在等它就连过去，否则开新列，新列是一条新线。
                    // 父提交可能在下一页还没读到，按 id 查它挂的分支，颜色和读到后一致
                    val existing = lanes.indexOf(parent)
                    val lane = if (existing >= 0) existing else freeSlot(exclude = node).also {
                        lanes[it] = parent
                        tracks[it] = newTrack(colors.indexFor(parent))
                    }
                    outgoing.putIfAbsent(lane, tracks[lane]!!)
                }
            }

            rows += GraphRow(
                node = node,
                passThrough = passThrough.toIntArray(),
                incoming = incoming.toIntArray(),
                outgoing = outgoing.keys.toIntArray(),
                width = maxOf(widthBefore, lanes.size, node + 1),
                nodeTrack = nodeTrack,
                passTracks = passTracks,
                incomingTracks = incomingTracks,
                outgoingTracks = outgoing.values.toList(),
            )

            while (lanes.isNotEmpty() && lanes.last() == null) {
                lanes.removeAt(lanes.lastIndex)
                tracks.removeAt(tracks.lastIndex)
            }
        }
        return rows.toList()
    }

    private fun freeSlot(exclude: Int = -1): Int {
        val i = lanes.indices.firstOrNull { lanes[it] == null && it != exclude }
        if (i != null) return i
        lanes += null
        tracks += null
        return lanes.lastIndex
    }

    // 没有分支名的线：挑一个没被现有的线、也没被有名分支占用的颜色，都占满了再放宽
    private fun freshColor(): Int {
        val active = tracks.filterNotNull().map { it.color }.toSet()
        val pick = (0 until LANE_COLOR_COUNT).map { (nextFresh + it) % LANE_COLOR_COUNT }.let { order ->
            order.firstOrNull { it !in active && it !in named } ?: order.firstOrNull { it !in active } ?: order.first()
        }
        nextFresh = pick + 1
        return pick
    }

    // 一条新线：起点挂着分支就用分支的颜色（branchColor），没有就挑一个空闲色
    private fun newTrack(branchColor: Int?) = Track(branchColor ?: freshColor(), nextLine++)
}
