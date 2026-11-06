package gitviz

import java.nio.file.Files
import kotlin.test.*

class RecoveryStoreTest {
    @Test fun `review-required undo exposes recovery action and ordinary errors do not`(): Unit = RepositoryFixture().use { r ->
        r.commit()
        val result = r.cli.coordinator.execute(OperationRequest(OperationKind.FILE, listOf("fixture"), network = true)) {
            r.write("a", "changed\n"); GitResult(null, "")
        }
        assertTrue(result.ok, result.output)
        val undo = r.cli.undo(result.recoveryId)
        assertFalse(undo.ok); assertTrue(undo.needsRecovery)
        assertTrue(describeAction("Undoing", "Undone", undo).needsRecovery)
        assertTrue(describe(NetOp.FETCH, undo).needsRecovery)
        assertFalse(describeAction("Running", "Done", GitResult(GitFailure.OTHER, "Recovery is just text here")).needsRecovery)
        assertFalse(describe(NetOp.FETCH, GitResult(GitFailure.AUTH, "auth failed")).needsRecovery)
        assertEquals("changed\n", r.root.resolve("a").readText())
    }

    @Test fun `quota counts compressed objects rather than raw blob size`() = RepositoryFixture().use { r ->
        r.write("large", "x".repeat(3 * 1024 * 1024)); r.git("add", "large"); r.git("commit", "-qm", "large compressible blob")
        r.cli.recovery.quotaBytes = 1024 * 1024
        r.write("small", "next\n")
        val result = r.cli.stage(r.cli.status().first { it.path == "small" })
        assertTrue(result.ok, result.output)
        assertTrue(r.cli.recovery.size() < 1024 * 1024)
        r.git("gc", "--prune=now")
        assertTrue(r.cli.undo(result.recoveryId).ok)
        assertEquals(3 * 1024 * 1024, r.root.resolve("large").length().toInt())
    }

    @Test fun `revert deletion preserves overwritten ignored file for undo`() = RepositoryFixture().use { r ->
        r.commit(".gitignore", "scratch\n"); r.write("scratch", "tracked original\n"); r.git("add", "-f", "scratch"); r.git("commit", "-qm", "add")
        r.git("rm", "scratch"); r.git("commit", "-qm", "remove"); val deleted = r.git("rev-parse", "HEAD")
        r.write("scratch", "private ignored bytes\n")
        val result = GitRepo.open(r.root).use { r.cli.revert(it.commit(deleted)) }
        assertTrue(result.ok, result.output)
        assertEquals("tracked original\n", r.root.resolve("scratch").readText())
        val undone = r.cli.undo(result.recoveryId)
        assertTrue(undone.ok, undone.output)
        assertEquals("private ignored bytes\n", r.root.resolve("scratch").readText())
    }

