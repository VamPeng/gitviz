package gitviz

import java.io.File
import java.util.concurrent.CancellationException

internal class ReplaySession private constructor(val repo: GitRepo, val commits: List<CommitInfo>, val refs: List<RefTarget>) : AutoCloseable {
    val indexById = commits.withIndex().associate { it.value.id to it.index }

    /** A bounded viewport onto the already-revealed history, computed off the UI thread. */
    fun frame(position: Int): RepoState {
        require(position in commits.indices)
        val visible = commits.subList((position + 1 - GitRepo.PAGE_SIZE).coerceAtLeast(0), position + 1).asReversed()
        val result = LoadResult(visible, position + 1 > GitRepo.PAGE_SIZE, null, refs)
        return RepoState(repo, result, GraphLayout.compute(visible, BranchColors(refs)), null)
    }

    override fun close() = repo.close()

    companion object {
        const val MAX_COMMITS = 100_000
        fun open(directory: File, refs: Set<String> = emptySet(), cancelled: () -> Boolean = { false }): ReplaySession {
            val repo = GitRepo.open(directory)
            try {
                val snapshot = repo.refsSnapshot()
                val roots = repo.historyRoots(refs)
                val labels = repo.collectRefLabels().mapValues { (_, labels) -> labels.filter { it.kind != RefKind.HEAD && it.kind != RefKind.STASH }.map { it.copy(isCurrent = false) } }
                val commits = ArrayList<CommitInfo>()
                repo.newHistoryWalk(startIds = roots).use { walk ->
                    while (true) {
                        if (cancelled()) throw CancellationException("Replay cancelled")
                        val c = walk.next() ?: break
                        check(commits.size < MAX_COMMITS) { t("Replay supports up to $MAX_COMMITS commits. Select a smaller branch scope.", "回放最多支持 $MAX_COMMITS 个提交，请缩小分支范围。") }
                        commits += with(repo) { c.toInfo(labels[c.name].orEmpty()) }
                        c.disposeBody()
                    }
                }
                check(snapshot == repo.refsSnapshot()) { t("References changed while loading. Refresh the replay.", "加载期间引用已改变，请刷新回放。") }
                val ordered = GraphLayout.topoSort(commits).asReversed()
                return ReplaySession(repo, ordered, labels.flatMap { (id, labels) -> labels.map { RefTarget(it, id) } })
            } catch (e: Exception) { repo.close(); throw e }
        }
    }
}
