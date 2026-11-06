package gitviz

import kotlin.test.*

class ConflictSourcesTest {
    private fun conflict(r: RepositoryFixture) {
        r.commit(content = "base\r\n"); r.git("branch", "side")
        r.commit(content = "ours\r\n"); r.git("switch", "side"); r.commit(content = "theirs\r\n"); r.git("switch", "main")
        assertEquals(GitFailure.CONFLICT, r.cli.merge("side").failure)
    }
    @Test fun `three sources come from actual index stages and stale saves do not overwrite`() = RepositoryFixture().use { r ->
        conflict(r)
        val sources = r.cli.conflictSources("a")
        assertEquals("base\r\n", sources.base)
        assertEquals("ours\r\n", sources.ours)
        assertEquals("theirs\r\n", sources.theirs)
        r.write("a", "external\r\n")
        val result = r.cli.resolveFile("a", "stale\r\n", sources.signature)
        assertFalse(result.ok)
        assertEquals("external\r\n", r.root.resolve("a").readText())
        val current = r.cli.conflictSources("a")
        assertTrue(r.cli.resolveFile("a", "manual\r\n", current.signature).ok)
        assertEquals("manual\r\n", r.root.resolve("a").readText())
        assertTrue(r.cli.status().none { it.conflicted })
    }
    @Test fun `custom marker length retains base and CRLF`() {
        val text = "<<<<<<<<<< ours\r\nleft\r\n|||||||||| base\r\nbase\r\n==========\r\nright\r\n>>>>>>>>>> theirs\r\n"
        val parsed = assertNotNull(parseConflict(text))
        assertEquals(listOf("base\r"), parsed.hunks.single().base)
        assertEquals("left\r\nright\r\n", resolveConflict(parsed, mapOf(0 to Choice.BOTH)))
        assertNull(parseConflict("normal <<<<<<<<< text\n"))
    }
    @Test fun `partial hunk choices survive entering manual edit`() {
        val block = "<<<<<<< ours\na\n=======\nb\n>>>>>>> theirs\n"
        val parsed = assertNotNull(parseConflict(block + "middle\n" + block))
        val draft = conflictDraftText(parsed, mapOf(0 to Choice.THEIRS))
        assertTrue(draft.startsWith("b\nmiddle\n"))
        assertEquals(1, assertNotNull(parseConflict(draft)).hunks.size)
    }

    @Test fun `file presence and link type are included in the signature`() = RepositoryFixture().use { r ->
        conflict(r)
        r.write("a", "missing")
        val ordinary = r.cli.conflictSources("a").signature
        r.root.resolve("a").delete()
        assertNotEquals(ordinary, r.cli.conflictSources("a").signature)
        java.nio.file.Files.createSymbolicLink(r.root.resolve("a").toPath(), java.nio.file.Path.of("missing"))
        assertNotEquals(ordinary, r.cli.conflictSources("a").signature)
    }
    @Test fun `text modify delete can keep deletion`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "side"); r.commit(content = "changed\n")
        r.git("switch", "side"); r.git("rm", "a"); r.git("commit", "-m", "delete"); r.git("switch", "main")
        assertEquals(GitFailure.CONFLICT, r.cli.merge("side").failure)
        val source = r.cli.conflictSources("a")
        assertNull(source.theirs); assertTrue(source.textSupported)
        assertTrue(r.cli.resolveWhole("a", false, source.signature).ok)
        assertFalse(r.root.resolve("a").exists())
    }
    @Test fun `manual edit leaves mixed newlines intact`() {
        val mixed = "one\r\ntwo\nthree\r\n"
        assertEquals(mixed, editedLineEndings(mixed, mixed))
        assertEquals("one\r\ntwo\r\n", editedLineEndings("one\r\ntwo\n", "one\r\n"))
    }

    @Test fun `strict text rejects malformed bytes and binary`() {
        assertNull(strictText(byteArrayOf(-1)))
        assertNull(strictText(byteArrayOf(0, 1)))
        assertEquals("a\r\n", strictText("a\r\n".toByteArray()))
    }
}