    @Test fun `undo stage restores distinct index and worktree byte contents`() = RepositoryFixture().use { r ->
        r.commit(); r.write("a", "staged\n"); r.git("add", "a"); r.write("a", "worktree\n")
        assertTrue(r.cli.stage(r.cli.status().first()).ok)
        val result = r.cli.undo()
        assertTrue(result.ok, result.output)
        assertEquals("staged", r.git("show", ":a"))
        assertEquals("worktree\n", r.root.resolve("a").readText())
    }
    @Test fun `undo first commit restores unborn branch and index`() = RepositoryFixture().use { r ->
        r.write("a", "first\n"); r.git("add", "a")
        assertTrue(r.cli.commit("initial", false).ok)
        val result = r.cli.undo(); assertTrue(result.ok, result.output)
        assertNotEquals(0, r.cli.raw(listOf("rev-parse", "--verify", "HEAD")).exitCode)
        assertEquals("refs/heads/main", r.git("symbolic-ref", "HEAD"))
        assertEquals("first", r.git("show", ":a"))
    }
    @Test fun `undo discard restores untracked binary and symlink`() = RepositoryFixture().use { r ->
        r.commit()
        val bytes = byteArrayOf(0, -1, 13, 10)
        r.root.resolve("binary").writeBytes(bytes)
        assertTrue(r.cli.discard(r.cli.status().first { it.path == "binary" }).ok)
        val result = r.cli.undo(); assertTrue(result.ok, result.output)
        assertContentEquals(bytes, r.root.resolve("binary").readBytes())
        Files.createSymbolicLink(r.root.resolve("link").toPath(), java.nio.file.Path.of("binary"))
        assertTrue(r.cli.discard(r.cli.status().first { it.path == "link" }).ok)
        val restored = r.cli.undo(); assertTrue(restored.ok, restored.output)
        assertEquals("binary", Files.readSymbolicLink(r.root.resolve("link").toPath()).toString())
    }
    @Test fun `external edits prevent ordinary undo`() = RepositoryFixture().use { r ->
        r.commit(); r.write("a", "two\n"); assertTrue(r.cli.stage(r.cli.status().first()).ok)
        r.write("a", "external\n")
        val blocked = r.cli.undo()
        assertFalse(blocked.ok); assertTrue(blocked.needsRecovery)
        assertEquals("external\n", r.root.resolve("a").readText())
    }
    @Test fun `deleted commit can be recovered after source gc`() = RepositoryFixture().use { r ->
        val base = r.commit(); val tip = r.commit(content = "lost\n")
        assertTrue(r.cli.reset(base, ResetMode.HARD).ok)
        r.git("reflog", "expire", "--expire=now", "--all"); r.git("gc", "--prune=now")
        val result = r.cli.undo(); assertTrue(result.ok, result.output)
        assertEquals(tip, r.git("rev-parse", "HEAD")); assertEquals("lost\n", r.root.resolve("a").readText())
    }
    @Test fun `stash middle removal restores ordered list`() = RepositoryFixture().use { r ->
        r.commit()
        repeat(3) { r.write("a", "stash $it\n"); r.git("stash", "push", "-m", "entry $it") }
        val before = r.git("stash", "list", "--format=%H %gs")
        assertTrue(r.cli.dropStash(r.cli.stashList()[1]).ok)
        val result = r.cli.undo(); assertTrue(result.ok, result.output)
        assertEquals(before, r.git("stash", "list", "--format=%H %gs"))
    }
    @Test fun `hard reset protects ignored directory colliding with target file`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.commit("blocked", "tracked\n"); val tip = r.git("rev-parse", "HEAD")
        r.git("reset", "--hard", base); r.write(".gitignore", "blocked\n"); r.write("blocked/local", "valuable\n")
        val result = r.cli.reset(tip, ResetMode.HARD); assertTrue(result.ok, result.output)
        val undone = r.cli.undo(); assertTrue(undone.ok, undone.output)
        assertEquals("valuable\n", r.root.resolve("blocked/local").readText())
        assertEquals(base, r.git("rev-parse", "HEAD"))
    }
    @Test fun `interrupted record blocks new writes until inspected`() = RepositoryFixture().use { r ->
        r.commit()
        val record = r.cli.recovery.begin(OperationRequest.command(listOf("commit", "-m", "pending")))
        val blocked = r.cli.createBranch("other", "HEAD")
        assertFalse(blocked.ok); assertTrue(blocked.needsRecovery)
        assertTrue(r.cli.inspectPoint(record.id).ok)
        assertTrue(r.cli.createBranch("other", "HEAD").ok)
    }
    @Test fun `historical recovery never follows a parent symlink outside the worktree`() = RepositoryFixture().use { r ->
        r.commit("dir/a", "original\n")
        assertTrue(r.cli.createBranch("marker", "HEAD").ok)
        val point = r.cli.recovery.latest()!!
        val outside = Files.createTempDirectory("gitviz-outside").toFile()
        try {
            outside.resolve("a").writeText("sentinel")
            r.root.resolve("dir").deleteRecursively()
            Files.createSymbolicLink(r.root.resolve("dir").toPath(), outside.toPath())
            val result = r.cli.restorePoint(point.id)
            assertTrue(result.ok, result.output)
            assertEquals("sentinel", outside.resolve("a").readText())
            assertEquals("original\n", r.root.resolve("dir/a").readText())
            assertFalse(Files.isSymbolicLink(r.root.resolve("dir").toPath()))
        } finally { outside.deleteRecursively() }
    }

    @Test fun `no change result does not advertise an unrelated undo`() = RepositoryFixture().use { r ->
        r.commit(); r.write("a", "next\n")
        assertNotNull(r.cli.stage(r.cli.status().first()).recoveryId)
        assertNull(r.cli.stage(WorkingFile("a", null, 'M', ' ')).recoveryId)
    }
    @Test fun `damaged recovery content is rejected before changing live state`() = RepositoryFixture().use { r ->
        r.commit(); r.write("a", "next\n"); assertTrue(r.cli.stage(r.cli.status().first()).ok)
        val before = r.cli.recovery.latest()!!.before
        r.cli.recovery.directory.resolve("content/${before.index.getValue("index")}").writeText("broken")
        val head = r.git("rev-parse", "HEAD"); val index = r.git("show", ":a")
        assertFalse(r.cli.undo().ok)
        assertEquals(head, r.git("rev-parse", "HEAD")); assertEquals(index, r.git("show", ":a"))
    }
    @Test fun `symbolic remote head is preserved when its target is restored`() = RepositoryFixture().use { r ->
        val base = r.commit(); val next = r.commit(content = "next\n")
        r.git("update-ref", "refs/remotes/origin/main", base)
        r.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        val result = r.cli.coordinator.execute(OperationRequest(OperationKind.BRANCH, listOf("update remote"))) {
            r.cli.raw(listOf("update-ref", "refs/remotes/origin/main", next)).result()
        }
        assertTrue(result.ok, result.output)
        val undo = r.cli.undo(); assertTrue(undo.ok, undo.output)
        assertEquals(base, r.git("rev-parse", "refs/remotes/origin/main"))
        assertEquals("refs/remotes/origin/main", r.git("symbolic-ref", "refs/remotes/origin/HEAD"))
    }

    @Test fun `quota refuses writes instead of silently dropping recovery`() = RepositoryFixture().use { r ->
        r.commit(); r.cli.recovery.quotaBytes = 1024 * 1024
        val data = ByteArray(2 * 1024 * 1024); java.util.Random(42).nextBytes(data); r.root.resolve("large").writeBytes(data)
        val blocked = r.cli.stage(r.cli.status().first { it.path == "large" })
        assertFalse(blocked.ok); assertTrue(blocked.needsRecovery)
        assertEquals("", r.git("diff", "--cached", "--name-only"))
        assertContentEquals(data, r.root.resolve("large").readBytes())
    }
}
