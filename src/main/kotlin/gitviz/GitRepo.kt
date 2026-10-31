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
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File

enum class RefKind { HEAD, BRANCH, REMOTE, TAG }

data class RefLabel(val name: String, val kind: RefKind, val isCurrent: Boolean = false)

/** 一个引用和它指向的提交。指向的提交不一定在已加载的范围内。 */
data class RefTarget(val label: RefLabel, val commitId: String)

data class CommitInfo(
    val id: String,
    val parents: List<String>,
    val shortMessage: String,
    val fullMessage: String,
    val author: String,
    val email: String,
    val timeSeconds: Long,
    val refs: List<RefLabel>,
) {
    val shortId: String get() = id.take(8)
}

data class FileChange(
    val type: DiffEntry.ChangeType,
    val oldPath: String,
    val newPath: String,
    val additions: Int,
    val deletions: Int,
    internal val entry: DiffEntry,
) {
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

data class LoadResult(
    val commits: List<CommitInfo>,
    val truncated: Boolean,
    val currentBranch: String?,
    val refs: List<RefTarget>,
)

/** 只读访问一个本地仓库。所有方法都会阻塞，请在 IO 线程调用。 */
class GitRepo private constructor(val repository: Repository) : AutoCloseable {

    val workTree: File? get() = if (repository.isBare) null else repository.workTree
    val displayName: String get() = workTree?.name ?: repository.directory.name

    fun loadCommits(limit: Int = MAX_COMMITS): LoadResult {
        val labels = collectRefLabels()
        val result = ArrayList<CommitInfo>()
        var truncated = false

        RevWalk(repository).use { walk ->
            // 不用 RevSort.TOPO：它会先读完整个历史才输出第一个提交，limit 就白设了
            walk.sort(RevSort.COMMIT_TIME_DESC, true)

            // 只走分支和标签，notes、stash 这类内部引用不算提交历史
            val starts = repository.refDatabase
                .getRefsByPrefix(Constants.R_HEADS, Constants.R_REMOTES, Constants.R_TAGS)
                .mapNotNull { it.objectId } + listOfNotNull(repository.resolve(Constants.HEAD))
            for (id in starts.distinct()) {
                val commit = runCatching { walk.peel(walk.parseAny(id)) as? RevCommit }.getOrNull()
                if (commit != null) walk.markStart(commit)
            }

            for (c in walk) {
                if (result.size >= limit) { truncated = true; break }
                result += c.toInfo(labels[c.name].orEmpty())
            }
        }
        val refs = labels.flatMap { (id, list) ->
            list.filter { it.kind != RefKind.HEAD }.map { RefTarget(it, id) }
        }
        return LoadResult(GraphLayout.topoSort(result), truncated, repository.branch, refs)
    }

    fun changes(commitId: String): List<FileChange> {
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(ObjectId.fromString(commitId))
            val oldTree: RevTree? = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null
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
            BlameLine(i + 1, contents.getString(i), source.name, source.authorIdent.name, source.commitTime.toLong())
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
            text.take(MAX_DIFF_CHARS) + "\n\n… diff 过大，已截断（${text.length} 字符）"
        } else text
    }

    private fun collectRefLabels(): Map<String, List<RefLabel>> {
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

    private fun RevCommit.toInfo(refs: List<RefLabel>) = CommitInfo(
        id = name,
        parents = parents.map { it.name },
        shortMessage = shortMessage,
        fullMessage = fullMessage,
        author = authorIdent.name,
        email = authorIdent.emailAddress,
        timeSeconds = commitTime.toLong(),
        refs = refs.sortedBy { it.kind.ordinal },
    )

    override fun close() = repository.close()

    companion object {
        const val MAX_COMMITS = 5000
        const val MAX_DIFF_CHARS = 1_000_000
        const val MAX_FILE_HISTORY = 500

        /** 从给定目录向上查找 .git，找不到时抛异常。 */
        fun open(dir: File): GitRepo {
            val builder = FileRepositoryBuilder().readEnvironment().findGitDir(dir.canonicalFile) // 规范化路径，传 "." 时仓库名不会变成 "."
            require(builder.gitDir != null) { "不是 git 仓库：${dir.absolutePath}" }
            return GitRepo(builder.setMustExist(true).build())
        }
    }
}
