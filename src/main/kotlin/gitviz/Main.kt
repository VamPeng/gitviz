package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.swing.JFileChooser

fun main(args: Array<String>) {
    val initial = args.firstOrNull()?.let(::File)
        ?: Settings.recentRepos.firstOrNull()?.let(::File)
        ?: File(System.getProperty("user.dir"))
    System.setProperty(
        "apple.awt.application.appearance",
        if (Palette.isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
    )
    val size = DpSize(1360.dp, 860.dp)
    val root: @Composable FrameWindowScope.() -> Unit = {
        GitVizTheme {
            Surface(
                Modifier.fillMaxSize().clip(RoundedCornerShape(if (isMac) 16.dp else 0.dp)),
                color = Palette.Ground,
                contentColor = Palette.Ink,
            ) {
                App(initial, reportInitialError = args.isNotEmpty())
            }
        }
    }
    val appIcon = AppIcon.load()
    // 打包后的 .app 用 Info.plist 里的 .icns；./gradlew run 时 Dock 里默认是 Java 图标，这里换掉
    if (appIcon != null && java.awt.Taskbar.isTaskbarSupported() &&
        java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)
    ) {
        java.awt.Taskbar.getTaskbar().iconImage = appIcon
    }
    application {
        if (isMac) {
            GlassWindow("GitViz", size, onClose = ::exitApplication) { root() }
        } else {
            Window(
                onCloseRequest = ::exitApplication,
                title = "GitViz",
                icon = appIcon?.let { remember { BitmapPainter(it.toComposeImageBitmap()) } },
                state = rememberWindowState(size = size),
            ) {
                root()
            }
        }
    }
}

/** 应用图标，来自 resources/icon.png（scripts/icons.py 导出）。读不到就用系统默认图标。 */
private object AppIcon {
    fun load(): java.awt.image.BufferedImage? =
        AppIcon::class.java.getResourceAsStream("/icon.png")?.use { javax.imageio.ImageIO.read(it) }
}

/** 启动时加载画面至少显示多久。仓库打开得太快时，画面一闪就切走，看着像闪烁。 */
private const val STARTUP_MIN_SHOW_MS = 600L

// TODO 启动时从转圈切到主页会卡 0.5～0.6 秒，转圈停住。2026-11-05 实测：不是 GC（期间 0～50 ms），
//  是主线程第一次画主页时加载了约 1100 个 Compose 类（文本组件、懒加载列表、排版最多）。
//  方案：转圈期间在后台线程用 ImageComposeScene 离屏画一遍主页，提前加载这些类；要先确认和真实窗口同时跑不会互相干扰。
//  备选：AppCDS 类归档，整个启动都能快，但要改打包流程。

/** 监听不了文件变化时，多久读一次工作区状态。 */
private const val POLL_INTERVAL_MS = 3000L

/** 监听到变化后，两次读工作区状态之间至少隔多久。 */
private const val MIN_RELOAD_GAP_MS = 1500L

/** 监听期间没有变化时，多久兜底读一次。 */
private const val FALLBACK_RELOAD_MS = 30_000L

private enum class MainView { HISTORY, CHANGES, STASH, CONFLICT, SETTINGS }

/** 打开的仓库和已加载的那部分历史。不可变，每加载一页换一个新对象。 */
internal class RepoState(
    val repo: GitRepo,
    val result: LoadResult,
    val rows: List<GraphRow>,
    val headId: String?,
    val history: CommitPager? = null,
    // 排出 rows 的那个 GraphBuilder，下一页接着它往下排
    private val layout: GraphBuilder? = null,
) {
    val branchColors = BranchColors(result.refs)
    val indexById: Map<String, Int> = result.commits.withIndex().associate { (i, c) -> c.id to i }

    /** 再读 count 个提交。会阻塞，在 IO 线程调用。 */
    fun loadMore(count: Int): RepoState {
        val pager = checkNotNull(history)
        val next = pager.load(count)
        val from = next.appendedFrom
        // 前面的没动，而且 layout 正好排到这里，就只排新的；否则从头排
        if (layout != null && from > 0 && from == result.commits.size && layout.size == from) {
            return RepoState(repo, next, layout.add(next.commits.subList(from, next.commits.size)), headId, pager, layout)
        }
        return create(repo, next, headId, pager)
    }

    companion object {
        fun create(repo: GitRepo, result: LoadResult, headId: String?, history: CommitPager? = null): RepoState {
            val layout = GraphBuilder(BranchColors(result.refs))
            return RepoState(repo, result, layout.add(result.commits), headId, history, layout)
        }
    }
}

