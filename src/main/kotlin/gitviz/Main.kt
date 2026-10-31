package gitviz

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "GitViz",
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(initial, reportInitialError = args.isNotEmpty()) }
            }
        }
    }
}

private class RepoState(
    val repo: GitRepo,
    val result: LoadResult,
    val rows: List<GraphRow>,
    val headId: String?,
)

@Composable
fun App(initialDir: File, reportInitialError: Boolean) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RepoState?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CommitInfo?>(null) }

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

    Column(Modifier.fillMaxSize()) {
        Toolbar(state, loading, onOpen = { chooseDirectory()?.let { open(it) } }, onRefresh = {
            state?.repo?.workTree?.let { open(it) } ?: state?.repo?.repository?.directory?.let { open(it) }
        })
        HorizontalDivider()
        error?.let {
            Text("打开失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator()
                else Text("点击左上角「打开仓库」选择一个本地 git 仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                CommitList(s, selected, onSelect = { selected = it }, Modifier.weight(0.55f).fillMaxHeight())
                VerticalDivider()
                CommitDetail(s.repo, selected, Modifier.weight(0.45f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Toolbar(state: RepoState?, loading: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onOpen, enabled = !loading) { Text("打开仓库") }
        OutlinedButton(onClick = onRefresh, enabled = state != null && !loading) { Text("刷新") }
        if (state != null) {
            Text(state.repo.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("当前分支：${state.result.currentBranch ?: "?"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val count = state.result.commits.size
            Text(
                if (state.result.truncated) "显示最近 $count 个提交" else "$count 个提交",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private fun formatTime(seconds: Long) = dateFormat.format(Instant.ofEpochSecond(seconds))

@Composable
private fun CommitList(state: RepoState, selected: CommitInfo?, onSelect: (CommitInfo) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    val lanesShown = remember(state) {
        (state.rows.maxOfOrNull { it.width } ?: 1).coerceIn(1, MAX_VISIBLE_LANES)
    }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
            itemsIndexed(state.result.commits, key = { _, c -> c.id }) { index, c ->
                val isSelected = c.id == selected?.id
                Row(
                    Modifier.fillMaxWidth().height(28.dp)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onSelect(c) }
                        .padding(start = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GraphCell(state.rows[index], lanesShown, c.id == state.headId)
                    Spacer(Modifier.width(6.dp))
                    c.refs.forEach { RefChip(it) }
                    Text(
                        c.shortMessage, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp, modifier = Modifier.weight(1f),
                        fontWeight = if (c.id == state.headId) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(c.author, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp).padding(start = 8.dp))
                    Text(formatTime(c.timeSeconds), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(118.dp).padding(start = 8.dp))
                    Text(c.shortId, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun RefChip(ref: RefLabel) {
    val color = when (ref.kind) {
        RefKind.HEAD -> Color(0xFFE57373)
        RefKind.BRANCH -> Color(0xFF64B5F6)
        RefKind.REMOTE -> Color(0xFF90A4AE)
        RefKind.TAG -> Color(0xFFFFD54F)
    }
    Box(
        Modifier.padding(end = 6.dp)
            .background(color.copy(alpha = if (ref.isCurrent) 0.45f else 0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            ref.name,
            fontSize = 11.sp, color = color, maxLines = 1,
            fontWeight = if (ref.isCurrent) FontWeight.Bold else FontWeight.Normal,
        )
    }
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

    Column(modifier) {
        // 提交信息
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(commit.fullMessage.trim(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 6,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("${commit.author} <${commit.email}> · ${formatTime(commit.timeSeconds)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(commit.id, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (commit.parents.size > 1) {
                    Text("合并提交，父提交：${commit.parents.joinToString { it.take(8) }}（diff 相对第一个父提交）",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()

        // 改动文件列表
        val loaded = changes
        val list = loaded?.getOrNull()
        val failure = loaded?.exceptionOrNull()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
            if (failure != null) item {
                Text("读取改动失败：${failure.message ?: failure}", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp))
            }
            else if (list == null) item { Text("加载中…", Modifier.padding(12.dp)) }
            else if (list.isEmpty()) item { Text("没有文件改动", Modifier.padding(12.dp)) }
            else itemsIndexed(list) { _, f ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (f == selectedFile) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { selectedFile = f }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (tag, color) = changeBadge(f.type)
                    Text(tag, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, modifier = Modifier.width(18.dp))
                    Text(f.displayPath, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider()

        // diff
        // 改动已读完却没有选中文件，说明没有可显示的 diff，不要一直转圈
        if (loaded != null && selectedFile == null) Spacer(Modifier.weight(1f))
        else DiffView(diff, Modifier.fillMaxWidth().weight(1f))
    }
}

private fun changeBadge(type: DiffEntry.ChangeType): Pair<String, Color> = when (type) {
    DiffEntry.ChangeType.ADD -> "A" to Color(0xFF81C784)
    DiffEntry.ChangeType.DELETE -> "D" to Color(0xFFE57373)
    DiffEntry.ChangeType.MODIFY -> "M" to Color(0xFFFFB74D)
    DiffEntry.ChangeType.RENAME -> "R" to Color(0xFF64B5F6)
    DiffEntry.ChangeType.COPY -> "C" to Color(0xFFBA68C8)
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
    Box(modifier.background(Color(0xFF1B1B1F))) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
            itemsIndexed(lines) { _, line ->
                val (fg, bg) = when (line.kind) {
                    DiffLineKind.HEADER -> Color(0xFF9E9E9E) to Color.Transparent
                    DiffLineKind.HUNK -> Color(0xFF64B5F6) to Color(0x1A64B5F6)
                    DiffLineKind.ADDED -> Color(0xFFA5D6A7) to Color(0x2A2E7D32)
                    DiffLineKind.REMOVED -> Color(0xFFEF9A9A) to Color(0x2AC62828)
                    DiffLineKind.CONTEXT -> Color(0xFFD0D0D0) to Color.Transparent
                }
                Text(
                    line.text.ifEmpty { " " }, color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp),
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
