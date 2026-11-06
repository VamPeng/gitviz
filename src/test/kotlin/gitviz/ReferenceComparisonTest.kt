package gitviz

import org.eclipse.jgit.lib.*
import kotlin.test.*

class ReferenceComparisonTest {
    @Test fun `symmetric difference and directional file comparison use frozen tips`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.git("branch", "b"); val a = r.commit("a-only", "a\n"); r.git("switch", "b"); val b = r.commit("b-only", "b\n")
        ComparisonSession.open(r.root, CompareRequest("main", "b")).use { session ->
            assertEquals(a, session.a); assertEquals(b, session.b)
            assertEquals(listOf(1L, 1L), session.counts)
            assertEquals(listOf(base), session.bases)
            assertEquals(listOf(a), session.load(true).commits.map { it.id })
            assertEquals(listOf(b), session.load(false).commits.map { it.id })
            assertEquals(listOf("b-only"), session.files(false, base).map { it.path })
            assertEquals(setOf("a-only", "b-only"), session.files(true, null).map { it.path }.toSet())
            r.commit("after", "after\n")
            assertEquals(listOf("b-only"), session.files(false, base).map { it.path })
        }
        ComparisonSession.open(r.root, CompareRequest("main", "main")).use { assertEquals(listOf(0L, 0L), it.counts); assertTrue(it.load(true).commits.isEmpty()) }
    }
    @Test fun `unrelated and multiple ancestors are explicit`() = RepositoryFixture().use { r ->
        val root = r.commit(); val tree = r.git("rev-parse", "HEAD^{tree}")
        fun commit(message: String, vararg parents: String): String = r.cli.raw(listOf("commit-tree", tree) + parents.flatMap { listOf("-p", it) }, "$message\n".toByteArray()).requireSuccess().decodeToString().trim()
        val a = commit("a", root); val b = commit("b", root)
        val left = commit("left", a, b); val right = commit("right", b, a)
        ComparisonSession.open(r.root, CompareRequest(left, right)).use {
            assertEquals(setOf(a, b), it.bases.toSet()); assertFails { it.files(false, null) }; assertTrue(it.files(false, a).isEmpty())
        }
        val orphan = commit("orphan")
        ComparisonSession.open(r.root, CompareRequest(root, orphan)).use { assertTrue(it.bases.isEmpty()); assertTrue(it.files(false, null).isEmpty()) }
    }
    @Test fun `independent cursors page beyond 200 without following moved refs`() = RepositoryFixture().use { r ->
        val root = r.commit(); val tree = ObjectId.fromString(r.git("rev-parse", "HEAD^{tree}"))
        var tip = ObjectId.fromString(root)
        GitRepo.open(r.root).use { repo -> repo.repository.newObjectInserter().use { insert ->
            repeat(205) { n -> val commit = CommitBuilder().apply {
                setTreeId(tree); setParentId(tip); author = PersonIdent("Test", "test@example.com"); committer = author; message = "item $n"
            }; tip = insert.insert(commit) }
            insert.flush()
        } }
        r.git("update-ref", "refs/heads/many", tip.name)
        ComparisonSession.open(r.root, CompareRequest("many", "main")).use { session ->
            val first = session.load(true)
            assertEquals(200, first.commits.size); assertTrue(first.hasMore)
            r.git("update-ref", "refs/heads/many", root)
            val second = session.load(true)
            assertEquals(5, second.commits.size); assertFalse(second.hasMore)
            assertEquals(205, (first.commits + second.commits).map { it.id }.toSet().size)
            assertTrue(session.load(false).commits.isEmpty())
        }
    }
}
