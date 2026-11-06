package gitviz

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.test.*

class InsightsTest {
    private val date = LocalDate.of(2026, 11, 6)
    private val utc = ZoneId.of("UTC")

    private fun commit(r: RepositoryFixture, path: String, body: String, time: String, name: String = "Test", email: String = "test@example.com"): String {
        r.write(path, body); r.git("add", "--", path)
        r.cli.raw(listOf("commit", "-qm", body), environment = mapOf("GIT_AUTHOR_DATE" to time, "GIT_COMMITTER_DATE" to "2026-11-06T12:00:00Z", "GIT_AUTHOR_NAME" to name, "GIT_AUTHOR_EMAIL" to email)).requireSuccess()
        return r.git("rev-parse", "HEAD")
    }

    @Test fun `empty repository produces empty activity`() = RepositoryFixture().use { r ->
        val data = collectInsights(r.root, null, date, utc)
        assertEquals(0, data.scanned)
        assertEquals(0, data.commits)
        assertTrue(data.files.isEmpty()); assertTrue(data.contributors.isEmpty())
    }

    @Test fun `overlapping branches tags and stash do not duplicate contributions`() = RepositoryFixture().use { r ->
        commit(r, "a", "base", "2026-11-05T10:00:00Z")
        r.git("branch", "topic"); r.git("tag", "-a", "v1", "-m", "tag")
        commit(r, "a", "next", "2026-11-06T10:00:00Z", "Other name")
        r.git("update-ref", "refs/remotes/origin/main", "HEAD")
        r.write("a", "stash content"); r.git("stash", "push", "-qm", "hidden")
        val beforeIndex = r.cli.ownDirectory.resolve("index").readBytes()
        val before = r.git("show-ref")
        val data = collectInsights(r.root, null, date, utc)
        assertEquals(2L, data.commits)
        assertEquals(1, data.contributors.size)
        assertEquals(2L, data.contributors.single().commits)
        assertEquals(listOf(FileActivity("a", 2)), data.files)
        assertEquals(before, r.git("show-ref"))
        assertContentEquals(beforeIndex, r.cli.ownDirectory.resolve("index").readBytes())
    }

    @Test fun `author dates are filtered without prematurely stopping walk and use selected timezone`() = RepositoryFixture().use { r ->
        commit(r, "a", "within", "2026-11-05T23:30:00Z")
        commit(r, "a", "old author new commit", "2020-01-01T00:00:00Z")
        commit(r, "a", "future", "2027-01-01T00:00:00Z")
        val data = collectInsights(r.root, date, date, ZoneId.of("Asia/Taipei"))
        assertEquals(3L, data.scanned)
        assertEquals(1L, data.commits)
        assertEquals(mapOf(date to 1L), data.days)
    }

    @Test fun `merge commits count toward activity but not hot files and deleted paths remain`() = RepositoryFixture().use { r ->
        commit(r, "a", "base", "2026-11-05T00:00:00Z")
        r.git("switch", "-c", "topic")
        commit(r, "b", "topic", "2026-11-05T01:00:00Z", email = "other@test")
        r.git("switch", "main")
        commit(r, "c", "main", "2026-11-05T02:00:00Z")
        r.cli.raw(listOf("merge", "--no-ff", "--no-edit", "topic"), environment = mapOf("GIT_AUTHOR_DATE" to "2026-11-05T03:00:00Z", "GIT_COMMITTER_DATE" to "2026-11-05T03:00:00Z")).requireSuccess()
        r.git("rm", "a")
        r.cli.raw(listOf("commit", "-qm", "delete"), environment = mapOf("GIT_AUTHOR_DATE" to "2026-11-05T04:00:00Z", "GIT_COMMITTER_DATE" to "2026-11-05T04:00:00Z")).requireSuccess()
        val data = collectInsights(r.root, null, date, utc)
        assertEquals(5L, data.commits); assertEquals(1L, data.merges)
        assertEquals(mapOf("a" to 2L, "b" to 1L, "c" to 1L), data.files.associate { it.path to it.commits })
        assertEquals(2, data.contributors.size)
    }

    @Test fun `statistics scan beyond main graph page and cancellation closes own repository`() = RepositoryFixture().use { r ->
        val total = GitRepo.PAGE_SIZE + 9
        val input = buildString { repeat(total) { i ->
            val message = "commit $i"
            append("commit refs/heads/main\ncommitter Bulk <bulk@test> ${1700000000L + i} +0000\ndata ${message.length}\n$message\n\n")
        } }
        r.cli.raw(listOf("fast-import", "--quiet"), input.toByteArray()).requireSuccess()
        val data = collectInsights(r.root, null, date, utc)
        assertEquals(total.toLong(), data.commits)
        var stop = false
        assertFailsWith<CancellationException> { collectInsights(r.root, null, date, utc, cancelled = { stop }, progress = { stop = true }) }
        assertEquals(total.toLong(), collectInsights(r.root, null, date, utc).commits)
    }
}
