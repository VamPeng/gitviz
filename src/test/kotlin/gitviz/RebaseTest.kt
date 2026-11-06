package gitviz

import kotlin.test.*

class RebaseTest {
    @Test fun `multiple pauses survive restart and undo restores original branch`() = RepositoryFixture().use { r ->
        r.commit("a", "base\n"); r.commit("b", "base\n")
        r.git("branch", "topic")
        r.commit("a", "main\n"); r.commit("b", "main\n")
        r.git("switch", "topic")
        r.commit("a", "topic\n"); val original = r.commit("b", "topic\n")
        assertEquals(GitFailure.CONFLICT, r.cli.rebase("main").failure)
        val restarted = GitCli(r.root)
        assertEquals(GitOperation.REBASE, restarted.currentOperation())
        assertTrue(restarted.resolveWhole("a", false).ok)
        assertEquals(GitFailure.CONFLICT, restarted.continueOperation().failure)
        assertTrue(restarted.resolveWhole("b", false).ok)
        val done = restarted.continueOperation()
        assertTrue(done.ok, done.output)
        assertNull(restarted.currentOperation())
        assertEquals("topic", r.git("branch", "--show-current"))
        assertNull(done.recoveryId)
        assertFalse(restarted.undo().ok)
        val point = restarted.recovery.latest()!!
        assertTrue(restarted.restorePoint(point.id, restarted.recovery.preview(point.id).fingerprint).ok)
        assertEquals(original, r.git("rev-parse", "HEAD"))
        assertEquals("topic\n", r.root.resolve("a").readText())
    }

    @Test fun `external replacement rebase does not acquire an old recovery parent`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("a", "main\n"); r.git("switch", "topic"); r.commit("a", "topic\n")
        r.cli.rebase("main")
        val old = r.cli.recovery.records().first { it.state == RecordState.CONFLICTED }.id
        r.git("rebase", "--abort")
        r.commit("new", "later\n")
        GitProcess.execute(r.root, listOf("rebase", "main"))
        assertTrue(r.cli.resolveWhole("a", false).ok)
        val result = r.cli.continueOperation()
        assertTrue(result.ok, result.output)
        assertNull(result.recoveryId)
        assertEquals(RecordState.CONFLICTED, r.cli.recovery.records().first { it.id == old }.state)
    }

    @Test fun `abort restores tip and rebase control directory defines paused state`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("a", "main\n"); r.git("switch", "topic")
        val original = r.commit("a", "topic\n")
        r.cli.rebase("main")
        r.cli.ownDirectory.resolve("REBASE_HEAD").delete()
        assertEquals(GitOperation.REBASE, GitCli(r.root).currentOperation())
        assertTrue(r.cli.abortOperation().ok)
        assertEquals(original, r.git("rev-parse", "HEAD"))
    }

    @Test fun `keep empty result after conflict and dirty preflight`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("a", "main\n"); r.git("switch", "topic"); r.commit("a", "topic\n")
        r.write("dirty", "x")
        assertFalse(r.cli.rebase("main").ok)
        r.root.resolve("dirty").delete()
        assertEquals(GitFailure.CONFLICT, r.cli.rebase("main").failure)
        assertTrue(r.cli.resolveWhole("a", true).ok)
        assertTrue(r.cli.rebaseProgress()!!.canKeepEmpty)
        assertTrue(r.cli.keepEmptyRebaseCommit().ok)
        assertEquals(r.git("rev-parse", "HEAD^{tree}"), r.git("rev-parse", "HEAD^1^{tree}"))
        assertEquals("topic", r.git("log", "-1", "--format=%s"))
    }

    @Test fun `unexpected empty patch pauses without conflicts and can be skipped`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic")
        r.write("a", "same\n"); r.write("extra", "extra\n"); r.git("add", "."); r.git("commit", "-qm", "combined")
        r.git("switch", "topic"); r.commit("a", "same\n")
        val paused = r.cli.rebase("main")
        assertEquals(GitFailure.PAUSED, paused.failure, paused.output)
        assertTrue(r.cli.status().isEmpty())
        assertEquals(GitOperation.REBASE, GitCli(r.root).currentOperation())
        assertTrue(r.cli.rebaseStep("--skip").ok)
        assertEquals(r.git("rev-parse", "main"), r.git("rev-parse", "HEAD"))
    }

    @Test fun `original empty commits remain`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("main", "main\n"); r.git("switch", "topic")
        r.git("commit", "--allow-empty", "-qm", "intentional")
        val result = r.cli.rebase("main")
        assertTrue(result.ok, result.output)
        assertEquals("intentional", r.git("log", "-1", "--format=%s"))
        assertEquals(r.git("rev-parse", "HEAD^{tree}"), r.git("rev-parse", "HEAD^1^{tree}"))
    }

    @Test fun `merge topology retained without rewriting other branches`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("mainfile", "main\n"); r.git("switch", "topic")
        r.commit("left", "left\n"); r.git("checkout", "-b", "side", "HEAD^")
        r.commit("right", "right\n"); r.git("switch", "topic"); r.git("merge", "--no-ff", "--no-edit", "side")
        val side = r.git("rev-parse", "side")
        r.git("config", "rebase.updateRefs", "true")
        val done = r.cli.rebase("main")
        assertTrue(done.ok, done.output)
        assertEquals(3, r.git("rev-list", "--parents", "-n", "1", "HEAD").split(' ').size)
        assertEquals(side, r.git("rev-parse", "side"))
        assertTrue(r.root.resolve("mainfile").exists())
    }
}
