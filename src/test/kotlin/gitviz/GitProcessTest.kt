package gitviz

import java.nio.file.Files
import kotlin.test.*

class GitProcessTest {
    @Test fun `transport preserves binary input and separates stderr`() {
        val root = Files.createTempDirectory("gitviz-process").toFile()
        try {
            GitProcess.execute(root, listOf("init", "-q")).requireSuccess()
            val payload = byteArrayOf(0, -1, 13, 10, 42)
            val oid = GitProcess.execute(root, listOf("hash-object", "-w", "--stdin"), payload).requireSuccess().decodeToString().trim()
            assertContentEquals(payload, GitProcess.execute(root, listOf("cat-file", "blob", oid)).requireSuccess())
            val bad = GitProcess.execute(root, listOf("cat-file", "blob", "missing"))
            assertNotEquals(0, bad.exitCode)
            assertEquals(0, bad.stdout.size)
            assertTrue(bad.stderr.isNotEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `unknown result never offers blind retry`() {
        val status = describe(NetOp.PUSH, GitResult(GitFailure.UNKNOWN, "timed out"))
        assertEquals(NetState.WARNING, status.state)
        assertNull(status.next)
    }
}
