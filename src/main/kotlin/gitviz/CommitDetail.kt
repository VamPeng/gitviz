package gitviz

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry

private enum class FileTab { Diff, History, Blame }
private enum class DiffMode { Unified, Split }

private val LineHeight = 18.dp
private val CharWidth = 7.4.dp // Menlo 12sp 一个字符的大致宽度，用来算横向滚动的范围
private val NumberColumn = 40.dp
private val BlameColumn = 176.dp

@Composable
internal fun CommitDetail(repo: GitRepo, commit: CommitInfo?, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    if (commit == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("选择一个提交") }
        return
    }
    // 刷新后 repo 是新对象，旧的 FileChange 不能再用，所以 repo 也要作为 key
    var changes by remember(repo, commit.id) { mutableStateOf<Result<List<FileChange>>?>(null) }
    var selectedFile by remember(repo, commit.id) { mutableStateOf<FileChange?>(null) }
    var tab by remember { mutableStateOf(FileTab.Diff) }
    var mode by remember { mutableStateOf(DiffMode.Unified) }

    LaunchedEffect(repo, commit.id) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.changes(commit.id) } }
        changes = result
        selectedFile = result.getOrNull()?.firstOrNull()
    }

    Column(modifier) {
        CommitHeader(commit, onOpenCommit)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ChangedFiles(changes, selectedFile, onSelect = { selectedFile = it }, Modifier.width(340.dp).fillMaxHeight())
            VerticalDivider()
            val file = selectedFile
            if (file != null) {
                FilePane(repo, commit, file, tab, { tab = it }, mode, { mode = it }, onOpenCommit,
                    Modifier.weight(1f).fillMaxHeight().background(Palette.Panel))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CommitHeader(commit: CommitInfo, onOpenCommit: (String) -> Unit) {
    // 去掉段落间的空行，不然正文只有两行的额度会被空行占掉
    val body = commit.fullMessage.trim().substringAfter("\n\n", "").trim().replace(Regex("\n\\s*\n"), "\n")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                Column {
                    Text(commit.shortMessage, fontSize = TypeScale.Heading, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    if (body.isNotEmpty()) {
                        Text(body, fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Avatar(commit.author)
                    Text("${commit.author} <${commit.email}>", fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 1)
                }
                Text(formatTime(commit.timeSeconds), fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 1)
                if (commit.parents.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("父提交", fontSize = TypeScale.Small, color = Palette.Muted)
                        for (parent in commit.parents) {
                            Text(parent.take(7), fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Link,
                                modifier = Modifier.clickable { onOpenCommit(parent) })
                        }
                    }
                }
            }
        }
        CopyHash(commit)
    }
}

@Composable
private fun CopyHash(commit: CommitInfo) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(commit.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier.height(26.dp).clip(shape).border(1.dp, Palette.Line, shape)
            .clickable {
                clipboard.setText(AnnotatedString(commit.id))
                copied = true
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(commit.id.take(7), fontSize = TypeScale.Small, fontFamily = CodeFont)
        Text(if (copied) "已复制" else "复制", fontSize = TypeScale.Small, color = if (copied) Palette.Added else Palette.Muted)
    }
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
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${list?.size ?: 0} 个文件", fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            if (list != null) {
                Text("+${list.sumOf { it.additions }}", fontSize = TypeScale.Small, color = Palette.Added)
                Text("−${list.sumOf { it.deletions }}", fontSize = TypeScale.Small, color = Palette.Removed)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (failure != null) item {
                Text("读取改动失败：${failure.message ?: failure}", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp))
            }
            else if (list == null) item { Text("加载中…", Modifier.padding(14.dp), fontSize = TypeScale.Body) }
            else if (list.isEmpty()) item { Text("没有文件改动", Modifier.padding(14.dp), fontSize = TypeScale.Body) }
            else items(list) { f -> FileRow(f, f == selected, onSelect) }
        }
    }
}

