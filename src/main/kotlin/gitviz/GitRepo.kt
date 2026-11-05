package gitviz

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File

enum class RefKind { HEAD, BRANCH, REMOTE, TAG, STASH }

data class RefLabel(val name: String, val kind: RefKind, val isCurrent: Boolean = false)

/** 一个引用和它指向的提交。指向的提交不一定在已加载的范围内。 */
data class RefTarget(val label: RefLabel, val commitId: String)

/**
 * timeSeconds 是作者写下这个提交的时间，和 author 配对显示。
 * rebase、cherry-pick、amend 会改提交者和提交时间，不改作者，所以两组可能不同。
 */
data class CommitInfo(
    val id: String,
    val parents: List<String>,
    val shortMessage: String,
    val fullMessage: String,
    val author: String,
    val email: String,
    val timeSeconds: Long,
    val refs: List<RefLabel>,
    val committer: String = author,
    val committerEmail: String = email,
    val commitTimeSeconds: Long = timeSeconds,
) {
    val shortId: String get() = id.take(8)

    /** 储藏不是真正的提交历史：不能 checkout、cherry-pick，只在图上标出它基于哪个提交。 */
    val isStash: Boolean get() = refs.any { it.kind == RefKind.STASH }

    /** 提交者或提交时间和作者不同，详情里要单独显示一行。 */
    val committedSeparately: Boolean
        get() = committer != author || committerEmail != email || commitTimeSeconds != timeSeconds
}

data class FileChange(
    val type: DiffEntry.ChangeType,
    val oldPath: String,
    val newPath: String,
    val additions: Int,
    val deletions: Int,
    internal val entry: DiffEntry,
) {
    /** 只有改动、重命名、复制才谈得上权限变化；新增和删除的另一边是 MISSING。 */
    val modeChange: ModeChange?
        get() {
            if (type == DiffEntry.ChangeType.ADD || type == DiffEntry.ChangeType.DELETE) return null
            val old = Integer.toOctalString(entry.oldMode.bits)
            val new = Integer.toOctalString(entry.newMode.bits)
            return if (old != new) ModeChange(old, new) else null
        }

    /** 文件在这个提交里所在的路径；删除的文件取删除前的路径。 */
    val path: String get() = if (type == DiffEntry.ChangeType.DELETE) oldPath else newPath

    val displayPath: String
        get() = when (type) {
            DiffEntry.ChangeType.DELETE -> oldPath
            DiffEntry.ChangeType.RENAME, DiffEntry.ChangeType.COPY -> "$oldPath → $newPath"
            else -> newPath
        }
}

/** Blame 的一行：text 是文件内容，其余是最后改动这一行的提交。 */
data class BlameLine(
    val lineNo: Int,
    val text: String,
    val commitId: String,
    val author: String,
    val timeSeconds: Long,
)

/** commits 是拓扑序。truncated 表示历史还没读完，可以继续加载。 */
data class LoadResult(
    val commits: List<CommitInfo>,
    val truncated: Boolean,
    val currentBranch: String?,
    val refs: List<RefTarget>,
)

/** 全库搜索的结果。complete 为 false 表示中途停下（取消或到了上限），后面可能还有。 */
data class SearchResult(val ids: List<String>, val complete: Boolean)

/** 搜索说明、作者、哈希前缀和引用名，不区分大小写。q 已去掉首尾空白且不为空。 */
internal fun matchesQuery(q: String, shortMessage: String, author: String, id: String, refNames: List<String>): Boolean =
    shortMessage.contains(q, ignoreCase = true) || author.contains(q, ignoreCase = true) ||
        id.startsWith(q, ignoreCase = true) || refNames.any { it.contains(q, ignoreCase = true) }

/**
 * 分页读提交历史：一直开着一个 RevWalk，每次接着往下读。
 *
 * 提交时间不准时，子提交可能比父提交晚读到，甚至落在下一页，所以每页都把已读的全部重新排拓扑序，
 * 而不是只排新读的这一页。几万个提交重排一次是几十毫秒。
 *
 * 不是线程安全的，同一时间只能有一个线程调用。
 */
