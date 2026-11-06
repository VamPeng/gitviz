package gitviz

import kotlin.test.*

class PatchDocumentTest {
    @Test fun `reading staged changes does not refresh index and invalidate undo`() = RepositoryFixture().use { r ->
        r.commit("a", "before\n"); r.write("a", "after\n")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        val staged = r.cli.applySelection(doc, doc.changes)
        assertTrue(staged.ok, staged.output)
        val index = r.cli.ownDirectory.resolve("index").readBytes()
        val file = r.cli.status().single()
        assertContentEquals(index, r.cli.ownDirectory.resolve("index").readBytes(), "status")
        r.cli.diff(file, false)
        assertContentEquals(index, r.cli.ownDirectory.resolve("index").readBytes(), "working diff")
        r.cli.diff(file, true)
        assertContentEquals(index, r.cli.ownDirectory.resolve("index").readBytes(), "staged diff")
        r.cli.patchDocument(file, true)
        assertContentEquals(index, r.cli.ownDirectory.resolve("index").readBytes())
        val undo = r.cli.undo(staged.recoveryId)
        assertTrue(undo.ok, undo.output)
        assertEquals("before", r.git("show", ":a"))
        assertEquals("after\n", r.root.resolve("a").readText())
    }
    @Test fun `selected deletion and addition are independent and worktree stays unchanged`() = RepositoryFixture().use { r ->
        r.commit("a", "one\ntwo\nthree\n"); r.write("a", "one\nTWO\nthree\nfour\n")
        val file = r.cli.status().single()
        val doc = r.cli.patchDocument(file, false)
        assertNull(doc.unavailable)
        val delete = doc.lines.indexOfFirst { it.text == "-two" }
        val result = r.cli.applySelection(doc, setOf(delete))
        assertTrue(result.ok, result.output)
        assertEquals("one\nthree", r.git("show", ":a"))
        assertEquals("one\nTWO\nthree\nfour\n", r.root.resolve("a").readText())
        val next = r.cli.patchDocument(r.cli.status().single(), false)
        val addition = next.lines.indexOfFirst { it.text == "+four" }
        assertTrue(r.cli.applySelection(next, setOf(addition)).ok)
        assertEquals("one\nthree\nfour", r.git("show", ":a"))
        val undo = r.cli.patchDocument(r.cli.status().single(), true)
        val selected = undo.lines.indexOfFirst { it.text == "+four" }
        val unstaged = r.cli.applySelection(undo, setOf(selected))
        assertTrue(unstaged.ok, unstaged.output)
        assertEquals("one\nthree", r.git("show", ":a"))
    }
    @Test fun `zero context hunks insert and reverse delete at correct offsets`() = RepositoryFixture().use { r ->
        r.commit("a", "one\ntwo\nthree\n"); r.git("config", "diff.context", "0"); r.write("a", "one\ntwo\nthree\nfour\n")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        val added = r.cli.applySelection(doc, doc.changes)
        assertTrue(added.ok, added.output)
        assertEquals("one\ntwo\nthree\nfour", r.git("show", ":a"))
        r.write("a", "one\nthree\nfour\n"); r.git("add", "a")
        val reverse = r.cli.patchDocument(r.cli.status().single(), true)
        val removed = reverse.lines.indexOfFirst { it.text == "-two" }
        val result = r.cli.applySelection(reverse, setOf(removed))
        assertTrue(result.ok, result.output)
        assertEquals("one\ntwo\nthree\nfour", r.git("show", ":a"))
    }
    @Test fun `hunks preserve CRLF no final newline and special paths`() = RepositoryFixture().use { r ->
        val path = "space [a]\t中\nfile.txt"
        r.commit(path, "one\r\ntwo\r\nend"); r.write(path, "one\r\nTWO\r\nend!")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        val result = r.cli.applySelection(doc, doc.changes)
        assertTrue(result.ok, result.output)
        assertContentEquals(r.root.resolve(path).readBytes(), r.cli.bytes("show", ":$path"))
        val reverse = r.cli.patchDocument(r.cli.status().single(), true)
        val back = r.cli.applySelection(reverse, reverse.changes)
        assertTrue(back.ok, back.output)
        assertContentEquals("one\r\ntwo\r\nend".toByteArray(), r.cli.bytes("show", ":$path"))
    }
    @Test fun `unborn partial add and full unstage removes index entry`() = RepositoryFixture().use { r ->
        r.write("new", "first\nsecond\n")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        assertTrue(r.cli.applySelection(doc, setOf(doc.lines.indexOfFirst { it.text == "+second" })).ok)
        assertEquals("second", r.git("show", ":new"))
        val reverse = r.cli.patchDocument(r.cli.status().single(), true)
        assertTrue(r.cli.applySelection(reverse, reverse.changes).ok)
        assertEquals("", r.git("ls-files"))
        assertEquals("first\nsecond\n", r.root.resolve("new").readText())
    }
    @Test fun `partial deletion retains file and complete deletion removes it`() = RepositoryFixture().use { r ->
        r.commit("a", "one\ntwo\n"); r.root.resolve("a").delete()
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        assertTrue(r.cli.applySelection(doc, setOf(doc.lines.indexOfFirst { it.text == "-one" })).ok)
        assertEquals("two", r.git("show", ":a"))
        val next = r.cli.patchDocument(r.cli.status().single(), false)
        assertTrue(r.cli.applySelection(next, next.changes).ok)
        assertEquals("", r.git("ls-files"))
    }
    @Test fun `stale index or concurrent modification never overwrites other staged changes`() = RepositoryFixture().use { r ->
        r.commit(); r.write("a", "modified\n")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        r.write("other", "other\n")
        val result = r.cli.applySelection(doc, doc.changes) { r.git("add", "other") }
        assertFalse(result.ok)
        assertEquals("other", r.git("show", ":other"))
        assertEquals("first", r.git("show", ":a"))
        assertFalse(r.cli.ownDirectory.resolve("index.lock").exists())
    }
    @Test fun `non UTF8 old side and large files refuse partial edits`() = RepositoryFixture().use { r ->
        r.root.resolve("a").writeBytes(byteArrayOf(0xe9.toByte(), 10)); r.git("add", "a"); r.git("commit", "-qm", "legacy")
        r.write("a", "UTF8\n"); r.git("add", "a")
        val doc = r.cli.patchDocument(r.cli.status().single(), true)
        assertNotNull(doc.unavailable)
        r.write("large", "x".repeat(1_048_577))
        val large = r.cli.patchDocument(r.cli.status().first { it.path == "large" }, false)
        assertNotNull(large.unavailable)
        assertTrue(large.lines.isEmpty())
        r.git("add", "large"); r.git("commit", "-qm", "large")
        r.write("large", "small\n"); r.git("add", "large")
        val oldLarge = r.cli.patchDocument(r.cli.status().first { it.path == "large" }, true)
        assertNotNull(oldLarge.unavailable)
        assertTrue(oldLarge.lines.isEmpty())
    }
    @Test fun `split index is installed as complete index and undo restores it`() = RepositoryFixture().use { r ->
        r.commit(); r.git("update-index", "--split-index"); r.write("a", "new\n")
        val doc = r.cli.patchDocument(r.cli.status().single(), false)
        val result = r.cli.applySelection(doc, doc.changes)
        assertTrue(result.ok, result.output)
        assertEquals("new", r.git("show", ":a"))
        val undo = r.cli.undo(result.recoveryId)
        assertTrue(undo.ok, undo.output)
        assertEquals("first", r.git("show", ":a"))
        assertEquals("new\n", r.root.resolve("a").readText())
    }
}
