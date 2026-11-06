package gitviz

import kotlin.test.*

class HistoryQueryTest {
    @Test fun `branch union intersects file search and freezes paging roots`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.git("branch", "side")
        val main = r.commit(content = "main needle\n")
        r.git("switch", "side"); val side = r.commit("b", "side needle\n")
        GitRepo.open(r.root).use { repo ->
            val single = repo.openHistory(HistoryQuery(setOf("refs/heads/main")))
            assertEquals(listOf(main), single.load(1).commits.map { it.id })
            val both = repo.openHistory(HistoryQuery(setOf("refs/heads/main", "refs/heads/side")))
            assertEquals(setOf(main, side, base), both.load(20).commits.map { it.id }.toSet())
            val file = repo.openHistory(HistoryQuery(setOf("refs/heads/main", "refs/heads/side"), "a"))
            assertEquals(setOf(main, base), file.load(20).commits.map { it.id }.toSet())
            assertEquals(listOf(main), repo.searchCommits("needle", path = "a", startIds = file.startIds).ids)
            r.git("update-ref", "refs/heads/main", side)
            assertEquals(setOf(main, base), single.load(20).commits.map { it.id }.toSet())
            assertEquals(listOf(main), repo.searchCommits("needle", startIds = single.startIds, refNames = single.refNames).ids)
            assertEquals(listOf(main), repo.searchCommits("main", startIds = single.startIds, refNames = single.refNames).ids)
            assertTrue(repo.isReachable(main, single.startIds))
            assertFalse(repo.isReachable(side, single.startIds))
            assertFailsWith<IllegalStateException> { repo.openHistory(HistoryQuery(setOf("refs/heads/missing"))) }
            assertEquals(listOf("a"), repo.changesBetween(base, main).map { it.path })
        }
    }
}