class CommitPager internal constructor(private val repo: GitRepo, val path: String? = null) : AutoCloseable {
    private val walk = repo.newHistoryWalk(path)
    // 只看某个文件时，储藏和这个文件的历史无关
    private val stashes = if (path == null) repo.stashCommits() else emptyList()
    private val labels = repo.collectRefLabels()
    private val refs = labels.flatMap { (id, list) -> list.filter { it.kind != RefKind.HEAD }.map { RefTarget(it, id) } }
    private val currentBranch = repo.repository.branch
    private val walkOrder = ArrayList<CommitInfo>()
    private var pending: RevCommit? = null
    private var exhausted = false
    private var closed = false

    private fun next(): RevCommit? {
        pending?.let { pending = null; return it }
        if (exhausted) return null
        return walk.next().also { if (it == null) exhausted = true }
    }

    val loadedCount: Int get() = walkOrder.size

    /** 再读最多 count 个提交，返回到目前为止读到的全部。 */
    fun load(count: Int): LoadResult {
        check(!closed) { t("Repository is closed", "仓库已关闭") }
        repeat(count) {
            val c = next() ?: return@repeat
            with(repo) { walkOrder += c.toInfo(labels[c.name].orEmpty()) }
        }
        // 刚好读满一页时还不知道后面有没有，先看一眼下一个，留到下一页
        if (pending == null && !exhausted) pending = walk.next().also { if (it == null) exhausted = true }
        // 储藏所基于的提交加载了，它才挂得上去
        val loaded = walkOrder.mapTo(HashSet()) { it.id }
        val commits = insertByTime(walkOrder, stashes.filter { it.parents.firstOrNull() in loaded })
        return LoadResult(GraphLayout.topoSort(commits), pending != null, currentBranch, refs)
    }

    override fun close() {
        if (closed) return
        closed = true
        walk.close()
        repo.forget(this)
    }
}

/** 只读访问一个本地仓库。所有方法都会阻塞，请在 IO 线程调用。 */
class GitRepo private constructor(val repository: Repository) : AutoCloseable {

    val workTree: File? get() = if (repository.isBare) null else repository.workTree
    val displayName: String get() = workTree?.name ?: repository.directory.name

    private val pagers = ArrayList<CommitPager>()

    /**
     * 一页一页读提交历史，用完随仓库一起关闭。
     * path 不为空时只读改动过这个文件的提交，父提交会改写成过滤后历史里最近的祖先，图才连得上。
     */
    fun openHistory(path: String? = null): CommitPager =
        synchronized(pagers) { CommitPager(this, path).also { pagers += it } }

    internal fun forget(pager: CommitPager) = synchronized(pagers) { pagers.remove(pager) }

    /** 一次读最多 limit 个提交。 */
    fun loadCommits(limit: Int = PAGE_SIZE): LoadResult = CommitPager(this).use { it.load(limit) }

