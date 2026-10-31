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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

fun main(args: Array<String>) {
    val initial = args.firstOrNull()?.let(::File) ?: File(System.getProperty("user.dir"))
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
    application {
        if (isMac) {
            GlassWindow("GitViz", size, onClose = ::exitApplication) { root() }
        } else {
            Window(onCloseRequest = ::exitApplication, title = "GitViz", state = rememberWindowState(size = size)) {
                root()
            }
        }
    }
}

internal class RepoState(
    val repo: GitRepo,
    val result: LoadResult,
    val rows: List<GraphRow>,
    val headId: String?,
) {
    val indexById: Map<String, Int> = result.commits.withIndex().associate { (i, c) -> c.id to i }
}

@Composable
fun FrameWindowScope.App(initialDir: File, reportInitialError: Boolean) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RepoState?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CommitInfo?>(null) }
    var query by remember { mutableStateOf("") }
    var net by remember { mutableStateOf<NetStatus?>(null) }
    var showChanges by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(emptyList<WorkingFile>()) }
    val commitListState = rememberLazyListState()

    fun open(dir: File, quiet: Boolean = false) {
        scope.launch {
            loading = true; error = null
            try {
                val newState = withContext(Dispatchers.IO) {
                    val repo = GitRepo.open(dir)
                    try {
                        val result = repo.loadCommits()
                        RepoState(repo, result, GraphLayout.compute(result.commits), repo.repository.resolve("HEAD")?.name)
                    } catch (e: Throwable) {
                        repo.close()
                        throw e
                    }
                }
                state?.repo?.close()
                state = newState
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

    LaunchedEffect(Unit) { open(initialDir, quiet = !reportInitialError) }
    DisposableEffect(Unit) { onDispose { state?.repo?.close() } }

    val chooseAndOpen = { chooseDirectory()?.let { open(it) }; Unit }
    val s = state
    val cli = remember(s?.repo) { s?.repo?.workTree?.let(::GitCli) }

    suspend fun reloadWorking() {
        working = cli?.let { withContext(Dispatchers.IO) { it.status() } }.orEmpty()
    }

    // 文件可能在别的编辑器里改，定时读一次工作区状态
    LaunchedEffect(cli) {
        while (true) {
            reloadWorking()
            delay(3000)
        }
    }

    fun runNet(op: NetOp) {
        val workTree = s?.repo?.workTree ?: return
        scope.launch {
            net = NetStatus(op.running, "origin", NetState.RUNNING)
            val cli = GitCli(workTree)
            val result = withContext(Dispatchers.IO) { if (op == NetOp.PUSH) cli.push() else cli.pull() }
            net = describe(op, result)
            open(workTree)
            if (result.ok) {
                delay(2500)
                net = null
            }
        }
    }
    val shown = remember(s, query) { s?.let { filterCommits(it.result.commits, query) }.orEmpty() }

    fun selectCommit(index: Int) {
        val current = s ?: return
        selected = current.result.commits[index]
        val position = shown.indexOf(index)
        if (position >= 0) scope.launch { commitListState.animateScrollToItem((position - 3).coerceAtLeast(0)) }
    }

    Row(Modifier.fillMaxSize()) {
        if (s != null) {
            // 侧边栏从窗口顶部铺下来，顶上一段留给红绿灯
            Column(Modifier.width(248.dp).fillMaxHeight().background(Palette.Rail)) {
                if (isMac) WindowDraggableArea { Spacer(Modifier.fillMaxWidth().height(TitleBarHeight)) }
                Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    WorkspaceItem("本地改动", Icons.Edit, showChanges, working.size) { showChanges = true }
                    WorkspaceItem("所有提交", Icons.History, !showChanges, 0) { showChanges = false }
                }
                HorizontalDivider()
                RefSidebar(
                    s.result.refs, query, selected?.id,
                    isLoaded = { it in s.indexById },
                    onSelect = { ref ->
                        showChanges = false
                        s.indexById[ref.commitId]?.let(::selectCommit)
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    IconButton(if (Palette.isDark) Icons.Sun else Icons.Moon, onClick = Palette::toggle)
                }
            }
            VerticalDivider()
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Ground)) {
            WindowDraggableArea {
                // 没有侧边栏时红绿灯压在工具栏上，要让开
                val start = if (isMac && s == null) 84.dp else 18.dp
                Toolbar(
                    s, loading, start, query, onQuery = { query = it }, onOpen = chooseAndOpen,
                    onRefresh = { s?.repo?.workTree?.let { open(it) } ?: s?.repo?.repository?.directory?.let { open(it) } },
                    netBusy = net?.state == NetState.RUNNING,
                    onPull = { runNet(NetOp.PULL) },
                    onPush = { runNet(NetOp.PUSH) },
                )
            }
            HorizontalDivider()
            net?.let {
                NetBanner(it, onAction = ::runNet, onDismiss = { net = null })
                HorizontalDivider()
            }
            error?.let {
                Text("打开失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            if (s == null) {
                Welcome(loading, chooseAndOpen)
            } else {
                if (showChanges && cli != null) {
                    ChangesView(
                        cli, working, s.result.currentBranch,
                        onChanged = { historyChanged ->
                            scope.launch { reloadWorking() }
                            if (historyChanged) s.repo.workTree?.let { open(it) }
                        },
                        onFailure = { title, detail -> net = NetStatus(title, detail, NetState.FAILED) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    CommitList(s, shown, selected, commitListState, onSelect = { selected = it }, Modifier.fillMaxWidth().weight(0.46f))
                    HorizontalDivider()
                    CommitDetail(
                        s.repo, selected,
                        onOpenCommit = { id -> s.indexById[id]?.let(::selectCommit) },
                        Modifier.fillMaxWidth().weight(0.54f),
                    )
                }
                HorizontalDivider()
                StatusBar(s)
            }
        }
    }
}

/** 搜索说明、作者、哈希和引用名，返回匹配提交在完整列表里的下标。 */
internal fun filterCommits(commits: List<CommitInfo>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return commits.indices.toList()
    return commits.indices.filter { i ->
        val c = commits[i]
        c.shortMessage.contains(q, ignoreCase = true) || c.author.contains(q, ignoreCase = true) ||
            c.id.startsWith(q, ignoreCase = true) || c.refs.any { it.name.contains(q, ignoreCase = true) }
    }
}

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
private fun Welcome(loading: Boolean, onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择一个本地 git 仓库，查看它的分支和提交", fontSize = TypeScale.Body, color = Palette.Muted)
                val shape = RoundedCornerShape(6.dp)
                Text(
                    "打开仓库", fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, color = Palette.OnAccent,
                    modifier = Modifier.clip(shape).background(Palette.Accent, shape).clickable(onClick = onOpen)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    netBusy: Boolean,
    onPull: () -> Unit,
    onPush: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(TitleBarHeight).padding(start = start, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp)).clickable(enabled = !loading, onClick = onOpen).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state?.repo?.displayName ?: "打开仓库", fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Title, maxLines = 1)
            Icon(Icons.ChevronDown, Palette.Muted, size = 10.dp, strokeWidth = 3f)
        }
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (state != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolButton("刷新", Icons.Refresh, enabled = !loading, onClick = onRefresh)
                val canSync = !loading && !netBusy && state.repo.workTree != null
                ToolButton("拉取", Icons.Pull, enabled = canSync, onClick = onPull)
                ToolButton("推送", Icons.Push, enabled = canSync, onClick = onPush)
                // 其余写操作还没接上
                ToolButton("新建分支", Icons.Branch, enabled = false) {}
                ToolButton("储藏", Icons.Stash, enabled = false) {}
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
                    if (value.isEmpty()) Text("搜索提交、分支、标签", fontSize = TypeScale.Body, color = Palette.Faint, maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
internal fun StatusBar(state: RepoState) {
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
            if (state.result.truncated) "显示最近 $count 个提交" else "$count 个提交",
            fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1,
        )
    }
}

private fun chooseDirectory(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择 git 仓库目录"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
