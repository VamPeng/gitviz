package gitviz

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用本地裸仓库当 origin，推送不会碰到任何真实的远程。 */
class GitCliTest {

    private val root: File = createTempDirectory("gitviz-cli").toFile()
    private val origin = File(root, "origin.git")
    private val work = File(root, "work")
    private val other = File(root, "other")

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    private fun setUp(dir: File) {
        git(dir, "config", "user.name", "t")
        git(dir, "config", "user.email", "t@t")
        git(dir, "config", "commit.gpgsign", "false")
    }

    private fun commit(dir: File, file: String, content: String) {
        File(dir, file).writeText(content)
        git(dir, "add", file)
        git(dir, "commit", "-q", "-m", "edit $file")
    }

    private fun cloneTo(dir: File) {
        git(root, "clone", "-q", origin.path, dir.path)
        setUp(dir)
    }

    private fun prepare() {
        git(root, "init", "-q", "--bare", "--initial-branch=main", origin.path)
        git(root, "init", "-q", "--initial-branch=main", work.path)
        setUp(work)
        git(work, "remote", "add", "origin", origin.path)
        commit(work, "a", "one\n")
        git(work, "push", "-q", "-u", "origin", "main")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `push sends new commits to origin`() {
        prepare()
        commit(work, "a", "two\n")

        assertTrue(GitCli(work).push().ok)
        assertEquals(git(work, "rev-parse", "HEAD"), git(origin, "rev-parse", "main"))
    }

    @Test
    fun `push sets the upstream of a new branch`() {
        prepare()
        git(work, "checkout", "-q", "-b", "feature")
        commit(work, "b", "x\n")

        assertTrue(GitCli(work).push().ok)
        assertEquals("origin/feature", git(work, "rev-parse", "--abbrev-ref", "@{upstream}"))
    }

    @Test
    fun `push is rejected when origin has new commits and pull fixes it`() {
        prepare()
        cloneTo(other)
        commit(other, "b", "theirs\n")
        git(other, "push", "-q")
        commit(work, "c", "ours\n")

        val cli = GitCli(work)
        val rejected = cli.push()
        assertEquals(GitFailure.REJECTED, rejected.failure)

        assertTrue(cli.pull().ok)
        assertTrue(cli.push().ok)
        assertEquals(git(work, "rev-parse", "HEAD"), git(origin, "rev-parse", "main"))
    }

    @Test
    fun `pull reports a conflict`() {
        prepare()
        cloneTo(other)
        commit(other, "a", "theirs\n")
        git(other, "push", "-q")
        commit(work, "a", "ours\n")

        assertEquals(GitFailure.CONFLICT, GitCli(work).pull().failure)
    }

    @Test
    fun `detached head cannot push`() {
        prepare()
        git(work, "checkout", "-q", "--detach")

        val result = GitCli(work).push()
        assertFalse(result.ok)
        assertEquals(GitFailure.OTHER, result.failure)
    }

    @Test
    fun `rejected push offers pull as the next step`() {
        val status = describe(NetOp.PUSH, GitResult(GitFailure.REJECTED, "! [rejected] main -> main (fetch first)"))
        assertEquals(NetState.FAILED, status.state)
        assertEquals(NetOp.PULL, status.next)
    }

    @Test
    fun `failures are classified from git output`() {
        assertEquals(GitFailure.AUTH, classifyFailure("git@github.com: Permission denied (publickey)."))
        assertEquals(GitFailure.AUTH, classifyFailure("fatal: could not read Username for 'https://github.com'"))
        assertEquals(GitFailure.AUTH, classifyFailure("Host key verification failed."))
        assertEquals(GitFailure.OTHER, classifyFailure("fatal: not a git repository"))
    }
}
