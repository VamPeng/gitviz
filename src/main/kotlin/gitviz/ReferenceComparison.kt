package gitviz

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.filter.RevFilter
import java.io.File

internal data class CompareRequest(val left: String, val right: String, val leftLabel: String = left, val rightLabel: String = right, val generation: Int = 0)
internal data class ComparePage(val commits: List<CommitInfo>, val hasMore: Boolean)
internal class ComparisonSession private constructor(val repo: GitRepo, val request: CompareRequest) : AutoCloseable {
    val a = repo.repository.resolve("${request.left}^{commit}")?.name ?: error("Invalid reference: ${request.left}")
    val b = repo.repository.resolve("${request.right}^{commit}")?.name ?: error("Invalid reference: ${request.right}")
    init { check(GitCli(repo.workTree ?: repo.repository.directory).read("rev-parse", "--is-shallow-repository").trim() == "false") { t("Comparison needs complete history; shallow repository cannot determine all ancestors.", "对比需要完整历史；浅仓库无法确定全部祖先。") } }
    val counts = GitCli(repo.workTree ?: repo.repository.directory).read("rev-list", "--left-right", "--count", "$a...$b").trim().split(Regex("\\s+")).map { it.toLong() }
    val bases: List<String> = RevWalk(repo.repository).use { walk ->
        walk.revFilter = RevFilter.MERGE_BASE; walk.markStart(walk.parseCommit(ObjectId.fromString(a))); walk.markStart(walk.parseCommit(ObjectId.fromString(b)))
        buildList { while (true) add((walk.next() ?: break).name) }
    }
    private class Cursor(val walk: RevWalk, val count: Long) { var seen = 0L; var failed = false }
    private fun cursor(start: String, exclude: String, count: Long) = Cursor(RevWalk(repo.repository).apply {
        sort(RevSort.COMMIT_TIME_DESC); markStart(parseCommit(ObjectId.fromString(start))); markUninteresting(parseCommit(ObjectId.fromString(exclude)))
    }, count)
    private val left = cursor(a, b, counts[0])
    private val right = cursor(b, a, counts[1])
    private var closed = false
    @Synchronized fun load(leftSide: Boolean, count: Int = 200): ComparePage {
        check(!closed) { "Comparison was closed" }
        val cursor = if (leftSide) left else right
        check(!cursor.failed) { "Comparison page failed; refresh to rebuild the cursor" }
        val commits = try { buildList<CommitInfo> {
            while (size < count) { val c = cursor.walk.next() ?: break; add(with(repo) { c.toInfo(emptyList()) }); c.disposeBody(); cursor.seen++ }
        }
        } catch (e: Exception) { cursor.failed = true; throw e }
        return ComparePage(commits, cursor.seen < cursor.count)
    }
    @Synchronized fun files(endpoint: Boolean, base: String?): List<FileChange> {
        check(!closed)
        val from = if (endpoint || bases.isEmpty()) a else checkNotNull(base).also { require(it in bases) }
        return repo.changesBetween(from, b)
    }
    @Synchronized fun sources(file: FileChange): DiffSources { check(!closed); return repo.diffSources(file) }
    @Synchronized fun diff(file: FileChange): String { check(!closed); return repo.diffText(file) }
    @Synchronized override fun close() { if (!closed) { closed = true; left.walk.close(); right.walk.close(); repo.close() } }
    companion object {
        fun open(directory: File, request: CompareRequest): ComparisonSession {
            val repo = GitRepo.open(directory)
            return try { ComparisonSession(repo, request) } catch (e: Exception) { repo.close(); throw e }
        }
    }
}
