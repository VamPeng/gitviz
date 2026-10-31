package gitviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefTreeTest {

    private fun refs(vararg names: String) = names.map { RefTarget(RefLabel(it, RefKind.BRANCH), "id-$it") }

    private fun paths(rows: List<RefRow>) = rows.map { "  ".repeat(it.depth) + it.node.name }

    @Test
    fun `slashes become nested folders listed before branches`() {
        val tree = buildRefTree(refs("main", "pyh/fix/a", "pyh/fix/b", "pyh/dev", "codex"))

        assertEquals(
            listOf("pyh", "  fix", "    a", "    b", "  dev", "codex", "main"),
            paths(flattenRefTree(tree) { false }),
        )
    }

    @Test
    fun `collapsed folder hides its children`() {
        val tree = buildRefTree(refs("main", "pyh/fix/a", "pyh/dev"))

        assertEquals(listOf("pyh", "main"), paths(flattenRefTree(tree) { it == "pyh" }))
    }

    @Test
    fun `leaf keeps its ref and folder has none`() {
        val tree = buildRefTree(refs("pyh/dev"))

        assertNull(tree.single().ref)
        assertEquals("id-pyh/dev", tree.single().children.single().ref?.commitId)
    }
}
