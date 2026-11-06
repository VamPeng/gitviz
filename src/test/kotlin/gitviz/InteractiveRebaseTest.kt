package gitviz

import kotlin.test.*

class InteractiveRebaseTest {
    private fun GitCli.previewEdited(plan: InteractiveRebasePlan) = planOperation(OperationIntent(PreviewKind.REBASE, plan.base, expectedHead = plan.head, interactive = plan))

    @Test fun `initially empty and newly empty edits preserve reviewed messages after continuing`() = RepositoryFixture().use { r ->
        val base = r.commit()
        r.git("commit", "--allow-empty", "-m", "initially empty")
        val initial = r.cli.interactiveRange(base)
        val initialEdit = initial.copy(edits = initial.edits.map { it.copy(action = RebaseAction.REWORD, message = "reviewed empty") })
        val first = r.cli.executePlan(r.cli.previewEdited(initialEdit))
        assertTrue(first.ok, first.output)
        assertEquals("reviewed empty", r.git("log", "-1", "--format=%B"))
        assertEquals(r.git("rev-parse", "$base^{tree}"), r.git("rev-parse", "HEAD^{tree}"))

        val nextBase = r.git("rev-parse", "HEAD")
        val add = r.commit("repeat", "same\n")
        r.git("rm", "repeat"); r.git("commit", "-m", "remove repeated change")
        val remove = r.git("rev-parse", "HEAD")
        val repeat = r.commit("repeat", "same\n")
        val range = r.cli.interactiveRange(nextBase)
        val edit = range.copy(edits = listOf(RebaseEdit(add), RebaseEdit(remove, RebaseAction.DROP), RebaseEdit(repeat, RebaseAction.REWORD, "retained empty")))
        val paused = r.cli.executePlan(r.cli.previewEdited(edit))
        assertEquals(GitFailure.PAUSED, paused.failure, paused.output)
        val reopened = GitCli(r.root, isolated = true)
        assertTrue(reopened.rebaseProgress()?.canKeepEmpty == true)
        val continued = reopened.keepEmptyRebaseCommit()
        assertTrue(continued.ok, continued.output)
        assertEquals("retained empty", r.git("log", "-1", "--format=%B"))
        assertEquals("2", r.git("rev-list", "--count", "$nextBase..HEAD"))
        assertEquals(r.git("rev-parse", "HEAD^{tree}"), r.git("rev-parse", "HEAD~1^{tree}"))
    }

    @Test fun `reorder reword squash fixup and drop are rehearsed and entire operation can be undone`() = RepositoryFixture().use { r ->
        val base = r.commit()
        val one = r.commit("one", "one\n"); val two = r.commit("two", "two\n"); val three = r.commit("three", "three\n")
        val four = r.commit("four", "four\n"); val five = r.commit("five", "five\n")
        val range = r.cli.interactiveRange(base)
        val message = "新标题 '\" \$(touch SHOULD_NOT_EXIST) `touch ALSO_NOT`\n\nbody\nwith\\slashes"
        val edited = range.copy(edits = listOf(RebaseEdit(two, RebaseAction.REWORD, message), RebaseEdit(one, RebaseAction.SQUASH), RebaseEdit(three, RebaseAction.FIXUP), RebaseEdit(four, RebaseAction.DROP), RebaseEdit(five)))
        val preview = r.cli.previewEdited(edited)
        assertTrue(preview.outcome.ok, preview.outcome.output)
        assertEquals(five, r.git("rev-parse", "HEAD"), "Rehearsal changed source HEAD")
        val result = r.cli.executePlan(preview)
        assertTrue(result.ok, result.output)
        assertEquals("2", r.git("rev-list", "--count", "$base..HEAD"))
        assertEquals(message + "\n\none", r.git("log", "-1", "--format=%B", "HEAD^"))
        assertFalse(r.root.resolve("four").exists())
        assertTrue(listOf("one", "two", "three", "five").all { r.root.resolve(it).isFile })
        assertFalse(r.root.resolve("SHOULD_NOT_EXIST").exists()); assertFalse(r.root.resolve("ALSO_NOT").exists())
        val undo = r.cli.undo(result.recoveryId)
        assertTrue(undo.ok, undo.output)
        assertEquals(five, r.git("rev-parse", "HEAD"))
        assertTrue(r.root.resolve("four").isFile)
    }

