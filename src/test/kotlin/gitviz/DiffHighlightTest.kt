package gitviz

import kotlin.test.*

class DiffHighlightTest {
    @Test fun `both sides use full source lexical state outside displayed hunk`() {
        val old = "/* start\ncomment\n*/\nval n = 12\n"
        val new = "val start = 1\nval value = 2\n// done\nval n = 12\n"
        val syntax = SyntaxCache().highlight(DiffSources(SourceCode("a.kt", old), SourceCode("a.kt", new)))
        val removed = DiffLine("-comment", DiffLineKind.REMOVED, oldNo = 2)
        val added = DiffLine("+val value = 2", DiffLineKind.ADDED, newNo = 2)
        assertEquals(SyntaxKind.COMMENT, syntax.line(removed, true)!!.tokens.single().kind)
        assertEquals(SyntaxKind.KEYWORD, syntax.line(added, false)!!.tokens.first().kind)
        assertEquals(SyntaxKind.NUMBER, syntax.line(added, false)!!.tokens.last().kind)
        val nested = SyntaxCache().highlight(SourceCode("a.kt", "/* a\n/* b */\nstill comment\n*/ val x = 1"))!!
        assertEquals(SyntaxKind.COMMENT, nested[2].tokens.single().kind)
        assertEquals(SyntaxKind.KEYWORD, nested[3].tokens[1].kind)
    }
    @Test fun `six languages preserve multiline strings and escaped quotes`() {
        val examples = mapOf("kt" to "val x = 2", "java" to "public int x = 2;", "js" to "const x = 2;", "ts" to "let x: number = 2;", "json" to "{\"x\": true}", "py" to "def x(): return 2")
        examples.forEach { (ext, text) -> assertTrue(SyntaxCache().highlight(SourceCode("a.$ext", text))!!.single().tokens.any { it.kind == SyntaxKind.KEYWORD }, ext) }
        listOf("py" to "\"\"\"", "kt" to "\"\"\"", "js" to "`").forEach { (ext, delimiter) ->
            val lines = SyntaxCache().highlight(SourceCode("a.$ext", "${delimiter}start\nif false 123\n$delimiter\nreturn"))!!
            assertEquals(SyntaxKind.STRING, lines[1].tokens.single().kind)
            assertEquals(SyntaxKind.KEYWORD, lines[3].tokens.single().kind)
        }
        val line = SyntaxCache().highlight(SourceCode("a.js", "\"escaped \\\" quote\" // comment"))!!.single()
        assertEquals(SyntaxKind.COMMENT, line.tokens.last().kind)
    }
    @Test fun `javascript regex character classes do not contaminate following lines`() {
        listOf("js", "ts").forEach { ext ->
            val lines = SyntaxCache().highlight(SourceCode("a.$ext", "const re = /[/*]/;\nconst answer = 42;\nconst quotient = a / b / c;"))!!
            assertEquals(SyntaxKind.KEYWORD, lines[1].tokens.first().kind)
            assertEquals(SyntaxKind.NUMBER, lines[1].tokens.last().kind)
            assertTrue(lines[2].tokens.none { it.kind == SyntaxKind.COMMENT })
        }
    }
    @Test fun `minified divisions do not repeatedly copy line prefixes`() {
        val source = SourceCode("a.js", "const result = " + "a / b / ".repeat(50_000) + "c;")
        val start = System.nanoTime()
        assertNotNull(SyntaxCache().highlight(source))
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
    }
    @Test fun `limits and LRU bound memory while unsupported files stay plain`() {
        val cache = SyntaxCache(1500)
        assertNull(cache.highlight(SourceCode("a.txt", "val x = 1")))
        assertNull(cache.highlight(SourceCode("a.kt", "x".repeat(HIGHLIGHT_LIMIT + 1))))
        assertNull(cache.highlight(SourceCode("a.kt", "\n".repeat(20_001))))
        assertNotNull(cache.highlight(SourceCode("a.kt", "\n".repeat(20_000))))
        val first = cache.highlight(SourceCode("a.kt", "val a = 1"))
        assertSame(first, cache.highlight(SourceCode("b.kt", "val a = 1")))
        repeat(50) { cache.highlight(SourceCode("a.kt", "val number = $it")); assertTrue(cache.usedBytes <= 1500) }
        assertNotSame(first, cache.highlight(SourceCode("a.kt", "val a = 1")))
    }
    @Test fun `commit working stash and comparison supply independent complete source versions`() = RepositoryFixture().use { r ->
        val first = r.commit("a.kt", "val value = 1\n")
        r.write("a.kt", "val value = 2\n"); r.git("add", "a.kt"); r.write("a.kt", "val value = 3\n")
        val file = WorkingFile("a.kt", null, 'M', 'M')
        assertEquals("val value = 1\n", r.cli.workingSources(file, true).old!!.text)
        assertEquals("val value = 2\n", r.cli.workingSources(file, true).new!!.text)
        assertEquals("val value = 2\n", r.cli.workingSources(file, false).old!!.text)
        assertEquals("val value = 3\n", r.cli.workingSources(file, false).new!!.text)
        r.write("new.py", "return 3\n"); r.git("stash", "push", "-u", "-m", "sources")
        val stash = r.cli.stashList().single()
        val tracked = r.cli.stashSources(stash, StashFile("a.kt", 'M'))
        assertEquals("val value = 1\n", tracked.old!!.text); assertEquals("val value = 3\n", tracked.new!!.text)
        assertEquals("return 3\n", r.cli.stashSources(stash, StashFile("new.py", 'A', true)).new!!.text)
        val tip = r.commit("a.kt", "val value = 4\n")
        GitRepo.open(r.root).use { repo ->
            val sources = repo.diffSources(repo.changes(tip).single())
            assertEquals("val value = 1\n", sources.old!!.text); assertEquals("val value = 4\n", sources.new!!.text)
        }
        ComparisonSession.open(r.root, CompareRequest(first, tip)).use { session ->
            assertEquals("val value = 4\n", session.sources(session.files(true, null).single()).new!!.text)
        }
    }
}
