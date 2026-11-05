package gitviz

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 文件在 git 眼里的状态。CLEAN：已跟踪且没改，或者是目录。
 * REMOVED：git rm --cached 过，已从版本库移出、文件还留在磁盘上。
 */
enum class FileMark { CLEAN, MODIFIED, ADDED, DELETED, RENAMED, UNTRACKED, IGNORED, CONFLICTED, REMOVED }

/**
 * 目录里的一项。path 相对工作区，用 / 分隔。
 * ignoredRoot：被忽略规则直接匹配到的那一项（比如 build/），它下面的都跟着被忽略，只在这一项上标出来。
 * dirty：目录里有改动。missing：已删除、磁盘上没有了，只在 git 里还有记录。
 * modified：最后修改时间（毫秒），missing 时为 0。
 * subdirs：目录里有没有子目录，列父目录时顺便看的，决定目录树里画不画箭头。文件为 null。
 */
class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val mark: FileMark,
    val ignoredRoot: Boolean = false,
    val dirty: Boolean = false,
    val missing: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
    val subdirs: Subdirs? = null,
) {
    val ignored: Boolean get() = mark == FileMark.IGNORED
}

/**
 * 一个目录里有没有子目录。any：算上被忽略的；visible：不算被忽略的（隐藏被忽略的文件时用）。
 * null 表示没看完、不确定（超出了预读取的上限），这时照样画箭头，展开后再说。
 */
class Subdirs(val any: Boolean?, val visible: Boolean?) {
    fun has(showIgnored: Boolean): Boolean? = if (showIgnored) any else visible

    companion object {
        val Unknown = Subdirs(null, null)
        val None = Subdirs(false, false)
    }
}

/**
 * 一次 git status --ignored=matching 的结果，按路径查状态。
 * 目录结构从磁盘读，这里只负责标状态，以及补上磁盘上已经没有的已删除文件。
 * 被忽略的目录 git 只报一行 build/，不会列出里面的文件，所以要看祖先目录。
 */
class TreeStatus(files: List<WorkingFile>) {
    private val byPath = HashMap<String, WorkingFile>()
    private val removed = HashSet<String>()
    private val ignoredDirs = HashSet<String>()
    private val ignoredFiles = HashSet<String>()
    private val changedDirs = HashSet<String>()
    /** 每个目录下 git 有记录的子项：目录 → 子项名字 → 是不是目录。 */
    private val knownChildren = HashMap<String, HashMap<String, Boolean>>()

    init {
        for (f in files) {
            if (f.index == '!') {
                if (f.path.endsWith('/')) ignoredDirs += f.path.trimEnd('/') else ignoredFiles += f.path
                continue
            }
            val path = f.path.trimEnd('/')
            val earlier = byPath[path]
            // git rm --cached 之后同一个路径报两行：D（从版本库删除）和 ??（磁盘上的文件没人跟踪了）
            if (earlier != null && (earlier.untracked || f.untracked)) {
                removed += path
                if (f.untracked) continue // 留着 D 那一行，查看改动时看的是这次删除
            }
            byPath[path] = f
            var dir = parentOf(path)
            var child = path
            while (true) {
                knownChildren.getOrPut(dir) { HashMap() }.merge(child.substringAfterLast('/'), child != path) { a, b -> a || b }
                if (dir.isEmpty()) break
                changedDirs += dir
                child = dir
                dir = parentOf(dir)
            }
        }
    }

    /** 本地改动里能找到的那一项，用来跳过去看改动。 */
    fun working(path: String): WorkingFile? = byPath[path]

    /** path 本身被忽略规则匹配到。 */
    fun isIgnoredRoot(path: String): Boolean = path in ignoredFiles || path in ignoredDirs

    fun isIgnored(path: String): Boolean {
        if (isIgnoredRoot(path)) return true
        var dir = parentOf(path)
        while (dir.isNotEmpty()) {
            if (dir in ignoredDirs) return true
            dir = parentOf(dir)
        }
        return false
    }

    fun hasChanges(dir: String): Boolean = dir in changedDirs

    fun mark(path: String, isDir: Boolean): FileMark {
        if (isIgnored(path)) return FileMark.IGNORED
        if (isDir) return FileMark.CLEAN
        if (path in removed) return FileMark.REMOVED
        val f = byPath[path] ?: return FileMark.CLEAN
        return when {
            f.conflicted -> FileMark.CONFLICTED
            f.untracked -> FileMark.UNTRACKED
            else -> when (if (f.worktree != ' ') f.worktree else f.index) {
                'A' -> FileMark.ADDED
                'D' -> FileMark.DELETED
                'R', 'C' -> FileMark.RENAMED
                else -> FileMark.MODIFIED
            }
        }
    }

