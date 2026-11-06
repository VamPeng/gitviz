package gitviz

import java.util.concurrent.CancellationException
import kotlin.test.*

class ReplayTest {
    @Test fun `replay reveals ancestors before children and never changes HEAD index or files`() = RepositoryFixture().use { r ->
        val base = r.commit()
        r.git("switch", "-c", "topic"); val topic = r.commit("b", "topic\n")
        r.git("switch", "main"); val main = r.commit("c", "main\n")
        r.git("merge", "--no-ff", "--no-edit", "topic")
        val tip = r.git("rev-parse", "HEAD")
        r.write("untracked", "preserve\n")
        val index = r.cli.ownDirectory.resolve("index").readBytes()
        ReplaySession.open(r.root).use { replay ->
            assertEquals(4, replay.commits.size)
            assertEquals(base, replay.commits.first().id)
            assertEquals(tip, replay.commits.last().id)
            assertTrue(replay.indexById.getValue(topic) < replay.indexById.getValue(tip))
            assertTrue(replay.indexById.getValue(main) < replay.indexById.getValue(tip))
            replay.commits.indices.forEach { step ->
                val frame = replay.frame(step)
                assertEquals(step + 1, frame.result.commits.size)
                assertEquals(replay.commits[step].id, frame.result.commits.first().id)
                assertNull(frame.headId)
                assertTrue(frame.result.commits.all { c -> c.refs.none { it.kind == RefKind.HEAD || it.isCurrent } })
            }
        }
        assertEquals(tip, r.git("rev-parse", "HEAD"))
        assertEquals("main", r.git("symbolic-ref", "--short", "HEAD"))
        assertContentEquals(index, r.cli.ownDirectory.resolve("index").readBytes())
        assertEquals("preserve\n", r.root.resolve("untracked").readText())
    }

    @Test fun `empty history branch scope cancellation and frozen refs`() = RepositoryFixture().use { r ->
        ReplaySession.open(r.root).use { assertTrue(it.commits.isEmpty()) }
        val base = r.commit(); r.git("branch", "topic"); r.commit("b", "new\n")
        ReplaySession.open(r.root, setOf("refs/heads/topic")).use { replay ->
            assertEquals(listOf(base), replay.commits.map { it.id })
            r.git("branch", "-f", "topic", "main")
            assertEquals(base, replay.commits.single().id)
            assertEquals(base, replay.refs.single { it.label.name == "topic" }.commitId)
        }
        assertFailsWith<CancellationException> { ReplaySession.open(r.root, cancelled = { true }) }
        assertFailsWith<HistoryScopeException> { ReplaySession.open(r.root, setOf("refs/heads/missing")) }
        Unit
    }

    @Test fun `large snapshot keeps full progress while graph viewport is bounded`() = RepositoryFixture().use { r ->
        val total = GitRepo.PAGE_SIZE + 4
        val input = buildString { repeat(total) { i -> append("commit refs/heads/main\ncommitter T <t@test> ${1700000000L + i} +0000\ndata 1\nx\n\n") } }
        r.cli.raw(listOf("fast-import", "--quiet"), input.toByteArray()).requireSuccess()
        ReplaySession.open(r.root).use { replay ->
            assertEquals(total, replay.commits.size)
            assertEquals(GitRepo.PAGE_SIZE, replay.frame(total - 1).result.commits.size)
            assertTrue(replay.frame(total - 1).result.truncated)
            assertEquals(1, replay.frame(0).result.commits.size)
        }
    }
}
