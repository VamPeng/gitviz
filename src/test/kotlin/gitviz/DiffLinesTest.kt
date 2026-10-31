package gitviz

import kotlin.test.Test
import kotlin.test.assertEquals

class DiffLinesTest {

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
    fun `diff without hunks is all header`() {
        val kinds = parseDiffLines("diff --git a/x b/x\nBinary files a/x and b/x differ").map { it.kind }
        assertEquals(listOf(DiffLineKind.HEADER, DiffLineKind.HEADER), kinds)
    }
}
