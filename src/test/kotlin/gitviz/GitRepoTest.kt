package gitviz

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRepoTest {

    private val dir: File = createTempDirectory("gitviz-test").toFile()
    private val git: Git = Git.init().setDirectory(dir).setInitialBranch("main").call()

    @AfterTest
    fun cleanUp() {
        git.close()
        dir.deleteRecursively()
    }

    private fun commit(message: String, file: String? = null, epochSeconds: Long = 1_700_000_000): RevCommit {
        if (file != null) {
            File(dir, file).writeText(message)
            git.add().addFilepattern(file).call()
        }
        val ident = PersonIdent("t", "t@t", Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
        return git.commit().setMessage(message).setAuthor(ident).setCommitter(ident)
            .setAllowEmpty(true).setSign(false).call()
    }

    private fun load(limit: Int = GitRepo.MAX_COMMITS) = GitRepo.open(dir).use { it.loadCommits(limit) }

    @Test
    fun `notes do not show up as commits`() {
        val c = commit("one", "a")
        git.notesAdd().setObjectId(c).setMessage("note").call()

        assertEquals(listOf("one"), load().commits.map { it.shortMessage })
    }

    @Test
    fun `stash does not show up as commits`() {
        commit("one", "a")
        File(dir, "a").writeText("dirty")
        git.stashCreate().call()

        assertEquals(listOf("one"), load().commits.map { it.shortMessage })
    }

    @Test
    fun `empty commit has no changes`() {
        commit("one", "a")
        val empty = commit("empty")

        GitRepo.open(dir).use { assertTrue(it.changes(empty.name).isEmpty()) }
    }

    @Test
    fun `limit truncates the history`() {
        repeat(5) { commit("c$it", "a", 1_700_000_000L + it) }

        val capped = load(limit = 3)
        assertEquals(listOf("c4", "c3", "c2"), capped.commits.map { it.shortMessage })
        assertTrue(capped.truncated)
        assertFalse(load(limit = 5).truncated)
    }

    @Test
    fun `child stays before its parent when clocks are skewed`() {
        commit("root", "a", 1_700_000_000)
        val base = commit("old-clock-parent", "b", 1_700_009_000)
        git.branchCreate().setName("side").call()
        commit("skewed-child", "c", 1_700_000_100)
        git.checkout().setName("side").call()
        commit("side", "d", 1_700_000_200)
        git.checkout().setName("main").call()

        val messages = load().commits.map { it.shortMessage }
        assertEquals(base.shortMessage, messages[messages.size - 2])
        assertTrue(messages.indexOf("skewed-child") < messages.indexOf(base.shortMessage))
        assertTrue(messages.indexOf("side") < messages.indexOf(base.shortMessage))
    }
}