@Composable
private fun FileRow(f: FileChange, selected: Boolean, onSelect: (FileChange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverRow().rowHighlight(selected).clickable { onSelect(f) }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (tag, color) = changeBadge(f.type)
        ChangeBadge(tag, color)
        // 这一栏比较窄，文件名放前面，不然只能看到被截断的目录
        val renamed = f.type == DiffEntry.ChangeType.RENAME || f.type == DiffEntry.ChangeType.COPY
        val name = if (renamed) f.displayPath else f.displayPath.substringAfterLast('/')
        val dir = if (renamed) "" else f.displayPath.substringBeforeLast('/', "")
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (dir.isNotEmpty()) {
                Text(dir, fontSize = TypeScale.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.Faint,
                    modifier = Modifier.weight(1f, fill = false))
            }
        }
        Text("+${f.additions}", fontSize = TypeScale.Caption, color = Palette.Added, maxLines = 1)
        Text("−${f.deletions}", fontSize = TypeScale.Caption, color = Palette.Removed, maxLines = 1)
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
private fun FilePane(
    repo: GitRepo,
    commit: CommitInfo,
    file: FileChange,
    tab: FileTab,
    onTab: (FileTab) -> Unit,
    mode: DiffMode,
    onMode: (DiffMode) -> Unit,
    onOpenCommit: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(file.displayPath, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Segmented(listOf(FileTab.Diff to "改动", FileTab.History to "历史", FileTab.Blame to "Blame"), tab, onSelect = onTab)
            if (tab == FileTab.Diff) {
                Segmented(listOf(DiffMode.Unified to "统一", DiffMode.Split to "并排"), mode, onSelect = onMode)
            }
        }
        HorizontalDivider()
        val content = Modifier.fillMaxWidth().weight(1f)
        when (tab) {
            FileTab.Diff -> DiffContent(repo, file, mode, content)
            FileTab.History -> HistoryContent(repo, commit, file, onOpenCommit, content)
            FileTab.Blame -> BlameContent(repo, commit, file, onOpenCommit, content)
        }
    }
}

/** 在 IO 线程读数据，读完之前转圈，失败时显示原因。 */
@Composable
private fun <T : Any> Loaded(key: Any, modifier: Modifier, load: () -> T, content: @Composable (T) -> Unit) {
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key) { result = withContext(Dispatchers.IO) { runCatching(load) } }
    val current = result
    when {
        current == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        current.isFailure -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("读取失败：${current.exceptionOrNull()?.message}", fontSize = TypeScale.Body, color = Palette.Removed)
        }
        else -> content(current.getOrThrow())
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, fontSize = TypeScale.Body, color = Palette.Muted) }
}

/** 代码列表：字符多的时候可以横向滚动，所有行一起动。 */
@Composable
private fun CodeList(longestLine: Int, gutter: Dp, modifier: Modifier, content: LazyListScope.() -> Unit) {
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier) {
        val width = max(maxWidth, gutter + CharWidth * longestLine + 24.dp)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(width).fillMaxHeight(), state = listState, contentPadding = PaddingValues(vertical = 4.dp), content = content)
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun LineNumber(no: Int?) {
    Text(
        no?.toString().orEmpty(), fontFamily = CodeFont, fontSize = TypeScale.Small, lineHeight = 18.sp, color = Palette.Faint,
        softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.width(NumberColumn).padding(end = 8.dp),
    )
}

@Composable
private fun CodeText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text.ifEmpty { " " }, fontFamily = CodeFont, fontSize = TypeScale.Small, lineHeight = 18.sp, color = color,
        softWrap = false, maxLines = 1, modifier = modifier)
}

private fun DiffLine.colors(): Pair<Color, Color> = when (kind) {
    DiffLineKind.ADDED -> Palette.Added to Palette.Added.copy(alpha = 0.11f)
    DiffLineKind.REMOVED -> Palette.Removed to Palette.Removed.copy(alpha = 0.10f)
    DiffLineKind.HUNK -> Palette.Link to Palette.Link.copy(alpha = 0.10f)
    else -> Palette.Muted to Color.Transparent
}

/** 行首的 +、- 和空格由行号旁的标记表示，正文里去掉。 */
private val DiffLine.code: String get() = if (kind == DiffLineKind.HUNK) text else text.drop(1)