    @Test fun `conflicting reorder pauses and continues with edited message after reopening cli`() = RepositoryFixture().use { r ->
        val base = r.commit("a", "base\n")
        val one = r.commit("a", "first\n")
        val two = r.commit("a", "second\n")
        val original = r.cli.interactiveRange(base)
        val plan = original.copy(edits = listOf(RebaseEdit(two, RebaseAction.REWORD, "reviewed message"), RebaseEdit(one, RebaseAction.DROP)))
        val preview = r.cli.previewEdited(plan)
        assertTrue(preview.paused)
        assertTrue(preview.expected.isEmpty())
        val result = r.cli.executePlan(preview)
        assertEquals(GitFailure.CONFLICT, result.failure, result.output)
        assertEquals(GitOperation.REBASE, r.cli.currentOperation())
        val reopened = GitCli(r.root, isolated = true)
        assertTrue(reopened.resolveFile("a", "resolved\n").ok)
        val continued = reopened.continueOperation()
        assertTrue(continued.ok, continued.output)
        assertEquals("reviewed message", r.git("log", "-1", "--format=%B"))
        assertEquals("resolved\n", r.root.resolve("a").readText())
        val record = reopened.recovery.records().first { it.before.operation == null && it.label.startsWith("rebase ") }
        val recovery = reopened.recovery.preview(record.id)
        assertTrue(reopened.restorePoint(record.id, recovery.fingerprint).ok)
        assertEquals(two, r.git("rev-parse", "HEAD"))
    }

    @Test fun `invalid plans dirty and merge ranges or stale HEAD never run`(): Unit = RepositoryFixture().use { r ->
        val base = r.commit(); val one = r.commit("b", "b\n"); val two = r.commit("c", "c\n")
        val range = r.cli.interactiveRange(base)
        assertFails { range.copy(edits = listOf(RebaseEdit(one, RebaseAction.SQUASH), RebaseEdit(two))).todo() }
        assertFails { range.copy(edits = listOf(RebaseEdit(one), RebaseEdit(one))).todo() }
        assertFails { range.copy(edits = listOf(RebaseEdit(one, RebaseAction.REWORD, "\u0000"), RebaseEdit(two))).todo() }
        val preview = r.cli.previewEdited(range)
        r.write("untracked", "preserve")
        assertFalse(r.cli.executePlan(preview).ok)
        assertEquals(two, r.git("rev-parse", "HEAD"))
        r.root.resolve("untracked").delete()
        r.git("switch", "-c", "side", base); r.commit("d", "d\n"); r.git("switch", "main"); r.git("merge", "--no-ff", "--no-edit", "side")
        assertFails { r.cli.interactiveRange(base) }
    }

    @Test fun `drop all and abort paused edit return to expected history`() = RepositoryFixture().use { r ->
        val base = r.commit("a", "base\n"); val one = r.commit("a", "one\n"); val two = r.commit("a", "two\n")
        val range = r.cli.interactiveRange(base)
        val drop = range.copy(edits = range.edits.map { it.copy(action = RebaseAction.DROP) })
        val result = r.cli.executePlan(r.cli.previewEdited(drop))
        assertTrue(result.ok, result.output)
        assertEquals(base, r.git("rev-parse", "HEAD"))
        assertTrue(r.cli.undo(result.recoveryId).ok)
        val reordered = range.copy(edits = listOf(RebaseEdit(two), RebaseEdit(one)))
        assertFalse(r.cli.executePlan(r.cli.previewEdited(reordered)).ok)
        assertTrue(GitCli(r.root, isolated = true).abortOperation().ok)
        assertEquals(two, r.git("rev-parse", "HEAD"))
        assertEquals("two\n", r.root.resolve("a").readText())
    }
}
