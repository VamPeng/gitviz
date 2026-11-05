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
    init { Settings.language = Language.EN }


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

    private fun load(limit: Int = GitRepo.PAGE_SIZE) = GitRepo.open(dir).use { it.loadCommits(limit) }

    @Test
    fun `refs snapshot changes when a branch is created elsewhere`() {
        commit("one", "a")
        GitRepo.open(dir).use { repo ->
            val before = repo.refsSnapshot()
            assertEquals(before, repo.refsSnapshot())
            // 另一个 Repository 对象建分支，相当于在命令行里建
            git.branchCreate().setName("feature").call()
            val created = repo.refsSnapshot()
            assertTrue(created != before)
            git.checkout().setName("feature").call()
            assertTrue(repo.refsSnapshot() != created)
        }
    }

    @Test
    fun `notes do not show up as commits`() {
        val c = commit("one", "a")
        git.notesAdd().setObjectId(c).setMessage("note").call()

        assertEquals(listOf("one"), load().commits.map { it.shortMessage })
    }

    @Test
    fun `stash shows up once above the commit it was made on`() {
        val base = commit("one", "a")
        File(dir, "a").writeText("dirty")
        git.stashCreate().setWorkingDirectoryMessage("half done").call()

        val commits = load().commits
        // 只有储藏本身，没有 git 内部用来存暂存区的那个提交
        assertEquals(listOf("half done", "one"), commits.map { it.shortMessage })
        val stash = commits.first()
        assertTrue(stash.isStash)
        assertEquals(listOf(base.name), stash.parents)
        assertEquals(listOf("stash@{0}"), stash.refs.map { it.name })
    }

    @Test
    fun `stash waits until the commit it was made on is loaded`() {
        val old = commit("old", "a", 1_700_000_000)
        commit("new1", "b", 1_700_000_100)
        commit("new2", "b", 1_700_000_200)
        git.checkout().setName(old.name).call()
        File(dir, "a").writeText("dirty")
        git.stashCreate().setWorkingDirectoryMessage("on old").call()
        git.checkout().setName("main").call()

        GitRepo.open(dir).use { repo ->
            val pager = repo.openHistory()
            assertTrue(pager.load(2).commits.none { it.isStash })
            val all = pager.load(10).commits.map { it.shortMessage }
            assertTrue("on old" in all)
            assertTrue(all.indexOf("on old") < all.indexOf("old"))
        }
    }

    @Test
    fun `path history keeps only commits touching the file and links them`() {
        val a1 = commit("a1", "a", 1_700_000_000)
        commit("b1", "b", 1_700_000_100)
        val a2 = commit("a2", "a", 1_700_000_200)
        commit("b2", "b", 1_700_000_300)
        File(dir, "b").writeText("dirty")
        git.stashCreate().call()

        GitRepo.open(dir).use { repo ->
            val result = repo.openHistory("a").load(GitRepo.PAGE_SIZE)
            assertEquals(listOf("a2", "a1"), result.commits.map { it.shortMessage })
            // 父提交改写成过滤后历史里的祖先，跳过了 b1
            assertEquals(listOf(a1.name), result.commits.first().parents)
            assertEquals(listOf(a2.name), repo.searchCommits("2", path = "a").ids)
        }
    }

    @Test
    fun `empty commit has no changes`() {
        commit("one", "a")
        val empty = commit("empty")

        GitRepo.open(dir).use { assertTrue(it.changes(empty.name).isEmpty()) }
    }

    @Test
    fun `display name comes from the real folder name`() {
        val viaDot = File(dir, ".")
        assertEquals(dir.canonicalFile.name, GitRepo.open(viaDot).use { it.displayName })
    }

    @Test
    fun `changes count added and removed lines`() {
        File(dir, "a").writeText("one\ntwo\nthree\n")
        git.add().addFilepattern("a").call()
        val base = commit("add a")
        File(dir, "a").writeText("one\n2\nthree\nfour\n")
        git.add().addFilepattern("a").call()
        val edit = commit("edit a")

        GitRepo.open(dir).use { repo ->
            assertEquals(3 to 0, repo.changes(base.name).single().let { it.additions to it.deletions })
            assertEquals(2 to 1, repo.changes(edit.name).single().let { it.additions to it.deletions })
        }
    }

    @Test
    fun `file history only has commits that touch the file`() {
        commit("first a", "a", 1_700_000_000)
        commit("only b", "b", 1_700_000_100)
        val last = commit("second a", "a", 1_700_000_200)

        val history = GitRepo.open(dir).use { it.fileHistory(last.name, "a") }
        assertEquals(listOf("second a", "first a"), history.map { it.shortMessage })
    }

    @Test
    fun `blame names the commit that last changed each line`() {
        File(dir, "a").writeText("keep\nold\n")
        git.add().addFilepattern("a").call()
        val first = commit("first", null, 1_700_000_000)
        File(dir, "a").writeText("keep\nnew\n")
        git.add().addFilepattern("a").call()
        val second = commit("second", null, 1_700_000_100)

        val lines = GitRepo.open(dir).use { it.blame(second.name, "a") }
        assertEquals(listOf("keep", "new"), lines.map { it.text })
        assertEquals(listOf(first.name, second.name), lines.map { it.commitId })
        assertEquals(listOf(1, 2), lines.map { it.lineNo })
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

    @Test
    fun `author and committer are kept apart`() {
        File(dir, "a").writeText("x")
        git.add().addFilepattern("a").call()
        val author = PersonIdent("ann", "ann@a", Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC)
        val committer = PersonIdent("bob", "bob@b", Instant.ofEpochSecond(1_700_050_000), ZoneOffset.UTC)
        git.commit().setMessage("picked").setAuthor(author).setCommitter(committer).setSign(false).call()

        val c = load().commits.single()
        assertEquals("ann" to 1_700_000_000L, c.author to c.timeSeconds)
        assertEquals("bob" to 1_700_050_000L, c.committer to c.commitTimeSeconds)
        assertTrue(c.committedSeparately)
    }

    @Test
    fun `merge commit can be compared with either parent`() {
        commit("root", "base")
        git.branchCreate().setName("side").call()
        commit("main change", "m")
        git.checkout().setName("side").call()
        commit("side change", "s")
        git.checkout().setName("main").call()
        val merge = git.merge().include(git.repository.resolve("side")).setMessage("merge")
            .setCommit(false).call()
        assertTrue(merge.mergeStatus.isSuccessful)
        val mergeCommit = commit("merge side")

        GitRepo.open(dir).use { repo ->
            assertEquals(listOf("s"), repo.changes(mergeCommit.name, 0).map { it.path })
            assertEquals(listOf("m"), repo.changes(mergeCommit.name, 1).map { it.path })
        }
    }

    @Test
    fun `mode only change is reported`() {
        commit("add script", "run.sh")
        File(dir, "run.sh").setExecutable(true)
        git.add().addFilepattern("run.sh").call()
        val chmod = commit("make executable")

        GitRepo.open(dir).use { repo ->
            val change = repo.changes(chmod.name).single()
            assertEquals(ModeChange("100644", "100755"), change.modeChange)
            assertEquals("Mode 100644 → 100755 (made executable); content unchanged", emptyDiffMessage(parseDiffLines(repo.diffText(change))))
        }
    }

    @Test
    fun `history loads page by page until it runs out`() {
        repeat(5) { commit("c$it", "a", 1_700_000_000L + it) }

        GitRepo.open(dir).use { repo ->
            val pager = repo.openHistory()
            val first = pager.load(2)
            assertEquals(listOf("c4", "c3"), first.commits.map { it.shortMessage })
            assertTrue(first.truncated)
            val second = pager.load(2)
            assertEquals(listOf("c4", "c3", "c2", "c1"), second.commits.map { it.shortMessage })
            assertEquals(2, second.appendedFrom) // 前一页没动，新的接在后面
            val last = pager.load(2)
            assertEquals(5, last.commits.size)
            assertFalse(last.truncated)
            assertEquals(5, pager.load(2).commits.size)
        }
    }

    @Test
    fun `a page that ends exactly at the root is not truncated`() {
        repeat(4) { commit("c$it", "a", 1_700_000_000L + it) }

        GitRepo.open(dir).use { repo ->
            val pager = repo.openHistory()
            assertTrue(pager.load(2).truncated)
            assertFalse(pager.load(2).truncated)
        }
    }

    @Test
    fun `skewed child read on a later page still sorts before its parent`() {
        commit("root", "a", 1_700_000_000)
        commit("old-clock-parent", "b", 1_700_009_000)
        git.branchCreate().setName("side").call()
        commit("skewed-child", "c", 1_700_000_100)
        git.checkout().setName("side").call()
        commit("side", "d", 1_700_000_200)
        git.checkout().setName("main").call()

        GitRepo.open(dir).use { repo ->
            val pager = repo.openHistory()
            var result = pager.load(1)
            while (result.truncated) result = pager.load(1)
            val messages = result.commits.map { it.shortMessage }
            assertTrue(messages.indexOf("skewed-child") < messages.indexOf("old-clock-parent"))
            assertTrue(messages.indexOf("side") < messages.indexOf("old-clock-parent"))
            assertEquals("root", messages.last())
        }
    }

    @Test
    fun `a skewed child on a later page makes the whole history re-sort`() {
        commit("root", "a", 1_700_000_000)
        commit("old-clock-parent", "b", 1_700_009_000)
        git.branchCreate().setName("side").call()
        git.checkout().setName("side").call()
        commit("skewed-child", "c", 1_700_000_100)
        git.checkout().setName("main").call()

        GitRepo.open(dir).use { repo ->
            val pager = repo.openHistory()
            // 按时间先读到 old-clock-parent，它的子提交 skewed-child 在后一页才读到
            assertEquals(listOf("old-clock-parent"), pager.load(1).commits.map { it.shortMessage })
            val next = pager.load(1)
            assertEquals(0, next.appendedFrom)
            assertEquals(listOf("skewed-child", "old-clock-parent"), next.commits.map { it.shortMessage })
        }
    }

    @Test
    fun `search covers history that is not loaded yet`() {
        val old = commit("Fix login bug", "a", 1_700_000_000)
        repeat(3) { commit("c$it", "a", 1_700_000_100L + it) }
        git.tag().setName("release-1").setObjectId(git.repository.parseCommit(old)).call()

        GitRepo.open(dir).use { repo ->
            assertEquals(listOf(old.name), repo.searchCommits("LOGIN").ids)
            assertEquals(listOf(old.name), repo.searchCommits(old.name.take(6)).ids)
            assertEquals(listOf(old.name), repo.searchCommits("release").ids)
            assertTrue(repo.searchCommits("c").complete)
            assertEquals(2, repo.searchCommits("c", limit = 2).let { assertFalse(it.complete); it.ids.size })
            assertFalse(repo.searchCommits("c", cancelled = { true }).complete)
        }
    }
}