@Composable
private fun DiffContent(repo: GitRepo, file: FileChange, mode: DiffMode, modifier: Modifier) {
    Loaded(file, modifier, { parseDiffLines(repo.diffText(file)) }) { lines ->
        val body = remember(lines) { lines.filter { it.kind != DiffLineKind.HEADER } }
        when {
            // 二进制文件、纯重命名没有 @@ 段
            body.isEmpty() -> Message(if (lines.any { "Binary" in it.text }) "二进制文件，无法显示改动" else "内容没有变化", modifier)
            mode == DiffMode.Unified -> UnifiedDiff(body, modifier)
            else -> SplitDiff(lines, modifier)
        }
    }
}

@Composable
private fun UnifiedDiff(lines: List<DiffLine>, modifier: Modifier) {
    val longest = remember(lines) { lines.maxOf { it.text.length } }
    CodeList(longest, NumberColumn * 2 + 14.dp, modifier) {
        items(lines) { line ->
            val (fg, bg) = line.colors()
            Row(Modifier.fillMaxWidth().height(LineHeight).background(bg)) {
                LineNumber(line.oldNo)
                LineNumber(line.newNo)
                val sign = when (line.kind) { DiffLineKind.ADDED -> "+"; DiffLineKind.REMOVED -> "−"; else -> "" }
                CodeText(sign, fg, Modifier.width(14.dp))
                CodeText(line.code, fg)
            }
        }
    }
}

@Composable
private fun SplitDiff(lines: List<DiffLine>, modifier: Modifier) {
    val rows = remember(lines) { toSplitRows(lines) }
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
            items(rows) { row ->
                Row(Modifier.fillMaxWidth().height(LineHeight)) {
                    SplitCell(row.left, left = true, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    SplitCell(row.right, left = false, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun SplitCell(line: DiffLine?, left: Boolean, modifier: Modifier) {
    if (line == null) {
        Spacer(modifier)
        return
    }
    val (fg, bg) = line.colors()
    Row(modifier.background(bg)) {
        LineNumber(if (left) line.oldNo else line.newNo)
        CodeText(line.code, fg, Modifier.clipToBounds())
    }
}

@Composable
private fun HistoryContent(repo: GitRepo, commit: CommitInfo, file: FileChange, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    Loaded(file to commit.id, modifier, { repo.fileHistory(commit.id, file.path) }) { history ->
        if (history.isEmpty()) {
            Message("这个提交里没有这个文件的历史", modifier)
            return@Loaded
        }
        val listState = rememberLazyListState()
        Box(modifier) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(history, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().height(32.dp).hoverRow().rowHighlight(c.id == commit.id)
                            .clickable { onOpenCommit(c.id) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(c.id.take(7), fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Faint)
                        Text(c.shortMessage, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(c.author, fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 1)
                        Text(formatTime(c.timeSeconds), fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 1)
                    }
                    HorizontalDivider()
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun BlameContent(repo: GitRepo, commit: CommitInfo, file: FileChange, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    if (file.type == DiffEntry.ChangeType.DELETE) {
        Message("这个文件在这个提交里被删除了", modifier)
        return
    }
    Loaded(file to commit.id, modifier, { repo.blame(commit.id, file.path) }) { lines ->
        if (lines.isEmpty()) {
            Message("二进制文件或空文件，没有可显示的行", modifier)
            return@Loaded
        }
        val longest = remember(lines) { lines.maxOf { it.text.length } }
        CodeList(longest, BlameColumn + NumberColumn, modifier) {
            itemsIndexed(lines) { i, line ->
                // 连续几行来自同一个提交时，只在第一行写哈希和作者
                val first = i == 0 || lines[i - 1].commitId != line.commitId
                Row(Modifier.fillMaxWidth().height(LineHeight).background(if (line.commitId == commit.id) Palette.Hover else Color.Transparent)) {
                    Row(
                        Modifier.width(BlameColumn).fillMaxHeight().clickable { onOpenCommit(line.commitId) }.padding(start = 14.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (first) {
                            CodeText(line.commitId.take(7), Palette.Link)
                            Text(line.author, fontSize = TypeScale.Caption, lineHeight = 18.sp, color = Palette.Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    LineNumber(line.lineNo)
                    CodeText(line.text, Palette.Ink)
                }
            }
        }
    }
}
