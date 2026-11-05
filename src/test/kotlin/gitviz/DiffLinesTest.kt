package gitviz

import kotlin.test.Test
import kotlin.test.assertEquals

class DiffLinesTest {
    init { Settings.language = Language.EN }


    @Test
    fun `lines that look like file headers inside a hunk keep their real kind`() {
        val diff = """
            diff --git a/q.sql b/q.sql
            index dfa03a0..081e9dc 100644
            --- a/q.sql
            +++ b/q.sql
            @@ -1,2 +1,2 @@
            --- old
             keep
            +++x
        """.trimIndent()

        assertEquals(
            listOf(
                DiffLineKind.HEADER, DiffLineKind.HEADER, DiffLineKind.HEADER, DiffLineKind.HEADER,
                DiffLineKind.HUNK, DiffLineKind.REMOVED, DiffLineKind.CONTEXT, DiffLineKind.ADDED,
            ),
            parseDiffLines(diff).map { it.kind },
        )
    }

    @Test
    fun `line numbers follow the hunk header`() {
        val lines = parseDiffLines("@@ -10,3 +20,3 @@ fun x\n keep\n-old\n+new\n\\ No newline at end of file\n tail")

        assertEquals(
            listOf(null to null, 10 to 20, 11 to null, null to 21, null to null, 12 to 22),
            lines.map { it.oldNo to it.newNo },
        )
    }

    @Test
    fun `split rows pair removals with additions`() {
        val rows = toSplitRows(parseDiffLines("--- a\n+++ b\n@@ -1,3 +1,2 @@\n-one\n-two\n+uno\n same"))

        assertEquals(
            listOf(
                "@@ -1,3 +1,2 @@" to "@@ -1,3 +1,2 @@",
                "-one" to "+uno",
                "-two" to null,
                " same" to " same",
            ),
            rows.map { it.left?.text to it.right?.text },
        )
    }

    @Test
    fun `diff without hunks is all header`() {
        val kinds = parseDiffLines("diff --git a/x b/x\nBinary files a/x and b/x differ").map { it.kind }
        assertEquals(listOf(DiffLineKind.HEADER, DiffLineKind.HEADER), kinds)
    }

    @Test
    fun `mode change is read from the file header only`() {
        val lines = parseDiffLines("diff --git a/x b/x\nold mode 100644\nnew mode 100755\n@@ -1 +1 @@\n-old mode 1\n+new mode 2")
        assertEquals(ModeChange("100644", "100755"), modeChange(lines))
        assertEquals(null, modeChange(parseDiffLines("@@ -1 +1 @@\n-a\n+b")))
    }

    @Test
    fun `empty diff explains why`() {
        assertEquals("Renamed only; content unchanged",
            emptyDiffMessage(parseDiffLines("diff --git a/x b/y\nsimilarity index 100%\nrename from x\nrename to y")))
        assertEquals("Binary file; changes can't be shown",
            emptyDiffMessage(parseDiffLines("diff --git a/x b/x\nBinary files a/x and b/x differ")))
        assertEquals("Mode 100755 → 100644 (no longer executable); content unchanged",
            emptyDiffMessage(parseDiffLines("diff --git a/x b/x\nold mode 100755\nnew mode 100644")))
    }
}
