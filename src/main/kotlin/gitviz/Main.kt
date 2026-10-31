package gitviz

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

fun main(args: Array<String>) {
    val initial = args.firstOrNull()?.let(::File) ?: File(System.getProperty("user.dir"))
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val size = DpSize(1360.dp, 860.dp)
    val root: @Composable FrameWindowScope.(Boolean) -> Unit = { glass ->
        GitVizTheme {
            // 毛玻璃装不上时根背景兜底，窗口不至于真的透明
            Surface(
                Modifier.fillMaxSize().clip(RoundedCornerShape(if (isMac) 16.dp else 0.dp)),
                color = if (glass) Color.Transparent else Palette.Ground,
                contentColor = Palette.Ink,
            ) {
                App(initial, reportInitialError = args.isNotEmpty(), glass)
            }
        }
    }
    application {
        if (isMac) {
            GlassWindow("GitViz", size, onClose = ::exitApplication, content = root)
        } else {
            Window(onCloseRequest = ::exitApplication, title = "GitViz", state = rememberWindowState(size = size)) {
                root(false)
            }
        }
    }
}

private class RepoState(
    val repo: GitRepo,
    val result: LoadResult,
    val rows: List<GraphRow>,
    val headId: String?,
) {
    val indexById: Map<String, Int> = result.commits.withIndex().associate { (i, c) -> c.id to i }
}

