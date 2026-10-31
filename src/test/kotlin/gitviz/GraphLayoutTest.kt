package gitviz

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
