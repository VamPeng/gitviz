package gitviz

import kotlin.test.*

class TeachingTest {
    @Test fun `stage records only user command and preserves special path quoting`() = RepositoryFixture().use { r ->
        val path = "a 'quoted' [file].txt"
        r.write(path, "content\n")
        val result = Teaching.capture(true) { r.cli.stage(r.cli.status().single()) }
        assertTrue(result.ok, result.output)
        assertEquals(listOf("git add -- ':(literal)a '\"'\"'quoted'\"'\"' [file].txt'"), result.lessons.map { it.command })
        assertEquals("content", r.git("show", ":$path"))
        assertEquals(result.lessons, describeAction("Staging", "Staged", result).lessons)
    }

    @Test fun `disabled and failed captures cannot leak commands into following operations`() {
        val disabled = Teaching.capture(false) { Teaching.command(listOf("push")); GitResult(null, "") }
        assertTrue(disabled.lessons.isEmpty())
        val failed = Teaching.capture(true) { Teaching.command(listOf("switch", "missing")); error("missing branch") }
        assertFalse(failed.ok)
        assertEquals("git switch missing", failed.lessons.single().command)
        Teaching.command(listOf("push"))
        assertTrue(Teaching.capture(true) { GitResult(null, "") }.lessons.isEmpty())
        assertFailsWith<java.util.concurrent.CancellationException> { Teaching.capture(true) { throw java.util.concurrent.CancellationException() } }
        assertTrue(Teaching.capture(true) { GitResult(null, "") }.lessons.isEmpty())
    }

    @Test fun `credentials never enter displayed command while shell arguments remain quoted`() {
        val command = formatGitCommand(listOf("clone", "--", "https://name:password@example.test/a.git?token=private#secret", "a\n\$(touch NEVER)"))
        assertFalse("password" in command)
        assertFalse("private" in command)
        assertFalse("secret" in command)
        assertTrue("https://<redacted>@example.test/a.git?<redacted>" in command)
        assertTrue(command.endsWith("'a\n\$(touch NEVER)'"))
        assertFalse("Bearer abc" in formatGitCommand(listOf("-c", "http.extraheader=Authorization: Bearer abc", "fetch")))
    }

    @Test fun `partial staging explains stdin and temporary index while undo has no fake command`() = RepositoryFixture().use { r ->
        r.commit(); r.write("a", "changed\n")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        val result = Teaching.capture(true) { r.cli.applySelection(doc, doc.changes) }
        assertTrue(result.ok, result.output)
        assertEquals(2, result.lessons.size)
        assertTrue(result.lessons.all { it.command!!.startsWith("git apply --cached") })
        assertTrue(result.lessons.all { "temporary index" in it.explanation || "临时暂存区" in it.explanation })
        assertTrue(result.lessons.all { "stdin" in it.explanation || "标准输入" in it.explanation })
        val undo = Teaching.capture(true) { r.cli.undo(result.recoveryId) }
        assertTrue(undo.ok, undo.output)
        assertNull(undo.lessons.single().command)
        assertEquals("first", r.git("show", ":a"))
        assertEquals("changed\n", r.root.resolve("a").readText())
    }

    @Test fun `preview and background reads are absent but executing frozen plan is recorded`() = RepositoryFixture().use { r ->
        r.commit(); r.git("switch", "-c", "topic"); r.commit("b", "topic\n"); r.git("switch", "main")
        lateinit var plan: OperationPlan
        val preview = Teaching.capture(true) {
            plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
            r.cli.status(); r.cli.stashList(); GitResult(null, "")
        }
        assertTrue(preview.lessons.isEmpty())
        val result = Teaching.capture(true) { r.cli.executePlan(plan) }
        assertTrue(result.ok, result.output)
        assertTrue(result.lessons.single().command!!.startsWith("git merge "))
    }
}
