package gitviz

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException

internal data class ContributorActivity(val name: String, val email: String, val commits: Long)
internal data class FileActivity(val path: String, val commits: Long)
internal data class RepositoryInsights(
    val roots: List<String>, val from: LocalDate?, val through: LocalDate, val zone: ZoneId,
    val scanned: Long, val commits: Long, val merges: Long, val days: Map<LocalDate, Long>,
    val contributors: List<ContributorActivity>, val files: List<FileActivity>,
)

/** Independent walk, never the UI's loaded page. Author dates are not monotonic in traversal order. */
internal fun collectInsights(directory: File, from: LocalDate?, through: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): RepositoryInsights = GitRepo.open(directory).use { repo ->
    require(from == null || from <= through)
    val roots = repo.historyRoots()
    val days = HashMap<LocalDate, Long>()
    val contributors = HashMap<String, ContributorActivity>()
    val files = HashMap<String, Long>()
    var scanned = 0L; var commits = 0L; var merges = 0L
    fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Insights cancelled") }
    repo.newHistoryWalk(startIds = roots).use { walk ->
        walk.setRetainBody(false)
        DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
            diff.setRepository(repo.repository)
            // Path frequency, not line churn: avoid reading blobs or running rename similarity detection.
            diff.isDetectRenames = false
            while (true) {
                checkCancelled()
                val c = walk.next() ?: break
                scanned++
                walk.parseBody(c)
                val author = c.authorIdent
                val day = author.whenAsInstant.atZone(zone).toLocalDate()
                if ((from == null || day >= from) && day <= through) {
                    commits++
                    days[day] = (days[day] ?: 0) + 1
                    val key = author.emailAddress.ifBlank { "name:${author.name}" }
                    val old = contributors[key]
                    contributors[key] = ContributorActivity(old?.name ?: author.name, author.emailAddress, (old?.commits ?: 0) + 1)
                    if (c.parentCount > 1) merges++ else {
                        val parent = if (c.parentCount == 0) null else walk.parseCommit(c.getParent(0)).tree
                        val paths = diff.scan(parent, c.tree).map { if (it.newPath == DiffEntry.DEV_NULL) it.oldPath else it.newPath }.toSet()
                        checkCancelled()
                        paths.forEach { path -> files[path] = (files[path] ?: 0) + 1 }
                    }
                    check(files.size <= 250_000 && contributors.size <= 100_000 && days.size <= 100_000) {
                        t("This range has too many distinct files or authors. Select a shorter period.", "这个范围的文件或作者过多，请选择较短的时间范围。")
                    }
                }
                c.disposeBody()
                if (scanned % 100 == 0L) progress(scanned)
            }
        }
    }
    checkCancelled(); progress(scanned)
    RepositoryInsights(roots, from, through, zone, scanned, commits, merges, days.toSortedMap(),
        contributors.values.sortedWith(compareByDescending<ContributorActivity> { it.commits }.thenBy { it.email }.thenBy { it.name }),
        files.map { FileActivity(it.key, it.value) }.sortedWith(compareByDescending<FileActivity> { it.commits }.thenBy { it.path }))
}
