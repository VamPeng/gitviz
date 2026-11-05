package gitviz

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GraphLayoutTest {

    private fun commit(id: String, vararg parents: String) =
        CommitInfo(id, parents.toList(), id, id, "a", "a@a", 0, emptyList())

    private fun ids(commits: List<CommitInfo>) = commits.map { it.id }

    @Test
    fun `linear history stays in one lane`() {
        val rows = GraphLayout.compute(listOf(commit("c", "b"), commit("b", "a"), commit("a")))
        assertEquals(listOf(0, 0, 0), rows.map { it.node })
        assertEquals(listOf(1, 1, 1), rows.map { it.width })
    }

    @Test
    fun `merged branch gets its own lane and rejoins at the fork point`() {
        val rows = GraphLayout.compute(
            listOf(commit("m", "x", "f"), commit("x", "a"), commit("f", "a"), commit("a")),
        )
        assertContentEquals(intArrayOf(0, 1), rows[0].outgoing)
        assertContentEquals(intArrayOf(1), rows[1].passThrough)
        assertEquals(1, rows[2].node)
        assertContentEquals(intArrayOf(0, 1), rows[3].incoming)
    }

    @Test
    fun `unrelated root takes a free lane and leaves no line behind`() {
        val rows = GraphLayout.compute(listOf(commit("b", "a"), commit("orphan"), commit("a")))
        assertEquals(1, rows[1].node)
        assertContentEquals(intArrayOf(), rows[1].outgoing)
        assertEquals(1, rows[2].width)
    }

    private fun withRefs(c: CommitInfo, vararg refs: RefLabel) = c.copy(refs = refs.toList())

    @Test
    fun `a line keeps one color and one line id all the way down`() {
        val rows = GraphLayout.compute(listOf(commit("c", "b"), commit("b", "a"), commit("a")))
        assertEquals(1, rows.map { it.nodeTrack }.distinct().size)
    }

    @Test
    fun `merged branch is a different line and takes its branch color`() {
        val commits = listOf(
            withRefs(commit("m", "x", "f"), RefLabel("main", RefKind.BRANCH, isCurrent = true)),
            commit("x", "a"),
            withRefs(commit("f", "a"), RefLabel("feature", RefKind.BRANCH)),
            commit("a"),
        )
        val colors = BranchColors(
            listOf(
                RefTarget(RefLabel("main", RefKind.BRANCH, isCurrent = true), "m"),
                RefTarget(RefLabel("feature", RefKind.BRANCH), "f"),
            ),
        )
        val rows = GraphLayout.compute(commits, colors)
        assertEquals(0, rows[0].nodeTrack.color) // 当前分支排第一
        assertEquals(1, rows[2].nodeTrack.color)
        assertNotEquals(rows[0].nodeTrack.line, rows[2].nodeTrack.line)
        // 合并线属于 feature 这条线，和 f 同色同编号
        assertEquals(rows[2].nodeTrack, rows[0].outgoingTracks[1])
        // 回到分叉点后沿用 main 的线
        assertEquals(rows[0].nodeTrack, rows[3].nodeTrack)
    }

    @Test
    fun `remote branch shares the color of the local one`() {
        val colors = BranchColors(
            listOf(
                RefTarget(RefLabel("zeta", RefKind.BRANCH), "1"),
                RefTarget(RefLabel("origin/zeta", RefKind.REMOTE), "1"),
                RefTarget(RefLabel("v1", RefKind.TAG), "1"),
            ),
        )
        assertEquals(colors.index(RefLabel("zeta", RefKind.BRANCH)), colors.index(RefLabel("origin/zeta", RefKind.REMOTE)))
        assertEquals(null, colors.index(RefLabel("v1", RefKind.TAG)))
    }

    @Test
    fun `topoSort keeps an order that is already topological`() {
        val commits = listOf(commit("m", "x", "f"), commit("x", "a"), commit("f", "a"), commit("a"))
        assertEquals(ids(commits), ids(GraphLayout.topoSort(commits)))
    }

    @Test
    fun `topoSort moves a parent after all of its children`() {
        val sorted = GraphLayout.topoSort(
            listOf(commit("x", "a"), commit("a"), commit("z"), commit("y", "a")),
        )
        assertEquals(listOf("x", "z", "y", "a"), ids(sorted))
    }

    @Test
    fun `topoSort ignores parents outside the loaded range`() {
        val commits = listOf(commit("b", "a"), commit("a", "not-loaded"))
        assertEquals(listOf("b", "a"), ids(GraphLayout.topoSort(commits)))
    }

    @Test
    fun `layout added in pieces has the same shape as all at once`() {
        val commits = listOf(
            commit("m2", "m1", "g"), commit("g", "f"), commit("m1", "x", "f"), commit("x", "a"),
            commit("f", "a"), commit("a", "r"), commit("r"),
        )
        val whole = GraphLayout.compute(commits)
        val builder = GraphBuilder()
        val first = builder.add(commits.take(3))
        val all = builder.add(commits.drop(3))
        assertEquals(3, first.size) // 之前拿到的快照不随后面的 add 变
        assertEquals(commits.size, builder.size)
        assertEquals(whole.map { it.node }, all.map { it.node })
        assertEquals(whole.map { it.width }, all.map { it.width })
        assertEquals(whole.map { it.passThrough.toList() }, all.map { it.passThrough.toList() })
        assertEquals(whole.map { it.incoming.toList() }, all.map { it.incoming.toList() })
        assertEquals(whole.map { it.outgoing.toList() }, all.map { it.outgoing.toList() })
        assertEquals(whole.map { it.nodeTrack.line }, all.map { it.nodeTrack.line })
    }

    @Test
    fun `merged branch keeps its color when its tip is on the next page`() {
        val commits = listOf(
            withRefs(commit("m", "x", "f"), RefLabel("main", RefKind.BRANCH, isCurrent = true)),
            commit("x", "a"),
            withRefs(commit("f", "a"), RefLabel("feature", RefKind.BRANCH)),
            commit("a"),
        )
        val colors = BranchColors(
            listOf(
                RefTarget(RefLabel("main", RefKind.BRANCH, isCurrent = true), "m"),
                RefTarget(RefLabel("feature", RefKind.BRANCH), "f"),
            ),
        )
        val whole = GraphLayout.compute(commits, colors)
        val builder = GraphBuilder(colors)
        builder.add(commits.take(2)) // f 在下一页
        val paged = builder.add(commits.drop(2))
        assertEquals(whole.map { it.nodeTrack.color }, paged.map { it.nodeTrack.color })
        assertEquals(whole[0].outgoingTracks.map { it.color }, paged[0].outgoingTracks.map { it.color })
        assertEquals(colors.index(RefLabel("feature", RefKind.BRANCH)), paged[0].outgoingTracks[1].color)
    }
}