    /** 从所有分支、远程分支、标签和 HEAD 出发往回走，notes、stash 这类内部引用不算提交历史。 */
    internal fun newHistoryWalk(path: String? = null): RevWalk {
        val walk = RevWalk(repository)
        // 和 git log -- path 一样：只留改动过它的提交，并改写父提交。注意 JGit 改写父提交要先读完整个历史
        if (path != null) walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF))
        // 不用 RevSort.TOPO：它会先读完整个历史才输出第一个提交，没法分页
        walk.sort(RevSort.COMMIT_TIME_DESC, true)
        val starts = repository.refDatabase
            .getRefsByPrefix(Constants.R_HEADS, Constants.R_REMOTES, Constants.R_TAGS)
            .mapNotNull { it.objectId } + listOfNotNull(repository.resolve(Constants.HEAD))
        for (id in starts.distinct()) {
            val commit = runCatching { walk.peel(walk.parseAny(id)) as? RevCommit }.getOrNull()
            if (commit != null) walk.markStart(commit)
        }
        return walk
    }

    /**
     * 在全部历史里找匹配 query 的提交，返回 id，新的在前。规则和 [filterCommits] 一样。
     * 最多返回 limit 个；cancelled 返回 true 时尽快停下，返回已找到的。
     */
    fun searchCommits(
        query: String,
        limit: Int = MAX_SEARCH_RESULTS,
        path: String? = null,
        cancelled: () -> Boolean = { false },
    ): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), complete = true)
        val refNames = collectRefLabels().mapValues { (_, labels) -> labels.map { it.name } }
        val ids = ArrayList<String>()
        newHistoryWalk(path).use { walk ->
            while (true) {
                if (cancelled() || ids.size >= limit) return SearchResult(ids, complete = false)
                val c = walk.next() ?: break
                if (matchesQuery(q, c.shortMessage, c.authorIdent.name, c.name, refNames[c.name].orEmpty())) ids += c.name
            }
        }
        return SearchResult(ids, complete = true)
    }

    /** 和第 parentIndex 个父提交比较的改动。合并提交可以选和哪个父提交比；根提交和空树比。 */
    fun changes(commitId: String, parentIndex: Int = 0): List<FileChange> {
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(ObjectId.fromString(commitId))
            require(commit.parentCount == 0 || parentIndex in 0 until commit.parentCount) {
                t("Commit ${commit.name.take(7)} has no parent #${parentIndex + 1}", "提交 ${commit.name.take(7)} 没有第 ${parentIndex + 1} 个父提交")
            }
            val oldTree: RevTree? = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(parentIndex)).tree else null
            DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
                df.setRepository(repository)
                df.setDiffComparator(RawTextComparator.DEFAULT)
                df.isDetectRenames = true
                return df.scan(oldTree, commit.tree).map {
                    val edits = df.toFileHeader(it).toEditList()
                    FileChange(it.changeType, it.oldPath, it.newPath, edits.sumOf { e -> e.lengthB }, edits.sumOf { e -> e.lengthA }, it)
                }
            }
        }
    }

    /** 从 commitId 往回，改动过 path 的提交，最新的在前。 */
    fun fileHistory(commitId: String, path: String, limit: Int = MAX_FILE_HISTORY): List<CommitInfo> =
        Git(repository).log().add(ObjectId.fromString(commitId)).addPath(path).setMaxCount(limit).call()
            .map { it.toInfo(emptyList()) }

    /** commitId 时 path 的每一行最后是谁改的。二进制文件没有行，返回空列表。 */
    fun blame(commitId: String, path: String): List<BlameLine> {
        val result = Git(repository).blame().setStartCommit(ObjectId.fromString(commitId)).setFilePath(path).call()
            ?: return emptyList()
        val contents = result.resultContents
        return (0 until contents.size()).map { i ->
            val source = result.getSourceCommit(i)
            BlameLine(i + 1, contents.getString(i), source.name, source.authorIdent.name, source.authorIdent.whenAsInstant.epochSecond)
        }
    }

    fun diffText(change: FileChange): String {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { df ->
            df.setRepository(repository)
            df.setDiffComparator(RawTextComparator.DEFAULT)
            df.format(change.entry)
        }
        val text = out.toString(Charsets.UTF_8)
        return if (text.length > MAX_DIFF_CHARS) {
            text.take(MAX_DIFF_CHARS) + t("\n\n… diff too large, truncated (${text.length} characters)", "\n\n… diff 过大，已截断（${text.length} 字符）")
        } else text
    }

    /**
     * 储藏，最新的在前。储藏提交的第二、三个父提交是暂存区和未跟踪文件，只是 git 的实现细节，
     * 这里只留第一个父提交，也就是储藏时所在的提交。
     */
    internal fun stashCommits(): List<CommitInfo> = runCatching {
        Git(repository).stashList().call().mapIndexed { i, c ->
            // 说明形如 “WIP on main: abc1234 消息” 或 “On main: 消息”，去掉前缀
            val message = c.shortMessage.substringAfter(": ", c.shortMessage)
            c.toInfo(listOf(RefLabel("stash@{$i}", RefKind.STASH)))
                .copy(parents = listOfNotNull(c.parents.firstOrNull()?.name), shortMessage = message)
        }
    }.getOrDefault(emptyList())

    internal fun collectRefLabels(): Map<String, List<RefLabel>> {
        val map = HashMap<String, MutableList<RefLabel>>()
        val current = repository.fullBranch // 分离 HEAD 时是提交哈希
        val detached = current != null && !current.startsWith(Constants.R_REFS)

        for (ref in repository.refDatabase.refs) {
            val name = ref.name
            val label = when {
                name.startsWith(Constants.R_HEADS) ->
                    RefLabel(name.removePrefix(Constants.R_HEADS), RefKind.BRANCH, isCurrent = name == current)
                name.startsWith(Constants.R_REMOTES) ->
                    if (name.endsWith("/HEAD")) null
                    else RefLabel(name.removePrefix(Constants.R_REMOTES), RefKind.REMOTE)
                name.startsWith(Constants.R_TAGS) ->
                    RefLabel(name.removePrefix(Constants.R_TAGS), RefKind.TAG)
                else -> null
            } ?: continue
            val peeled = repository.refDatabase.peel(ref)
            val target = (peeled.peeledObjectId ?: peeled.objectId)?.name ?: continue
            map.getOrPut(target) { mutableListOf() } += label
        }
        if (detached) {
            repository.resolve(Constants.HEAD)?.let {
                map.getOrPut(it.name) { mutableListOf() }.add(0, RefLabel("HEAD", RefKind.HEAD, isCurrent = true))
            }
        }
        return map
    }

    internal fun RevCommit.toInfo(refs: List<RefLabel>) = CommitInfo(
        id = name,
        parents = parents.map { it.name },
        shortMessage = shortMessage,
        fullMessage = fullMessage,
        author = authorIdent.name,
        email = authorIdent.emailAddress,
        timeSeconds = authorIdent.whenAsInstant.epochSecond,
        refs = refs.sortedBy { it.kind.ordinal },
        committer = committerIdent.name,
        committerEmail = committerIdent.emailAddress,
        commitTimeSeconds = commitTime.toLong(),
    )

    override fun close() {
        synchronized(pagers) { pagers.toList().forEach { it.close() } }
        repository.close()
    }

    companion object {
        /** 一页读多少个提交。 */
        const val PAGE_SIZE = 2000
        const val MAX_SEARCH_RESULTS = 5000
        const val MAX_DIFF_CHARS = 1_000_000
        const val MAX_FILE_HISTORY = 500

        /** 从给定目录向上查找 .git，找不到时抛异常。 */
        fun open(dir: File): GitRepo {
            val builder = FileRepositoryBuilder().readEnvironment().findGitDir(dir.canonicalFile) // 规范化路径，传 "." 时仓库名不会变成 "."
            require(builder.gitDir != null) { t("Not a git repository: ${dir.absolutePath}", "不是 git 仓库：${dir.absolutePath}") }
            return GitRepo(builder.setMustExist(true).build())
        }
    }
}

/**
 * 把 extras 按提交时间插进 commits：放在第一个比它旧的提交前面，commits 自己的顺序不变。
 * 储藏一定比它基于的提交新，插完再排拓扑序，就会落在那个提交上方。
 */
internal fun insertByTime(commits: List<CommitInfo>, extras: List<CommitInfo>): List<CommitInfo> {
    if (extras.isEmpty()) return commits
    val pending = extras.sortedByDescending { it.commitTimeSeconds }
    val result = ArrayList<CommitInfo>(commits.size + extras.size)
    var next = 0
    for (c in commits) {
        while (next < pending.size && pending[next].commitTimeSeconds >= c.commitTimeSeconds) result += pending[next++]
        result += c
    }
    while (next < pending.size) result += pending[next++]
    return result
}
