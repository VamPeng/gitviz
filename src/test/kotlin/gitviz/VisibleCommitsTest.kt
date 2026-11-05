package gitviz

import org.eclipse.jgit.api.Git
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VisibleCommitsTest {
    private val dir = createTempDirectory("gitviz-filter").toFile()
    private val repo = run {
        Git.init().setDirectory(dir).call().close()
        GitRepo.open(dir)
    }

    @AfterTest
    fun cleanUp() {
        repo.close()
        dir.deleteRecursively()
    }

    private fun commit(id: Char, message: String) =
        CommitInfo(id.toString().repeat(40), emptyList(), message, message, "a", "a@a", 0, emptyList())

    private fun state(vararg commits: CommitInfo) =
        RepoState(repo, LoadResult(commits.toList(), false, null, emptyList()), emptyList(), null)

    @Test
    fun `blank query or no result yet shows everything`() {
        val s = state(commit('a', "fix"), commit('b', "feat"))
        assertEquals(listOf(0, 1), visibleCommits(s, "", Filtered(s, "fix", listOf(0))))
        assertEquals(listOf(0, 1), visibleCommits(s, "fix", null))
    }

    @Test
    fun `result for the same state is used even while a newer query is computing`() {
        val s = state(commit('a', "fix"), commit('b', "feat"))
        assertEquals(listOf(0), visibleCommits(s, "fixe", Filtered(s, "fix", listOf(0))))
    }

    @Test
    fun `result from an older state is remapped by id`() {
        val a = commit('a', "fix a")
        val b = commit('b', "other")
        val c = commit('c', "fix c")
        val old = state(a, c)
        // 新的一页插进来一个提交，下标都变了
        val new = state(b, c, a)
        assertEquals(listOf(1, 2), visibleCommits(new, "fix", Filtered(old, "fix", listOf(0, 1))))
        // 刷新后提交没了，就不显示
        assertEquals(listOf(0), visibleCommits(state(c), "fix", Filtered(old, "fix", listOf(0, 1))))
    }
}
