package gitviz

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.attribute.BasicFileAttributes

/**
 * 工作区或 git 目录里有变化时调用 onChange，用来代替定时跑 git status。
 * onChange 在后台线程调用，可能一下子来很多次，调用方自己合并。
 *
 * - macOS 用 FSEvents：整棵树注册一次，系统自己合并事件。JDK 的 WatchService 在 macOS 上是每 10 秒
 *   把所有文件 stat 一遍，大仓库里比 git status 还费，所以不用。
 * - Windows 用 JDK WatchService 加 FILE_TREE，底层就是系统的 ReadDirectoryChangesW。
 * - Linux 用 JDK WatchService（inotify），不能递归，要一个目录一个目录地注册。
 *
 * 返回 null 表示这个平台或这个仓库监听不了（目录太多、系统上限等），调用方退回定时轮询。
 */
internal fun watchWorkTree(roots: List<File>, gitDirs: List<File>, onChange: () -> Unit): AutoCloseable? {
    val canonicalRoots = roots.map { it.canonicalFile }
    val filter = ChangeFilter(gitDirs.map { it.canonicalPath })
    return runCatching {
        if (isMac) FsEventsWatcher(canonicalRoots, filter, onChange)
        else JdkWatcher(canonicalRoots, filter, onChange)
    }.getOrNull()
}

/**
 * 哪些变化值得重读工作区状态。git 目录里 objects、logs 这些只是对象和历史记录，
 * fetch、gc 时会大量变动，但不影响 git status 的结果。其余的（index、HEAD、refs、MERGE_HEAD、
 * info/exclude、工作区里的文件）都算。
 */
internal class ChangeFilter(gitDirs: List<String>) {
    // Windows 的路径用反斜杠，统一成斜杠再比
    private val gitDirs = gitDirs.map(::normalize)

    private fun normalize(path: String) = path.replace('\\', '/').trimEnd('/')

    fun isRelevant(path: String): Boolean {
        val p = normalize(path)
        for (dir in gitDirs) {
            if (p != dir && !p.startsWith("$dir/")) continue
            val first = p.removePrefix(dir).trimStart('/').substringBefore('/')
            return first !in IgnoredInGitDir && !first.startsWith("gitviz-restore-") && !first.startsWith("gitviz-config-")
        }
        return true
    }

    private companion object {
        val IgnoredInGitDir = setOf("objects", "logs", "lfs", "hooks", "modules", "worktrees", "gitviz-recovery", "gitviz-operation.lock")
    }
}

/** macOS FSEvents。回调在我们自己建的 dispatch 队列上，不占主线程，也不用跑 CFRunLoop。 */
private class FsEventsWatcher(roots: List<File>, private val filter: ChangeFilter, private val onChange: () -> Unit) : AutoCloseable {
    private val process = NativeLibrary.getProcess()
    private val cf = NativeLibrary.getInstance("CoreFoundation")
    private val cs = NativeLibrary.getInstance("CoreServices")

    private fun interface StreamCallback : Callback {
        fun invoke(stream: Pointer?, info: Pointer?, count: Long, paths: Pointer?, flags: Pointer?, ids: Pointer?)
    }

    // 系统一直会调它，要一直留着引用，否则被 GC 后回调会崩
    private val callback = StreamCallback { _, _, count, paths, flags, _ ->
        runCatching {
            val n = count.toInt()
            val mustRescan = flags?.getIntArray(0, n)?.any { it and RESCAN_FLAGS != 0 } == true
            if (mustRescan || paths?.getStringArray(0, n, "UTF-8")?.any(filter::isRelevant) == true) onChange()
        }
    }

    private val strings = roots.map { cf.getFunction("CFStringCreateWithCString").invokePointer(arrayOf(null, it.path, UTF8)) }
    private val array = cf.getFunction("CFArrayCreate").invokePointer(
        arrayOf(null, strings.toTypedArray(), strings.size.toLong(), cf.getGlobalVariableAddress("kCFTypeArrayCallBacks")),
    )
    private val queue = process.getFunction("dispatch_queue_create").invokePointer(arrayOf("gitviz.worktree-watcher", null))
    private val stream = cs.getFunction("FSEventStreamCreate").invokePointer(
        arrayOf(null, callback, null, array, SINCE_NOW, LATENCY_SECONDS, FLAG_WATCH_ROOT),
    ) ?: run { release(); throw IOException("FSEventStreamCreate failed") }

    private var closed = false

