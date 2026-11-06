package gitviz

import kotlin.test.*

class ExpectedTopologyTest {
    @Test fun `projected labels reset and merge preserve planned topology with separate identities`() {
        fun commit(id: String) = CommitInfo(id, listOf("original-parent"), id, id, "a", "a@b", 0, emptyList())
        val expected = expectedTopology(listOf("label base", "pick A one", "label side", "reset base", "pick B two", "merge -C M side # merge"), "onto", emptyMap(), ::commit)
        val merge = expected.first(); val b = expected[1]; val a = expected[2]
        assertEquals(listOf(b.id, a.id), merge.parents)
        assertEquals(listOf("onto"), a.parents); assertEquals(listOf("onto"), b.parents)
        assertTrue(expected.all { it.id.startsWith("expected:") && it.refs.isEmpty() })
        assertFails { expectedTopology(listOf("reset missing"), "onto", emptyMap(), ::commit) }
    }
}
