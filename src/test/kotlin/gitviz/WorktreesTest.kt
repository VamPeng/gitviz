package gitviz

import kotlin.test.*

class WorktreesTest {
    @Test fun `hidden index flags block removal without changing files or index`(): Unit = RepositoryFixture().use { r ->
        val name = "local config\nfile"
        r.commit(name, "committed\n")
        for (flag in listOf("assume-unchanged", "skip-worktree", "both")) {
            val target = r.root.resolveSibling(r.root.name + " flagged " + flag)
            try {
                assertTrue(r.cli.addWorktree(target.path, "flagged-$flag").ok)
                val entry = r.cli.worktrees().single { it.path == target.canonicalPath }
                val child = GitCli(target, isolated = true)
                fun git(vararg args: String) = GitProcess.execute(target, args.toList(), isolated = true).requireSuccess()
                if (flag != "skip-worktree") git("update-index", "--assume-unchanged", "--", name)
                if (flag != "assume-unchanged") git("update-index", "--skip-worktree", "--", name)
                val index = child.ownDirectory.resolve("index").readBytes()
                // Even a genuinely clean flagged index must be rejected conservatively.
                assertFalse(r.cli.removeWorktree(entry).ok)
                assertTrue(target.isDirectory)
                target.resolve(name).writeText("uncommitted local configuration\n")
                assertTrue(child.treeStatus().isEmpty(), "Fixture must hide the modified file from status")
                val result = r.cli.removeWorktree(entry)
                assertFalse(result.ok)
                assertTrue("assume-unchanged" in result.output && "skip-worktree" in result.output)
                assertEquals("uncommitted local configuration\n", target.resolve(name).readText())
                assertContentEquals(index, child.ownDirectory.resolve("index").readBytes())
                git("update-index", "--no-assume-unchanged", "--", name)
                git("update-index", "--no-skip-worktree", "--", name)
                assertTrue(child.status().isNotEmpty())
                assertFalse(r.cli.removeWorktree(entry).ok, "The now-visible modification must still be protected")
                git("restore", "--", name)
                assertTrue(r.cli.removeWorktree(entry).ok)
                assertFalse(target.exists())
            } finally { if (target.exists()) deleteTemporaryTree(target.toPath()) }
        }
    }

    @Test fun `porcelain preserves special paths detached locked and prunable`() {
        val result = parseWorktrees("worktree /repo path\u0000HEAD 123\u0000branch refs/heads/main\u0000\u0000worktree /line\nbreak\u0000HEAD 456\u0000detached\u0000locked reason\nwhy\u0000prunable missing\u0000\u0000")
        assertEquals(2, result.size); assertTrue(result.first().main)
        assertEquals("/line\nbreak", result[1].path); assertNull(result[1].branch)
        assertEquals("reason\nwhy", result[1].locked); assertEquals("missing", result[1].prunable)
    }

    @Test fun `worktrees create separate branches and protect current dirty locked ignored and stale state`() = RepositoryFixture().use { r ->
        r.commit(); val target = r.root.resolveSibling(r.root.name + " linked space")
        try {
            assertFalse(r.cli.addWorktree(r.root.resolve("nested").path, "nested").ok)
            assertTrue(r.cli.addWorktree(target.path, "side").ok)
            var entry = r.cli.worktrees().single { !it.main }
            assertEquals("refs/heads/side", entry.branch)
            assertFalse(r.cli.addWorktree(target.resolveSibling("occupied").path, "side", newBranch = false).ok)
            assertFalse(GitCli(target, isolated = true).removeWorktree(entry).ok)
            assertFalse(r.cli.removeWorktree(r.cli.worktrees().first()).ok)
            target.resolve("new").writeText("preserve")
            assertFalse(r.cli.removeWorktree(entry).ok); assertTrue(target.resolve("new").exists())
            target.resolve("new").delete()
            r.git("worktree", "lock", "--reason", "travel", target.path)
            assertFalse(r.cli.removeWorktree(entry).ok)
            entry = r.cli.worktrees().single { !it.main }; assertEquals("travel", entry.locked)
            assertFalse(r.cli.removeWorktree(entry).ok)
            r.git("worktree", "unlock", target.path)
            entry = r.cli.worktrees().single { !it.main }
            r.cli.commonDirectory.resolve("info/exclude").appendText("\nignored\n")
            target.resolve("ignored").writeText("preserve")
            assertFalse(r.cli.removeWorktree(entry).ok)
            target.resolve("ignored").delete()
            assertTrue(r.cli.removeWorktree(entry).ok)
            assertFalse(target.exists())
            assertTrue(r.git("show-ref", "--verify", "refs/heads/side").isNotEmpty())
        } finally { if (target.exists()) deleteTemporaryTree(target.toPath()) }
    }
}
