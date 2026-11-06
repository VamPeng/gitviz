package gitviz

import kotlin.test.*

class OperationPreviewTest {
    @Test fun `rebase snapshots ignored paths present only in intermediate commits`() = RepositoryFixture().use { r ->
        r.commit(".gitignore", "scratch\n"); r.git("branch", "topic"); r.commit("main", "main\n"); r.git("switch", "topic")
        r.write("scratch", "temporary committed file\n"); r.git("add", "-f", "scratch"); r.git("commit", "-qm", "temporary file")
        r.git("rm", "scratch"); r.git("commit", "-qm", "remove temporary file")
        r.write("scratch", "private ignored bytes\n")
        val intent = OperationIntent(PreviewKind.REBASE, "main")
        assertTrue("scratch" in r.cli.recovery.previewState(intent.request()).files)
        val plan = r.cli.planOperation(intent)
        assertEquals("private ignored bytes\n", r.root.resolve("scratch").readText())
        assertTrue("scratch" in plan.workingImpact || !plan.outcome.ok)
        val result = r.cli.executePlan(plan)
        if (!result.ok && r.cli.currentOperation() != null) assertTrue(r.cli.abortOperation().ok)
        val record = r.cli.recovery.records().last { it.label == "rebase ${plan.intent.target}" }
        val preview = r.cli.recovery.preview(record.id)
        assertTrue(r.cli.restorePoint(record.id, preview.fingerprint).ok)
        assertEquals("private ignored bytes\n", r.root.resolve("scratch").readText())
    }

    @Test fun `preview is read only and frozen merge rejects stale state`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.git("checkout", "-b", "topic"); val tip = r.commit("new", "new\n"); r.git("switch", "main")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
        assertTrue(plan.verified, plan.outcome.output)
        assertEquals(base, r.git("rev-parse", "HEAD"))
        assertEquals(tip, plan.intent.target)
        assertTrue(plan.files.any { it.path.endsWith(" · new") })
        r.write("outside", "external\n")
        assertFalse(r.cli.executePlan(plan).ok)
        r.root.resolve("outside").delete()
        val fresh = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
        val result = r.cli.executePlan(fresh)
        assertTrue(result.ok, result.output)
        assertEquals(tip, r.git("rev-parse", "HEAD"))
        assertTrue(r.cli.undo(result.recoveryId).ok)
        assertEquals(base, r.git("rev-parse", "HEAD"))
    }

    @Test fun `reset modes reflect index and worktree independently including ignored collision`() = RepositoryFixture().use { r ->
        r.commit(".gitignore", "ignored\n"); val base = r.commit()
        r.git("checkout", "-b", "target"); r.commit("a", "target\n"); r.write("ignored", "tracked\n"); r.git("add", "-f", "ignored"); r.git("commit", "-qm", "ignored tracked")
        r.git("switch", "main")
        r.write("a", "staged\n"); r.git("add", "a"); r.write("a", "working\n"); r.write("ignored", "local ignored\n")
        r.git("update-index", "--split-index")
        for (mode in ResetMode.entries) {
            val plan = r.cli.planOperation(OperationIntent(PreviewKind.RESET, "target", mode))
            assertTrue(plan.outcome.ok, plan.outcome.output)
            assertEquals(mode != ResetMode.SOFT, plan.indexChanged)
            assertEquals(mode == ResetMode.HARD, plan.workingImpact.contains("a"))
            if (mode == ResetMode.HARD) {
                assertTrue("ignored" in plan.workingImpact)
                assertTrue(plan.files.any { it.path.endsWith(" · a") && "-working" in it.diff })
            }
            if (mode != ResetMode.SOFT) assertTrue(plan.files.any { it.path.endsWith(" · a") && "-staged" in it.diff })
            assertEquals(base, r.git("rev-parse", "HEAD"))
            assertEquals("staged", r.git("show", ":a")); assertEquals("working\n", r.root.resolve("a").readText())
        }
    }

    @Test fun `conflicting rebase marks later commits unverified and does not touch source`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("a", "main\n"); r.git("switch", "topic"); r.commit("a", "topic\n"); val tip = r.commit("later", "later\n")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.REBASE, "main"))
        assertTrue(plan.paused)
        assertFalse(plan.verified)
        assertTrue(plan.projected.isNotEmpty())
        assertTrue(plan.expected.isNotEmpty())
        assertTrue(plan.expected.all { it.id.startsWith("expected:") })
        assertNull(r.cli.currentOperation())
        assertEquals(tip, r.git("rev-parse", "HEAD"))
        assertTrue(r.cli.status().isEmpty())
    }

    @Test fun `merge refuses ignored collision and custom filters never execute in sandbox`() = RepositoryFixture().use { r ->
        r.commit(".gitignore", "ignored\n"); r.git("checkout", "-b", "topic")
        r.write("ignored", "tracked\n"); r.git("add", "-f", "ignored"); r.git("commit", "-qm", "add"); r.git("switch", "main"); r.write("ignored", "local\n")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
        assertFalse(plan.outcome.ok)
        assertEquals("local\n", r.root.resolve("ignored").readText())
        r.git("config", "filter.probe.smudge", "touch ${r.root.resolve("must-not-exist")}")
        val next = r.cli.planOperation(OperationIntent(PreviewKind.RESET, "topic", ResetMode.HARD))
        assertTrue(next.warnings.isNotEmpty())
        assertFalse(next.verified)
        assertFalse(r.root.resolve("must-not-exist").exists())
    }

    @Test fun `cleanup never follows directory symlinks and merge ff configuration is honored`() = RepositoryFixture().use { r ->
        val outside = java.nio.file.Files.createTempDirectory("gitviz-outside-")
        try {
            java.nio.file.Files.writeString(outside.resolve("sentinel"), "keep")
            r.commit(); r.git("checkout", "-b", "topic"); r.commit("next", "next\n"); r.git("switch", "main")
            java.nio.file.Files.createSymbolicLink(r.root.resolve("external").toPath(), outside)
            r.git("config", "merge.ff", "false")
            val plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
            assertTrue(plan.outcome.ok, plan.outcome.output)
            assertTrue(plan.afterHead.startsWith("preview:"))
            assertEquals("keep", java.nio.file.Files.readString(outside.resolve("sentinel")))
            val cancelled = java.nio.file.Files.createTempDirectory("gitviz-preview-cancel-test-")
            java.nio.file.Files.createSymbolicLink(cancelled.resolve("external"), outside)
            deleteTemporaryTree(cancelled)
            assertEquals("keep", java.nio.file.Files.readString(outside.resolve("sentinel")))
        } finally { deleteTemporaryTree(outside) }
    }

    @Test fun `merge mainline is explicit and real parent count is used`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "side"); r.commit("main", "main\n"); r.git("switch", "side"); r.commit("side", "side\n"); r.git("merge", "--no-ff", "--no-edit", "main")
        val merge = r.git("rev-parse", "HEAD"); r.git("checkout", "--detach", "HEAD^2")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.CHERRY_PICK, merge, mainline = 2))
        assertEquals(2, plan.parentCount)
        assertTrue(plan.outcome.ok, plan.outcome.output)
        assertTrue(plan.files.any { it.path.endsWith(" · side") })
    }
}
