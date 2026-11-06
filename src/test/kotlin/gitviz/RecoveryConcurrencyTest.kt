package gitviz

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.*

class RecoveryConcurrencyTest {
    @Test fun `prepared reference verifies literal symbolic target even when OID is unchanged`() = RepositoryFixture().use { r ->
        val head = r.commit(); r.git("branch", "other")
        val before = r.cli.recovery.previewState(OperationRequest(OperationKind.OTHER))
        r.git("symbolic-ref", "HEAD", "refs/heads/other")
        assertFails { r.cli.restoreReferences(before, before.copy(head = head)) }
        assertEquals("refs/heads/other", r.git("symbolic-ref", "HEAD"))
        assertFalse(r.root.resolve(".git/HEAD.lock").exists())
    }
    @Test fun `prepared lock refuses concurrent writes and abort preserves stash reflog`() = RepositoryFixture().use { r ->
        val oid = r.commit(); r.write("a", "stash\n"); r.git("stash", "push", "-m", "old")
        val stash = r.git("rev-parse", "refs/stash")
        assertFails {
            r.cli.referenceTransaction(listOf("delete refs/stash $stash")) {
                assertNotEquals(0, r.cli.raw(listOf("update-ref", "refs/stash", oid)).exitCode)
                error("simulated changed full stash list")
            }
        }
        assertEquals(stash, r.git("rev-parse", "refs/stash"))
        assertTrue("old" in r.git("stash", "list"))
        assertFalse(r.root.resolve(".git/refs/stash.lock").exists())
    }
    @Test fun `timeout aborts prepared transaction without leaking native lock or delayed commit`() = RepositoryFixture().use { r ->
        val oid = r.commit(); r.git("branch", "other")
        val release = CountDownLatch(1)
        try {
            val result = r.cli.raw(listOf("update-ref", "--no-deref", "--stdin"),
                "start\nupdate HEAD $oid $oid\nprepare\n".toByteArray(), timeout = 1, preparedCheck = { release.await() })
            assertTrue(result.uncertain)
            assertFalse(r.root.resolve(".git/HEAD.lock").exists())
            r.git("symbolic-ref", "HEAD", "refs/heads/other")
        } finally { release.countDown() }
        assertEquals("refs/heads/other", r.git("symbolic-ref", "HEAD"))
    }
    @Test fun `config lock blocks native edits and preserves unrelated keys`() = RepositoryFixture().use { r ->
        r.commit(); r.git("config", "example.keep", "original")
        val record = r.cli.recovery.begin(OperationRequest(OperationKind.BRANCH, listOf("configure")))
        r.git("config", "example.changed", "new")
        r.cli.recovery.finish(record, GitResult(null, ""))
        RecoveryIndexLock(r.cli.commonDirectory, "test-config", "config").use {
            assertNotEquals(0, r.cli.raw(listOf("config", "example.keep", "external")).exitCode)
        }
        assertTrue(r.cli.undo().ok)
        assertEquals("original", r.git("config", "example.keep"))
        assertNotEquals(0, r.cli.raw(listOf("config", "--get", "example.changed")).exitCode)
    }
    @Test fun `chained symbolic refs retain direct targets through recovery`() = RepositoryFixture().use { r ->
        r.commit(); r.git("symbolic-ref", "refs/remotes/origin/second", "refs/heads/main")
        r.git("symbolic-ref", "refs/remotes/origin/first", "refs/remotes/origin/second")
        val record = r.cli.recovery.begin(OperationRequest(OperationKind.BRANCH, listOf("change symbolic")))
        assertEquals("ref: refs/remotes/origin/second", record.before.refs["refs/remotes/origin/first"])
        r.git("symbolic-ref", "refs/remotes/origin/first", "refs/heads/main")
        r.cli.recovery.finish(record, GitResult(null, ""))
        val result = r.cli.undo(); assertTrue(result.ok, result.output)
        assertEquals("refs/remotes/origin/second", r.git("symbolic-ref", "--no-recurse", "refs/remotes/origin/first"))
    }
}