@Composable
fun FrameWindowScope.App(initialDir: File, reportInitialError: Boolean) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RepoState?>(null) }
    // 一启动就要打开上次的仓库，先当成正在加载，第一帧不要露出欢迎页的按钮
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CommitInfo?>(null) }
    var query by remember { mutableStateOf("") }
    var net by remember { mutableStateOf<NetStatus?>(null) }
    var view by remember { mutableStateOf(MainView.HISTORY) }
    var stashes by remember { mutableStateOf(emptyList<StashEntry>()) }
    var operation by remember { mutableStateOf<GitOperation?>(null) }
    var menu by remember { mutableStateOf<List<MenuEntry>?>(null) }
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) } // null：跟着鼠标弹出
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var working by remember { mutableStateOf(emptyList<WorkingFile>()) }
    val commitListState = rememberLazyListState()
    // 分页读历史和换仓库互斥，免得读到一半仓库被关掉
    val historyLock = remember { Mutex() }
    var loadingMore by remember { mutableStateOf(false) }
    // 正在加载、要找到的提交；找到后选中它
    var seeking by remember { mutableStateOf<Seek?>(null) }
    // 列表要滚到的提交。等列表按新数据重组后再滚，下标才对得上
    var scrollTarget by remember { mutableStateOf<String?>(null) }
    // 储藏页选中的储藏，侧边栏和图上的储藏都能把它带过去
    var stashSelected by remember { mutableStateOf<String?>(null) }
    // 侧边栏鼠标指着的分支，图上只亮它那条线
    var hoveredRef by remember { mutableStateOf<RefTarget?>(null) }

    /** minShowMs：至少转这么久再换成仓库，启动时用，免得加载画面一闪而过。 */
    fun open(dir: File, quiet: Boolean = false, minShowMs: Long = 0) {
        scope.launch {
            val started = System.currentTimeMillis()
            loading = true; error = null
            try {
                val before = state
                // 刷新时只读回看得到的部分：列表最底下那一行和选中的提交都要还在，滚到的位置才不变。
                // 再往下的等滚过去再读，不用把之前翻过的几万个提交一次读回来
                val lastVisible = commitListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
                val wanted = listOfNotNull(lastVisible, selected?.id)
                val needed = before?.let { st -> wanted.mapNotNull { st.indexById[it] }.maxOrNull()?.plus(1) } ?: 0
                val newState = withContext(Dispatchers.IO) {
                    val repo = GitRepo.open(dir)
                    try {
                        // 只看某个文件时继续只看它
                        val same = before?.takeIf { it.repo.repository.directory == repo.repository.directory }
                        val keep = if (same != null) needed else 0
                        val history = repo.openHistory(same?.history?.path)
                        var result = history.load(maxOf(GitRepo.PAGE_SIZE, keep))
                        // 新提交会把旧提交往后挤，按原来的条数可能正好读不到它们，就再读一页
                        if (same != null && result.truncated) {
                            val ids = result.commits.mapTo(HashSet()) { it.id }
                            if (wanted.any { it !in ids }) result = history.load(GitRepo.PAGE_SIZE)
                        }
                        RepoState.create(repo, result, repo.repository.resolve("HEAD")?.name, history)
                    } catch (e: Throwable) {
                        repo.close()
                        throw e
                    }
                }
                val waited = System.currentTimeMillis() - started
                if (waited < minShowMs) delay(minShowMs - waited)
                historyLock.withLock {
                    state?.repo?.close()
                    state = newState
                }
                newState.repo.workTree?.let { Settings.addRecentRepo(it.path) }
                // 刷新时保持选中；提交不在了或换了仓库才回到第一个
                val commits = newState.result.commits
                selected = commits.firstOrNull { it.id == selected?.id } ?: commits.firstOrNull()
            } catch (e: Exception) {
                if (!quiet) error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { open(initialDir, quiet = !reportInitialError, minShowMs = STARTUP_MIN_SHOW_MS) }
    DisposableEffect(Unit) { onDispose { state?.repo?.close() } }

    val chooseAndOpen = { chooseDirectory(t("Choose a git repository folder", "选择 git 仓库目录"))?.let { open(it) }; Unit }

    fun askClone() {
        dialog = DialogSpec(t("Clone repository", "克隆仓库"), t("Enter the repository URL. Next, choose where to save it.", "输入仓库地址，下一步选择保存到哪个文件夹。"), t("Next", "下一步"), input = t("Repository URL", "仓库地址")) { url, _ ->
            val parent = chooseDirectory(t("Choose where to save it", "选择保存到哪个文件夹")) ?: return@DialogSpec
            val name = repoNameFromUrl(url)
            scope.launch {
                net = NetStatus(t("Cloning", "正在克隆"), url, NetState.RUNNING)
                val result = withContext(Dispatchers.IO) { GitCli(parent).clone(url, name) }
                val outcome = describeAction(t("Cloning", "正在克隆"), t("Cloned $name", "已克隆 $name"), result)
                net = outcome
                if (result.ok) {
                    open(File(parent, name))
                    delay(2500)
                    if (net === outcome) net = null
                }
            }
        }
    }
    val s = state
    val workTree = s?.repo?.workTree
    val cli = remember(workTree) { workTree?.let(::GitCli) } // 刷新时不换对象，正在写的提交说明才不会丢
    val clipboard = LocalClipboardManager.current

    suspend fun reloadWorking() {
        val git = cli
        working = git?.let { withContext(Dispatchers.IO) { it.status() } }.orEmpty()
        stashes = git?.let { withContext(Dispatchers.IO) { it.stashList() } }.orEmpty()
        operation = git?.let { withContext(Dispatchers.IO) { it.currentOperation() } }
    }

    // 文件可能在别的编辑器里改，要重读工作区状态。大仓库里 git status 一次要几百毫秒 CPU，所以：
    // 监听文件变化，有变化才读；只在窗口有焦点时读，切回窗口时马上读一次；监听不了时退回每 3 秒读一次
    val changes = remember(cli) { Channel<Unit>(Channel.CONFLATED) }
    var watching by remember(cli) { mutableStateOf<Boolean?>(null) } // null：还在启动监听
    LaunchedEffect(cli) {
        val git = cli ?: return@LaunchedEffect
        var watcher: AutoCloseable? = null
        try {
            // Linux 上要逐个目录注册，可能要一会儿，放到后台。注册本身停不下来，
            // 这期间换了仓库也要拿到建好的监听器，下面 finally 里才关得掉
            watcher = withContext(Dispatchers.IO + NonCancellable) {
                runCatching { watchWorkTree(git.watchRoots, git.gitDirectories) { changes.trySend(Unit) } }.getOrNull()
            }
            watching = watcher != null
            awaitCancellation()
        } finally {
            watcher?.close()
        }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(cli, windowFocused, watching) {
        if (!windowFocused || cli == null) return@LaunchedEffect
        if (watching != true) {
            while (true) {
                reloadWorking()
                delay(POLL_INTERVAL_MS)
            }
        }
        while (true) {
            changes.tryReceive() // 失焦期间攒下的变化，下面这次读就包括了
            reloadWorking()
            // 编译、切分支时文件一直在变，两次读之间至少隔一会儿，期间的变化合并成下一次
            delay(MIN_RELOAD_GAP_MS)
            // 监听可能漏掉事件（比如网络盘），隔一阵子无论如何读一次
            withTimeoutOrNull(FALLBACK_RELOAD_MS) { changes.receive() }
        }
    }

    val conflicted = remember(working) { working.filter { it.conflicted } }
    val inConflict = conflicted.isNotEmpty() || operation != null

    // 出现冲突时直接带到解决页面，处理完回到历史
    LaunchedEffect(inConflict) {
        if (inConflict) view = MainView.CONFLICT else if (view == MainView.CONFLICT) view = MainView.HISTORY
    }

    fun runOp(running: NetStatus, finish: (GitResult) -> NetStatus, op: GitCli.() -> GitResult) {
        val git = cli ?: return
        val dir = workTree ?: return
        scope.launch {
            net = running
            val result = withContext(Dispatchers.IO) { git.op() }
            val outcome = finish(result)
            net = outcome
            open(dir)
            reloadWorking()
            if (result.ok) {
                delay(2500)
                if (net === outcome) net = null
            }
        }
    }

    fun runNet(op: NetOp) {
        val detail = if (op == NetOp.ABORT) "" else "origin"
        runOp(NetStatus(op.running, detail, NetState.RUNNING), { describe(op, it) }) {
            when (op) {
                NetOp.PUSH -> push()
                NetOp.PULL -> pull()
                NetOp.ABORT -> abortOperation()
            }
        }
    }

    val actions = GitActions(
        ask = { dialog = it },
        run = { running, done, op ->
            runOp(NetStatus(running, "", NetState.RUNNING), { describeAction(running, done, it) }, op)
        },
        copy = { clipboard.setText(AnnotatedString(it)) },
    )
    val currentBranch = s?.result?.refs?.firstOrNull { it.label.kind == RefKind.BRANCH && it.label.isCurrent }?.label?.name
    // 几万个提交过滤一次要一百多毫秒，放到后台算，打字时界面不卡
    var filtered by remember { mutableStateOf<Filtered?>(null) }
    LaunchedEffect(s, query) {
        val st = s ?: return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect
        val ids = withContext(Dispatchers.Default) { filterCommits(st.result.commits, query) }
        filtered = Filtered(st, query, ids)
    }
    val shown = remember(s, query, filtered) { s?.let { visibleCommits(it, query, filtered) }.orEmpty() }

    // 已加载的提交即时过滤；同时在后台搜全部历史，看没加载的部分里还有多少匹配
    var search by remember { mutableStateOf<SearchResult?>(null) }
    val searchRepo = s?.repo
    val pathFilter = s?.history?.path
    LaunchedEffect(searchRepo, query, pathFilter) {
        search = null
        val q = query.trim()
        if (searchRepo == null || q.isEmpty()) return@LaunchedEffect
        delay(300) // 打字时不要每个字都搜一遍
        val job = coroutineContext.job
        search = withContext(Dispatchers.IO) {
            runCatching { searchRepo.searchCommits(q, path = pathFilter, cancelled = { !job.isActive }) }.getOrNull()
        }
    }
    val unloadedMatches = remember(search, s) { s?.let { st -> search?.ids?.filter { it !in st.indexById } } }

    LaunchedEffect(scrollTarget, shown) {
        val id = scrollTarget ?: return@LaunchedEffect
        val index = s?.indexById?.get(id) ?: return@LaunchedEffect // 还没加载到，等下一页
        val position = shown.indexOf(index)
        // 搜索时新读到的提交要等后台过滤完才进列表，过滤结果换了 shown 会再进来
        if (position < 0 && query.isNotBlank() && (filtered?.state !== s || filtered?.query != query)) return@LaunchedEffect
        if (position >= 0) commitListState.animateScrollToItem((position - 3).coerceAtLeast(0))
        scrollTarget = null
    }

    /**
     * 读下一页。target 不为空时一直读到它出现或历史读完，返回它是否已加载。
     * 找提交时每次读的量翻倍，几十万个提交也只重排十几次。
     */
    suspend fun loadMore(target: String? = null): Boolean {
        while (true) {
            val current = state ?: return false
            if (target != null && target in current.indexById) return true
            if (!current.result.truncated || current.history == null) return false
            val count = if (target == null) GitRepo.PAGE_SIZE else maxOf(GitRepo.PAGE_SIZE, current.result.commits.size)
            loadingMore = true
            try {
                historyLock.withLock {
                    // 等锁的时候可能刷新了，换成了新仓库，回到循环开头重新看
                    if (state === current) state = withContext(Dispatchers.IO) { current.loadMore(count) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false // 仓库被关掉之类，下次刷新会重新读
            } finally {
                loadingMore = false
            }
            if (target == null) return true
        }
    }

    /** 主图换成只看 path 的历史；path 为空时回到全部历史。选中的提交还在就继续选中它。 */
    fun showHistoryOf(path: String?) {
        if (state?.history?.path == path) return
        seeking?.job?.cancel()
        scope.launch {
            loading = true
            try {
                historyLock.withLock {
                    val now = state ?: return@withLock
                    val next = withContext(Dispatchers.IO) {
                        val pager = now.repo.openHistory(path)
                        try {
                            val result = pager.load(GitRepo.PAGE_SIZE)
                            RepoState.create(now.repo, result, now.headId, pager)
                        } catch (e: Throwable) {
                            pager.close()
                            throw e
                        }
                    }
                    now.history?.close()
                    state = next
                    val commits = next.result.commits
                    selected = commits.firstOrNull { it.id == selected?.id } ?: commits.firstOrNull()
                    scrollTarget = selected?.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                net = NetStatus(t("Couldn't read file history", "读取文件历史失败"), e.message ?: e.toString(), NetState.FAILED)
            } finally {
                loading = false
            }
        }
    }

    /** 选中并滚到 id；还没加载时先往下读，select 为 false 时只加载不选中。 */
    fun jumpTo(id: String, select: Boolean = true) {
        val current = s ?: return
        current.indexById[id]?.let { index ->
            if (select) { selected = current.result.commits[index]; scrollTarget = id }
            return
        }
        seeking?.job?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            try {
                val found = loadMore(target = id)
                val now = state
                if (!found || now == null) {
                    val path = now?.history?.path
                    val why = if (path != null) t("Showing only the history of $path, and this commit doesn't touch it", "当前只看 $path 的历史，这个提交没有改动它") else t("It isn't in the history of any branch, remote branch or tag", "它不在任何分支、远程分支或标签的历史里")
                    net = NetStatus(t("Can't find commit ${id.take(7)}", "找不到提交 ${id.take(7)}"), why, NetState.FAILED)
                } else if (select) {
                    now.indexById[id]?.let { selected = now.result.commits[it] }
                    scrollTarget = id
                }
            } finally {
                if (seeking?.job === me) seeking = null
            }
        }
        seeking = Seek(id, job)
        job.start()
    }

    Row(Modifier.fillMaxSize()) {
        if (s != null) {
            // 侧边栏从窗口顶部铺下来，顶上一段留给红绿灯
            Column(Modifier.width(248.dp).fillMaxHeight().background(Palette.Rail)) {
                if (isMac) WindowDraggableArea { Spacer(Modifier.fillMaxWidth().height(TitleBarHeight)) }
                Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (inConflict) {
                        WorkspaceItem(t("Resolve conflicts", "解决冲突"), Icons.Branch, view == MainView.CONFLICT, conflicted.size) { view = MainView.CONFLICT }
                    }
                    WorkspaceItem(t("Changes", "本地改动"), Icons.Edit, view == MainView.CHANGES, working.size - conflicted.size) { view = MainView.CHANGES }
                    WorkspaceItem(t("All commits", "所有提交"), Icons.History, view == MainView.HISTORY, 0) { view = MainView.HISTORY }
                    WorkspaceItem(t("Stashes", "储藏"), Icons.Stash, view == MainView.STASH, stashes.size) { view = MainView.STASH }
                }
                HorizontalDivider()
                RefSidebar(
                    s.result.refs, query, selected?.id,
                    onSelect = { ref ->
                        view = MainView.HISTORY
                        jumpTo(ref.commitId)
                    },
                    onBranchOpen = { ref ->
                        if (cli != null && !ref.label.isCurrent) {
                            actions.run(t("Switching", "正在切换"), t("Switched to ${ref.label.name}", "已切换到 ${ref.label.name}")) { switchTo(ref.label.name) }
                        }
                    },
                    onBranchMenu = { ref ->
                        if (cli != null) {
                            menuAnchor = null
                            menu = branchMenu(ref.label.name, ref.label.isCurrent, currentBranch, actions)
                        }
                    },
                    branchColors = s.branchColors,
                    onHover = { hoveredRef = it },
                    stashes = stashes,
                    onStash = { entry ->
                        view = MainView.HISTORY
                        jumpTo(entry.id)
                    },
                    onStashOpen = { entry ->
                        stashSelected = entry.id
                        view = MainView.STASH
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(if (Palette.isDark) Icons.Sun else Icons.Moon, onClick = Palette::toggle)
                    IconButton(Icons.Settings) { view = MainView.SETTINGS }
                }
            }
            VerticalDivider()
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Ground)) {
            WindowDraggableArea {
                // 没有侧边栏时红绿灯压在工具栏上，要让开
                val start = if (isMac && s == null) 84.dp else 18.dp
                Toolbar(
                    s, loading, start, query, onQuery = { query = it },
                    onOpen = { at ->
                        menuAnchor = at
                        menu = repoMenu(workTree?.path, Settings.recentRepos, { open(File(it)) }, chooseAndOpen, ::askClone)
                    },
                    onRefresh = { s?.repo?.workTree?.let { open(it) } ?: s?.repo?.repository?.directory?.let { open(it) } },
                    netBusy = net?.state == NetState.RUNNING,
                    onPull = { runNet(NetOp.PULL) },
                    onPush = { runNet(NetOp.PUSH) },
                    onNewBranch = { selected?.let { askNewBranch(it, actions) } },
                    canStash = working.isNotEmpty(),
                    onStash = {
                        askStash(working.size, actions)
                        view = MainView.STASH
                    },
                )
            }
            HorizontalDivider()
            net?.let {
                NetBanner(it, onAction = ::runNet, onDismiss = { net = null })
                HorizontalDivider()
            }
            error?.let {
                Text(t("Couldn't open: $it", "打开失败：$it"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            if (s == null) {
                Welcome(loading, Settings.recentRepos, onRecent = { open(File(it)) }, onOpen = chooseAndOpen, onClone = ::askClone)
            } else {
                if (view == MainView.SETTINGS) {
                    SettingsView(cli, Modifier.fillMaxWidth().weight(1f))
                } else if (view == MainView.CONFLICT && cli != null) {
                    ConflictView(
                        cli, conflicted, operation,
                        onAbort = { runNet(NetOp.ABORT) },
                        onFinish = { actions.run(t("Finishing", "正在完成"), t("Finished", "已完成")) { continueOperation() } },
                        onChanged = { scope.launch { reloadWorking() } },
                        onFailure = { title, detail -> net = NetStatus(title, detail, NetState.FAILED) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else if (view == MainView.STASH && cli != null) {
                    StashView(cli, stashes, actions, Modifier.fillMaxWidth().weight(1f), stashSelected, onSelect = { stashSelected = it })
                } else if (view == MainView.CHANGES && cli != null) {
                    ChangesView(
                        cli, working.filterNot { it.conflicted }, s.result.currentBranch,
                        onChanged = { historyChanged ->
                            scope.launch { reloadWorking() }
                            if (historyChanged) s.repo.workTree?.let { open(it) }
                        },
                        onFailure = { title, detail -> net = NetStatus(title, detail, NetState.FAILED) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    val searching = query.isNotBlank()
                    val footer: String? = when {
                        seeking != null -> t("Loading earlier commits…", "正在加载更早的提交…")
                        searching && s.result.truncated && search == null -> t("Searching all history…", "正在搜索全部历史…")
                        searching && !unloadedMatches.isNullOrEmpty() -> {
                            val more = if (search?.complete == false) "+" else ""
                            t("${unloadedMatches.size}$more more matches in earlier history. Click to load.", "更早的历史里还有 ${unloadedMatches.size}$more 个匹配，点击加载")
                        }
                        loadingMore -> t("Loading earlier commits…", "正在加载更早的提交…")
                        else -> null
                    }
                    if (pathFilter != null) {
                        PathFilterBar(pathFilter, s.result, onClear = { showHistoryOf(null) })
                        HorizontalDivider()
                    }
                    CommitList(
                        s, shown, selected, commitListState, onSelect = { selected = it },
                        Modifier.fillMaxWidth().weight(0.46f),
                        sidebarHighlight = hoveredRef?.let { s.indexById[it.commitId] }?.let { s.rows[it].nodeTrack.line },
                        onMenu = { c ->
                            selected = c
                            // 储藏不能 checkout、cherry-pick，右键直接去储藏页应用或删除
                            if (c.isStash) {
                                stashSelected = c.id
                                view = MainView.STASH
                            } else if (cli != null) {
                                menuAnchor = null
                                menu = commitMenu(c, c.id == s.headId, currentBranch, actions)
                            }
                        },
                        // 搜索时不自动往下读，免得为了凑结果把整个历史读进来
                        onNearEnd = {
                            if (!searching && !loadingMore && seeking == null && s.result.truncated) scope.launch { loadMore() }
                        },
                        footer = footer,
                        onFooter = unloadedMatches?.lastOrNull()?.takeIf { searching && seeking == null }?.let { oldest ->
                            { jumpTo(oldest, select = false) }
                        },
                    )
                    HorizontalDivider()
                    CommitDetail(
                        s.repo, selected,
                        onOpenCommit = { id -> jumpTo(id) },
                        modifier = Modifier.fillMaxWidth().weight(0.54f),
                        onShowFileHistory = { path -> showHistoryOf(path) },
                    )
                }
                HorizontalDivider()
                StatusBar(s, loadingMore || seeking != null, seeking?.let { { it.job.cancel() } })
            }
        }
    }
    menu?.let { GitMenuPopup(it, onDismiss = { menu = null }, anchor = menuAnchor) }
    dialog?.let { ActionDialog(it, onDismiss = { dialog = null }) }
}

/** 后台算好的一次过滤结果。indices 是 state 里的下标，换了 state 就不能直接用。 */
internal class Filtered(val state: RepoState, val query: String, val indices: List<Int>)

/**
 * 列表里显示哪些提交。新结果还没算好时先用上一次的：
 * 同一个 state 直接用（只是慢一拍）；state 换了（翻页、刷新），按 id 换算到新列表的下标。
 * 一次都还没算过时先显示全部。
 */
internal fun visibleCommits(state: RepoState, query: String, filtered: Filtered?): List<Int> = when {
    query.isBlank() || filtered == null -> state.result.commits.indices.toList()
    filtered.state === state -> filtered.indices
    else -> filtered.indices.mapNotNull { state.indexById[filtered.state.result.commits[it].id] }.sorted()
}

/** 搜索说明、作者、哈希和引用名，返回匹配提交在完整列表里的下标。 */
internal fun filterCommits(commits: List<CommitInfo>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return commits.indices.toList()
    return commits.indices.filter { i ->
        val c = commits[i]
        matchesQuery(q, c.shortMessage, c.author, c.id, c.refs.map { it.name })
    }
}

/** 主图只看一个文件时，列表上方的说明和“显示全部”。 */
@Composable
private fun PathFilterBar(path: String, result: LoadResult, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(Palette.Accent.copy(alpha = 0.08f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t("Only", "只看"), fontSize = TypeScale.Small, color = Palette.Muted)
        Text(path, fontSize = TypeScale.Small, fontFamily = CodeFont, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        val count = result.commits.size
        Text(if (result.truncated) t("Loaded ${countOf(count, "commit")} that touch it", "已加载 $count 个改动它的提交") else t("${countOf(count, "commit")} touch it", "$count 个改动它的提交"), fontSize = TypeScale.Small,
            color = Palette.Muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(t("Show all commits", "显示全部提交"), fontSize = TypeScale.Small, color = Palette.Link, modifier = Modifier.clickable(onClick = onClear))
    }
}

/** 正在加载、要找到的提交。 */
private class Seek(val id: String, val job: Job)

internal val TitleBarHeight = 52.dp

@Composable
private fun WorkspaceItem(label: String, icon: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(5.dp))
            .background(if (selected) Palette.Raised else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, if (selected) Palette.AccentInk else Palette.Muted, size = 15.dp)
        Text(label, fontSize = TypeScale.Body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        if (badge > 0) {
            Text(
                "$badge", fontSize = TypeScale.Caption, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent,
                modifier = Modifier.background(Palette.Accent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun Welcome(loading: Boolean, recent: List<String>, onRecent: (String) -> Unit, onOpen: () -> Unit, onClone: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GitVizMark(size = 40.dp)
                        Text("GitViz", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(t("Choose a local git repository to see its branches and commits.", "选择一个本地 git 仓库，查看它的分支和提交。"), fontSize = TypeScale.Body, color = Palette.Muted)
                }
                val shape = RoundedCornerShape(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        t("Open repository…", "打开仓库…"), fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent,
                        modifier = Modifier.clip(shape).background(Palette.Accent, shape).clickable(onClick = onOpen)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        t("Clone repository", "克隆仓库"), fontSize = TypeScale.Body,
                        modifier = Modifier.clip(shape).border(1.dp, Palette.Line, shape).clickable(onClick = onClone)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (recent.isNotEmpty()) {
                    Column {
                        HorizontalDivider()
                        Text(t("Recent", "最近打开"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(vertical = 8.dp))
                        for (path in recent) {
                            Row(
                                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(5.dp)).hoverRow()
                                    .clickable { onRecent(path) }.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(File(path).name, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Faint, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Toolbar(
    state: RepoState?,
    loading: Boolean,
    start: Dp,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (IntOffset) -> Unit,
    onRefresh: () -> Unit,
    netBusy: Boolean,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onNewBranch: () -> Unit,
    canStash: Boolean,
    onStash: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(TitleBarHeight).padding(start = start, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 仓库菜单从标题正下方弹出，左边和标题对齐，不盖住标题
        var titleBounds by remember { mutableStateOf<Rect?>(null) }
        val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
        Row(
            Modifier.onGloballyPositioned { titleBounds = it.boundsInWindow() }
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = !loading) {
                    titleBounds?.let { onOpen(IntOffset(it.left.toInt(), it.bottom.toInt() + gap)) }
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state?.repo?.displayName ?: t("Open repository", "打开仓库"), fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Title, maxLines = 1)
            Icon(Icons.ChevronDown, Palette.Muted, size = 10.dp, strokeWidth = 3f)
        }
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (state != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolButton(t("Refresh", "刷新"), Icons.Refresh, enabled = !loading, onClick = onRefresh)
                val canSync = !loading && !netBusy && state.repo.workTree != null
                ToolButton(t("Pull", "拉取"), Icons.Pull, enabled = canSync, onClick = onPull)
                ToolButton(t("Push", "推送"), Icons.Push, enabled = canSync, onClick = onPush)
                ToolButton(t("New branch", "新建分支"), Icons.Branch, enabled = canSync, onClick = onNewBranch)
                ToolButton(t("Stash", "储藏"), Icons.Stash, enabled = canSync && canStash, onClick = onStash)
            }
            SearchField(query, onQuery)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body, fontFamily = UiFont),
        cursorBrush = SolidColor(Palette.Accent),
        interactionSource = interaction,
        decorationBox = { field ->
            Row(
                Modifier.width(240.dp).height(28.dp).background(Palette.Rail, shape)
                    .border(1.dp, if (focused) Palette.Accent else Palette.Line, shape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Search, Palette.Faint, size = 14.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(t("Search commits, branches, tags", "搜索提交、分支、标签"), fontSize = TypeScale.Body, color = Palette.Faint, maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
internal fun StatusBar(state: RepoState, loading: Boolean = false, onCancel: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(Palette.Rail).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val path = state.repo.workTree?.path ?: state.repo.repository.directory.path
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(Palette.Added)
            Text(path, fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val count = state.result.commits.size
        Text(
            when {
                loading -> t("Loaded ${countOf(count, "commit")} · loading earlier…", "已加载 $count 个提交 · 正在加载更早的…")
                state.result.truncated -> t("Loaded ${countOf(count, "commit")} · scroll to load more", "已加载 $count 个提交 · 往下滚继续加载")
                else -> t(countOf(count, "commit"), "$count 个提交")
            },
            fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1,
        )
        if (onCancel != null) {
            Text(t("Cancel", "取消"), fontSize = TypeScale.Caption, color = Palette.Link, modifier = Modifier.clickable(onClick = onCancel))
        }
    }
}

private fun chooseDirectory(title: String): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