    /** dir 下 git 有记录的子项（名字 → 是不是目录），用来补上已经从磁盘删掉的文件。 */
    fun knownChildren(dir: String): Map<String, Boolean> = knownChildren[dir].orEmpty()

    companion object {
        val Empty = TreeStatus(emptyList())
    }
}

internal fun parentOf(path: String): String = path.substringBeforeLast('/', "")

/** 一个目录列出来的结果。omitted：太多了没列出来的个数。 */
class DirListing(val entries: List<FileEntry>, val omitted: Int = 0)

/** 一个目录最多列多少项，node_modules 这样的目录一层就可能有上万个。 */
internal const val MAX_DIR_ENTRIES = 5000

/**
 * 列目录时顺便看每个子目录里有没有子目录，三个上限让最坏情况也有个数：
 * 每个子目录最多看这么多项，一次最多看这么多个子目录，加起来最多读这么多项。超出的算不确定。
 * 本机 SSD 上实测：普通目录不到 1 毫秒，296 个子目录的 /System/Library/Frameworks 第一次 70 毫秒左右。
 */
internal class ProbeLimits(val perDir: Int = 1000, val maxDirs: Int = 500, val budget: Int = 20_000) {
    companion object {
        val Default = ProbeLimits()
    }
}

/**
 * 看 dir（相对路径）里有没有子目录，用流式读，找到就停，不把整个目录读进内存。
 * 被忽略的子目录要接着往下找，看有没有不被忽略的；还没提交的已删除目录也算。
 * read 记已经读了多少项，所有子目录共用一份预算。
 */
private fun probeSubdirs(root: File, dir: String, status: TreeStatus, limits: ProbeLimits, read: IntArray, cancelled: () -> Boolean): Subdirs {
    // 自己被忽略时，隐藏被忽略的文件时它根本不出现，visible 无所谓，记成没有
    val dirIgnored = status.isIgnored(dir)
    var any = false
    var visible = !dirIgnored && status.knownChildren(dir).any { it.value } // 已删除、还没提交的子目录
    var complete = true
    try {
        Files.newDirectoryStream(File(root, dir).toPath()).use { stream ->
            var seen = 0
            for (child in stream) {
                if (any && (visible || dirIgnored)) break
                if (seen >= limits.perDir || read[0] >= limits.budget || cancelled()) {
                    complete = false
                    break
                }
                seen++
                read[0]++
                val name = child.fileName.toString()
                if (name == ".git" || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
                any = true
                if (!dirIgnored && !status.isIgnored("$dir/$name")) visible = true
            }
        }
    } catch (e: java.io.IOException) {
        complete = false // 读不了，不知道
    }
    // 没看完时，没找到的就是不确定
    return Subdirs(
        any = if (any || visible) true else if (complete) false else null,
        visible = if (visible) true else if (complete || dirIgnored) false else null,
    )
}

/**
 * 列出工作区里 dir（相对路径，根目录是 ""）的直接子项，从磁盘读，包括被忽略的。
 * .git 不列。已删除、还没提交的文件磁盘上没有，按 git status 补上，标成 missing。
 * 目录在前，其余按名字排序，不分大小写。符号链接当成文件，不跟进去，免得绕圈。
 */
internal fun listDirectory(
    root: File,
    dir: String,
    status: TreeStatus,
    limits: ProbeLimits = ProbeLimits.Default,
    cancelled: () -> Boolean = { false },
): DirListing {
    val folder = if (dir.isEmpty()) root else File(root, dir)
    val children = folder.listFiles().orEmpty().filter { it.name != ".git" }
    val onDisk = HashSet<String>()
    val entries = ArrayList<FileEntry>()
    for (file in children) {
        onDisk += file.name
        val path = if (dir.isEmpty()) file.name else "$dir/${file.name}"
        val isDir = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        entries += FileEntry(
            name = file.name,
            path = path,
            isDir = isDir,
            mark = status.mark(path, isDir),
            ignoredRoot = status.isIgnoredRoot(path),
            dirty = isDir && status.hasChanges(path),
            size = if (isDir) 0 else file.length(),
            modified = file.lastModified(),
        )
    }
    for ((name, isDir) in status.knownChildren(dir)) {
        if (name in onDisk) continue
        val path = if (dir.isEmpty()) name else "$dir/$name"
        entries += FileEntry(name, path, isDir, if (isDir) FileMark.CLEAN else status.mark(path, false),
            dirty = isDir, missing = true)
    }
    entries.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }, { it.name }))
    // 先排序再看子目录，超出上限时不确定的是排在后面的那些
    val read = IntArray(1)
    var probed = 0
    for (i in entries.indices) {
        val e = entries[i]
        if (!e.isDir) continue
        entries[i] = e.withSubdirs(when {
            e.missing -> Subdirs(status.knownChildren(e.path).any { it.value }, status.knownChildren(e.path).any { it.value })
            probed++ >= limits.maxDirs || read[0] >= limits.budget -> Subdirs.Unknown
            else -> probeSubdirs(root, e.path, status, limits, read, cancelled)
        })
    }
    return if (entries.size > MAX_DIR_ENTRIES) DirListing(entries.subList(0, MAX_DIR_ENTRIES).toList(), entries.size - MAX_DIR_ENTRIES)
    else DirListing(entries)
}