@Composable
fun FrameWindowScope.App(initialDir: File, reportInitialError: Boolean, glass: Boolean) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RepoState?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CommitInfo?>(null) }
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
    Row(Modifier.fillMaxSize()) {
        if (s != null) {
            // 侧边栏从窗口顶部铺下来，透出毛玻璃；顶上一段留给红绿灯
            Column(
                Modifier.width(240.dp).fillMaxHeight()
                    .background(if (glass) Palette.Rail.copy(alpha = 0.5f) else Palette.Rail),
            ) {
                if (isMac) WindowDraggableArea { Spacer(Modifier.fillMaxWidth().height(TitleBarHeight)) }
                RefSidebar(
                    s.result.refs, selected?.id,
                    isLoaded = { it in s.indexById },
                    onSelect = { ref ->
                        s.indexById[ref.commitId]?.let { index ->
                            selected = s.result.commits[index]
                            scope.launch { commitListState.animateScrollToItem((index - 3).coerceAtLeast(0)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            VerticalDivider()
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Ground)) {
            WindowDraggableArea {
                // 没有侧边栏时红绿灯压在工具栏上，要让开
                val start = if (isMac && s == null) 84.dp else 16.dp
                Toolbar(state, loading, start, onOpen = chooseAndOpen, onRefresh = {
                    state?.repo?.workTree?.let { open(it) } ?: state?.repo?.repository?.directory?.let { open(it) }
                })
            }
            HorizontalDivider()
            error?.let {
                Text("打开失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator()
                    else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("选择一个本地 git 仓库，查看它的分支和提交", fontSize = TypeScale.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ActionChip("打开仓库", enabled = true, primary = true, onClick = chooseAndOpen)
                    }
                }
            } else {
                CommitList(s, selected, commitListState, onSelect = { selected = it }, Modifier.fillMaxWidth().weight(0.52f))
                HorizontalDivider()
                CommitDetail(s.repo, selected, Modifier.fillMaxWidth().weight(0.48f))
            }
        }
    }
}

private val TitleBarHeight = 52.dp

@Composable
private fun Toolbar(state: RepoState?, loading: Boolean, start: Dp, onOpen: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(TitleBarHeight).padding(start = start, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state != null) {
            Text(state.repo.displayName, fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Title)
            state.result.currentBranch?.let { branch ->
                val headLane = state.indexById[state.headId]?.let { state.rows[it].node } ?: 0
                RouteBadge(branch, fill = laneColor(headLane), text = Palette.Rail, bold = true)
            }
            val count = state.result.commits.size
            Text(
                if (state.result.truncated) "显示最近 $count 个提交" else "$count 个提交",
                fontSize = TypeScale.Small, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        ActionChip("刷新", enabled = state != null && !loading, onClick = onRefresh)
        ActionChip("打开仓库", enabled = !loading, primary = true, onClick = onOpen)
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private fun formatTime(seconds: Long) = dateFormat.format(Instant.ofEpochSecond(seconds))

@Composable
private fun CommitList(
    state: RepoState,
    selected: CommitInfo?,
    listState: LazyListState,
    onSelect: (CommitInfo) -> Unit,
    modifier: Modifier,
) {
    val lanesShown = remember(state) {
        (state.rows.maxOfOrNull { it.width } ?: 1).coerceIn(1, MAX_VISIBLE_LANES)
    }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
            itemsIndexed(state.result.commits, key = { _, c -> c.id }) { index, c ->
                val isSelected = c.id == selected?.id
                val row = state.rows[index]
                Row(
                    Modifier.fillMaxWidth().height(30.dp)
                        .rowHighlight(isSelected)
                        .clickable { onSelect(c) }
                        .padding(start = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GraphCell(row, lanesShown, c.id == state.headId)
                    Spacer(Modifier.width(8.dp))
                    c.refs.forEach { RefChip(it, laneColor(row.node)) }
                    Text(
                        c.shortMessage, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = TypeScale.Body, modifier = Modifier.weight(1f),
                        fontWeight = if (c.id == state.headId) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(c.author, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TypeScale.Small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp).padding(start = 8.dp))
                    Text(formatTime(c.timeSeconds), maxLines = 1, softWrap = false, fontSize = TypeScale.Small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(120.dp).padding(start = 8.dp))
                    Text(c.shortId, fontSize = TypeScale.Small, fontFamily = CodeFont,
                        color = Palette.Faint, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** 线路牌：颜色跟随所在线路，实心、浅底、描边区分引用类型。 */
@Composable
private fun RefChip(ref: RefLabel, lane: Color) {
    val modifier = Modifier.padding(end = 6.dp)
    when {
        ref.kind == RefKind.HEAD -> RouteBadge(ref.name, Palette.Ink, Palette.Rail, modifier, bold = true)
        ref.kind == RefKind.TAG -> RouteBadge(ref.name, Color.Transparent, Palette.Muted, modifier, outline = Palette.Faint)
        ref.kind == RefKind.REMOTE -> RouteBadge(ref.name, Color.Transparent, lane, modifier, outline = lane)
        ref.isCurrent -> RouteBadge(ref.name, lane, Palette.Rail, modifier, bold = true)
        else -> RouteBadge(ref.name, lane.copy(alpha = 0.2f), lane, modifier)
    }
}

@Composable
private fun RouteBadge(
    label: String,
    fill: Color,
    text: Color,
    modifier: Modifier = Modifier,
    outline: Color? = null,
    bold: Boolean = false,
) {
    val shape = RoundedCornerShape(3.dp)
    Text(
        label, fontSize = TypeScale.Caption, color = text, maxLines = 1, softWrap = false,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        modifier = modifier.background(fill, shape)
            .then(if (outline != null) Modifier.border(1.dp, outline, shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun CommitDetail(repo: GitRepo, commit: CommitInfo?, modifier: Modifier) {
    if (commit == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("选择一个提交") }
        return
    }
    // 刷新后 repo 是新对象，旧的 FileChange 不能再用，所以 repo 也要作为 key
    var changes by remember(repo, commit.id) { mutableStateOf<Result<List<FileChange>>?>(null) }
    var selectedFile by remember(repo, commit.id) { mutableStateOf<FileChange?>(null) }
    var diff by remember(repo, commit.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(repo, commit.id) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.changes(commit.id) } }
        changes = result
        selectedFile = result.getOrNull()?.firstOrNull()
    }
    LaunchedEffect(selectedFile) {
        val f = selectedFile ?: return@LaunchedEffect
        diff = null
        diff = withContext(Dispatchers.IO) { runCatching { repo.diffText(f) }.getOrElse { "读取 diff 失败：${it.message}" } }
    }

    Row(modifier) {
        Column(Modifier.weight(0.38f).fillMaxHeight()) {
            CommitHeader(commit)
            CommitActions(commit)
            HorizontalDivider()
            ChangedFiles(changes, selectedFile, onSelect = { selectedFile = it }, Modifier.fillMaxWidth().weight(1f))
        }
        VerticalDivider()
        // 改动已读完却没有选中文件，说明没有可显示的 diff，不要一直转圈
        if (changes != null && selectedFile == null) Spacer(Modifier.weight(0.62f))
        else DiffView(diff, Modifier.weight(0.62f).fillMaxHeight())
    }
}

@Composable
private fun CommitHeader(commit: CommitInfo) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // 去掉段落间的空行，不然正文只有三行的额度会被空行占掉
    val body = commit.fullMessage.trim().substringAfter("\n\n", "").trim().replace(Regex("\n\\s*\n"), "\n")
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Text(commit.shortMessage, fontSize = TypeScale.Heading, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = TypeScale.Body, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text("${commit.author} <${commit.email}>", fontSize = TypeScale.Small, color = muted)
            Text(formatTime(commit.timeSeconds), fontSize = TypeScale.Small, color = muted)
            Text(commit.id, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Faint)
            if (commit.parents.size > 1) {
                Text("合并提交，父提交：${commit.parents.joinToString { it.take(8) }}（diff 相对第一个父提交）",
                    fontSize = TypeScale.Small, color = muted)
            }
        }
    }
}

/** 目前只有复制哈希可用，写操作先占位。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommitActions(commit: CommitInfo) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(commit.id) { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActionChip(if (copied) "已复制" else "复制哈希", enabled = true) {
            clipboard.setText(AnnotatedString(commit.id))
            copied = true
        }
        for (label in listOf("检出", "新建分支", "打标签", "Cherry-pick", "回滚")) {
            ActionChip(label, enabled = false) {}
        }
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Text(
        label, fontSize = TypeScale.Small, maxLines = 1,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        color = when {
            !enabled -> Palette.Faint
            primary -> colors.onPrimary
            else -> colors.onSurface
        },
        modifier = Modifier.clip(shape)
            .background(if (primary && enabled) colors.primary else Color.Transparent)
            .then(if (primary && enabled) Modifier else Modifier.border(1.dp, colors.outlineVariant, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ChangedFiles(
    changes: Result<List<FileChange>>?,
    selected: FileChange?,
    onSelect: (FileChange) -> Unit,
    modifier: Modifier,
) {
    val list = changes?.getOrNull()
    val failure = changes?.exceptionOrNull()
    LazyColumn(modifier) {
        if (failure != null) item {
            Text("读取改动失败：${failure.message ?: failure}", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp))
        }
        else if (list == null) item { Text("加载中…", Modifier.padding(14.dp), fontSize = TypeScale.Body) }
        else if (list.isEmpty()) item { Text("没有文件改动", Modifier.padding(14.dp), fontSize = TypeScale.Body) }
        else itemsIndexed(list) { _, f ->
            Row(
                Modifier.fillMaxWidth()
                    .rowHighlight(f == selected)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (tag, color) = changeBadge(f.type)
                Text(tag, color = color, fontFamily = CodeFont, fontWeight = FontWeight.Bold,
                    fontSize = TypeScale.Small, modifier = Modifier.width(18.dp))
                // 这一栏比较窄，文件名放前面，不然只能看到被截断的目录
                val renamed = f.type == DiffEntry.ChangeType.RENAME || f.type == DiffEntry.ChangeType.COPY
                val name = if (renamed) f.displayPath else f.displayPath.substringAfterLast('/')
                val dir = if (renamed) "" else f.displayPath.substringBeforeLast('/', "")
                Text(name, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (dir.isNotEmpty()) {
                    Text(dir, fontSize = TypeScale.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = Palette.Faint,
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
                }
            }
        }
    }
}

private fun changeBadge(type: DiffEntry.ChangeType): Pair<String, Color> = when (type) {
    DiffEntry.ChangeType.ADD -> "A" to Palette.Added
    DiffEntry.ChangeType.DELETE -> "D" to Palette.Removed
    DiffEntry.ChangeType.MODIFY -> "M" to Palette.Modified
    DiffEntry.ChangeType.RENAME -> "R" to Palette.Renamed
    DiffEntry.ChangeType.COPY -> "C" to Palette.Copied
}

@Composable
private fun DiffView(diff: String?, modifier: Modifier) {
    if (diff == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        return
    }
    val lines = remember(diff) { parseDiffLines(diff) }
    val listState = rememberLazyListState(0)
    LaunchedEffect(diff) { listState.scrollToItem(0) }
    Box(modifier.background(Palette.Rail)) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(lines) { _, line ->
                val (fg, bg) = when (line.kind) {
                    DiffLineKind.HEADER -> Palette.Faint to Color.Transparent
                    DiffLineKind.HUNK -> Palette.Renamed to Palette.Renamed.copy(alpha = 0.1f)
                    DiffLineKind.ADDED -> Palette.Added to Palette.Added.copy(alpha = 0.1f)
                    DiffLineKind.REMOVED -> Palette.Removed to Palette.Removed.copy(alpha = 0.12f)
                    DiffLineKind.CONTEXT -> Palette.Muted to Color.Transparent
                }
                Text(
                    line.text.ifEmpty { " " }, color = fg, fontFamily = CodeFont, fontSize = TypeScale.Small,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp),
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun chooseDirectory(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择 git 仓库目录"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
