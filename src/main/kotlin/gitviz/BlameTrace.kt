package gitviz

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

internal data class TraceLocation(val commit: String, val path: String, val firstLine: Int = 0, val lastLine: Int = firstLine)
internal data class TraceResult(val location: TraceLocation?, val message: String)

internal fun GitRepo.fileBytes(commit: String, path: String, limit: Long = 1_048_576): ByteArray? = RevWalk(repository).use { walk ->
    val tree = walk.parseCommit(ObjectId.fromString(commit)).tree
    TreeWalk.forPath(repository, path, tree)?.use { entry ->
        val loader = repository.open(entry.getObjectId(0))
        check(loader.size <= limit) { t("File is too large to trace or highlight", "文件过大，无法追溯或高亮") }
        loader.bytes
    }
}

internal fun GitRepo.beforeDeletion(commit: CommitInfo, file: FileChange, parentIndex: Int = 0): TraceLocation {
    require(file.type == DiffEntry.ChangeType.DELETE)
    val parent = commit.parents.getOrNull(parentIndex) ?: error("Parent version is unavailable")
    val matches = RevWalk(repository).use { walk ->
        TreeWalk.forPath(repository, file.oldPath, walk.parseCommit(ObjectId.fromString(parent)).tree)?.use { it.getObjectId(0).name.startsWith(file.entry.oldId.name()) } == true
    }
    check(matches) { t("Deleted file's selected parent object is unavailable", "删除前所选父版本对象不可用") }
    return TraceLocation(parent, file.oldPath)
}

internal fun GitRepo.tracePrevious(line: BlameLine, parentIndex: Int = 0): TraceResult {
    val source = commit(line.commitId)
    if (source.parents.isEmpty()) return TraceResult(null, t("This line was introduced here; no earlier version", "此行在这里引入，没有更早版本"))
    require(parentIndex in source.parents.indices)
    val parent = source.parents[parentIndex]
    val path = line.sourcePath
    val change = changes(source.id, parentIndex).firstOrNull { it.newPath == path }
    val oldPath = if (change?.type in setOf(DiffEntry.ChangeType.RENAME, DiffEntry.ChangeType.COPY)) checkNotNull(change).oldPath else path
    val oldBytes = fileBytes(parent, oldPath) ?: return TraceResult(null, t("This file was introduced here; no earlier version", "此文件在这里引入，没有更早版本"))
    val newBytes = fileBytes(source.id, path) ?: error("Source path is unavailable")
    check(strictText(oldBytes) != null && strictText(newBytes) != null) { t("Binary or non-UTF-8 content cannot be traced by line", "二进制或非 UTF-8 内容无法按行追溯") }
    val old = RawText(oldBytes); val new = RawText(newBytes)
    val index = line.sourceLine - 1
    require(index in 0 until new.size()) { "Source line is unavailable" }
    val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, old, new)
    var offset = 0
    for (edit in edits) {
        if (index < edit.beginB) break
        if (index < edit.endB) {
            if (edit.lengthA == 0) return TraceResult(null, t("This line was introduced here; no earlier version", "此行在这里引入，没有更早版本"))
            return TraceResult(TraceLocation(parent, oldPath, edit.beginA + 1, edit.endA), t("Replaced content corresponds to this parent interval; choose a line to continue", "替换内容对应父版本的这个区间，请选择一行继续追溯"))
        }
        offset += edit.lengthA - edit.lengthB
    }
    val previous = index + offset + 1
    require(previous in 1..old.size())
    return TraceResult(TraceLocation(parent, oldPath, previous), t("Previous version", "上一版本"))
}