private fun FileEntry.withSubdirs(s: Subdirs) =
    FileEntry(name, path, isDir, mark, ignoredRoot, dirty, missing, size, modified, s)

/** 左边目录树的一行：一个目录，或者正在读的目录下面的占位行。 */
sealed class DirRow(val key: String, val depth: Int) {
    class Item(val entry: FileEntry, depth: Int, val expanded: Boolean, val hasSubdirs: Boolean?) : DirRow(entry.path, depth)
    class Loading(dir: String, depth: Int) : DirRow("$dir/…loading", depth)
}

/**
 * 按展开的目录把目录树摊成行，只有目录。还没读到的展开目录下面放一行 Loading。
 * hasSubdirs 为 null 表示不知道有没有子目录；已知没有子目录的，展开了也不往下摊，不出 Loading 那一行。
 */
internal fun flattenDirTree(listings: Map<String, DirListing>, expanded: Set<String>, showIgnored: Boolean): List<DirRow> {
    val rows = ArrayList<DirRow>()
    fun visible(e: FileEntry) = e.isDir && (showIgnored || !e.ignored)
    fun walk(dir: String, depth: Int) {
        val listing = listings[dir] ?: run {
            rows += DirRow.Loading(dir, depth)
            return
        }
        for (entry in listing.entries) {
            if (!visible(entry)) continue
            // 读过它的内容就以内容为准，否则用列父目录时预读的结果
            val subdirs = listings[entry.path]?.entries?.any(::visible) ?: entry.subdirs?.has(showIgnored)
            val open = entry.path in expanded && subdirs != false
            rows += DirRow.Item(entry, depth, open, subdirs)
            if (open) walk(entry.path, depth + 1)
        }
    }
    walk("", 0)
    return rows
}

/** 需要读的目录：根目录，加上所有祖先都展开了的展开目录，再加上右边正在看的目录。 */
internal fun directoriesToRead(expanded: Set<String>, current: String): List<String> {
    val dirs = linkedSetOf("")
    for (dir in expanded.sorted()) {
        var parent = parentOf(dir)
        var visible = true
        while (parent.isNotEmpty()) {
            if (parent !in expanded) { visible = false; break }
            parent = parentOf(parent)
        }
        if (visible) dirs += dir
    }
    dirs += current
    return dirs.toList()
}

/** path 的所有祖先目录，不含根目录 ""，从近到远。 */
internal fun ancestorsOf(path: String): List<String> {
    val result = ArrayList<String>()
    var dir = parentOf(path)
    while (dir.isNotEmpty()) {
        result += dir
        dir = parentOf(dir)
    }
    return result
}

/** 文件大小，比如 812 B、4.2 KB、1.3 MB。 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
}

/**
 * 把 path 加进工作区根目录的 .gitignore。用 / 开头锚定到这一个路径，目录末尾加 /，
 * 免得同名的其他文件也被忽略。文件没以换行结尾时先补一个。
 */
internal fun appendToGitignore(root: File, path: String, isDir: Boolean) {
    val file = File(root, ".gitignore")
    // 前面有 / 了，# 和 ! 不会被当成注释或取反；通配符和末尾空格要转义，才是按字面匹配
    val escaped = path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[")
        .let { if (it.endsWith(" ")) it.dropLast(1) + "\\ " else it }
    val line = "/$escaped" + if (isDir) "/" else ""
    val existing = if (file.isFile) file.readText() else ""
    val prefix = if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
    file.appendText(prefix + line + "\n")
}