    init {
        cs.getFunction("FSEventStreamSetDispatchQueue").invokeVoid(arrayOf(stream, queue))
        if (cs.getFunction("FSEventStreamStart").invokeInt(arrayOf(stream)) and 0xff == 0) {
            close()
            throw IOException("FSEventStreamStart failed")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cs.getFunction("FSEventStreamStop").invokeVoid(arrayOf(stream))
        cs.getFunction("FSEventStreamInvalidate").invokeVoid(arrayOf(stream))
        cs.getFunction("FSEventStreamRelease").invokeVoid(arrayOf(stream))
        release()
    }

    private fun release() {
        array?.let { cf.getFunction("CFRelease").invokeVoid(arrayOf(it)) }
        strings.filterNotNull().forEach { cf.getFunction("CFRelease").invokeVoid(arrayOf(it)) }
        queue?.let { process.getFunction("dispatch_release").invokeVoid(arrayOf(it)) }
    }

    private companion object {
        const val UTF8 = 0x08000100 // kCFStringEncodingUTF8
        const val SINCE_NOW = -1L // kFSEventStreamEventIdSinceNow
        const val LATENCY_SECONDS = 0.3 // 系统把这段时间里的事件合成一批
        const val FLAG_WATCH_ROOT = 0x4 // 仓库目录本身被移走或删除时也通知
        // MustScanSubDirs、UserDropped、KernelDropped、RootChanged：系统丢了事件或者根目录变了，只能整个重读
        const val RESCAN_FLAGS = 0x1 or 0x2 or 0x4 or 0x20
    }
}

/**
 * JDK WatchService。支持 FILE_TREE（Windows）时整棵树注册一次；不支持时（Linux）逐个目录注册，
 * 新建的目录也要补上。highSensitivity 只影响轮询实现（macOS 上测试时用），系统实现会忽略它。
 */
internal class JdkWatcher(
    roots: List<File>,
    private val filter: ChangeFilter,
    private val onChange: () -> Unit,
    highSensitivity: Boolean = false,
) : AutoCloseable {
    private val service = FileSystems.getDefault().newWatchService()
    private val dirs = HashMap<WatchKey, Path>()
    private val sensitivity: Array<WatchEvent.Modifier> =
        if (highSensitivity) arrayOf(com.sun.nio.file.SensitivityWatchEventModifier.HIGH) else emptyArray()
    private var fileTree = true
    private var registered = 0

    private val thread = Thread(::run, "gitviz-worktree-watcher").apply { isDaemon = true }

    init {
        try {
            for (root in roots) register(root.toPath())
        } catch (e: Throwable) {
            service.close()
            throw e
        }
        thread.start()
    }

    private fun register(root: Path) {
        if (fileTree) {
            try {
                dirs[root.register(service, KINDS, com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE, *sensitivity)] = root
                return
            } catch (e: UnsupportedOperationException) {
                fileTree = false
            }
        }
        registerTree(root)
    }

    /** 逐个注册 root 下的目录，跳过 git 目录里不关心的部分。 */
    private fun registerTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!filter.isRelevant(dir.toString())) return FileVisitResult.SKIP_SUBTREE
                if (++registered > MAX_DIRECTORIES) throw IOException("Too many directories to watch")
                dirs[dir.register(service, KINDS, *sensitivity)] = dir
                return FileVisitResult.CONTINUE
            }

            // 读不了的目录跳过，别让整个监听失败
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
    }

    private fun run() {
        while (true) {
            val key = try {
                service.take()
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            val dir = synchronized(dirs) { dirs[key] }
            var relevant = false
            for (event in key.pollEvents()) {
                if (event.kind() == OVERFLOW || dir == null) {
                    relevant = true
                    continue
                }
                val path = dir.resolve(event.context() as Path)
                if (filter.isRelevant(path.toString())) relevant = true
                if (!fileTree && event.kind() == ENTRY_CREATE && Files.isDirectory(path)) {
                    // 新目录里的文件以后也要知道；注册失败（到了系统上限）就漏掉这个目录，定时的兜底读会补上
                    runCatching { synchronized(dirs) { registerTree(path) } }
                }
            }
            if (!key.reset()) synchronized(dirs) { dirs.remove(key) }
            if (relevant) onChange()
        }
    }

    override fun close() {
        service.close()
        thread.interrupt()
    }

    private companion object {
        val KINDS = arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
        // inotify 每个目录占一个监听名额，太大的仓库宁可退回定时轮询
        const val MAX_DIRECTORIES = 50_000
    }
}
