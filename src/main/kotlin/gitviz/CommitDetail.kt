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
internal enum class DiffMode { Unified, Split }

private val LineHeight = 18.dp
private val CharWidth = 7.4.dp // Menlo 12sp 一个字符的大致宽度，用来算横向滚动的范围
private val NumberColumn = 40.dp
private val BlameColumn = 176.dp

@Composable
internal fun CommitDetail(
    repo: GitRepo,
    commit: CommitInfo?,
    onOpenCommit: (String) -> Unit,
    modifier: Modifier,
    onShowFileHistory: (String) -> Unit = {},
) {
    if (commit == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(t("Select a commit", "选择一个提交")) }
        return
    }
    // 刷新后 repo 是新对象，旧的 FileChange 不能再用，所以 repo 也要作为 key
    // 合并提交和哪个父提交比较，换提交时回到第一个
    var parentIndex by remember(commit.id) { mutableStateOf(0) }
    var changes by remember(repo, commit.id, parentIndex) { mutableStateOf<Result<List<FileChange>>?>(null) }
    var selectedFile by remember(repo, commit.id, parentIndex) { mutableStateOf<FileChange?>(null) }
    var tab by remember { mutableStateOf(FileTab.Diff) }
    var mode by remember { mutableStateOf(Settings.defaultDiffMode) }

    LaunchedEffect(repo, commit.id, parentIndex) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.changes(commit.id, parentIndex) } }
        changes = result
        // 换父提交时尽量停在同一个文件上
        val list = result.getOrNull()
        selectedFile = list?.firstOrNull { it.path == selectedFile?.path } ?: list?.firstOrNull()
    }

    Column(modifier) {
        CommitHeader(commit, onOpenCommit)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ChangedFiles(commit, parentIndex, { parentIndex = it }, changes, selectedFile, onSelect = { selectedFile = it },
                Modifier.width(340.dp).fillMaxHeight())
            VerticalDivider()
            val file = selectedFile
            if (file != null) {
                FilePane(repo, commit, file, tab, { tab = it }, mode, { mode = it }, onOpenCommit, onShowFileHistory,
                    Modifier.weight(1f).fillMaxHeight().background(Palette.Panel), parentIndex)
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
                        Text(t("Parents", "父提交"), fontSize = TypeScale.Small, color = Palette.Muted)
                        for (parent in commit.parents) {
                            Text(parent.take(7), fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Link,
                                modifier = Modifier.clickable { onOpenCommit(parent) })
                        }
                    }
                }
            }
            if (commit.committedSeparately) {
                // 作者名一样时只说时间，免得同一个名字写两遍
                val who = if (commit.committer == commit.author && commit.committerEmail == commit.email) ""
                    else "${commit.committer} <${commit.committerEmail}> "
                Text(t("Committed by $who· ${formatTime(commit.commitTimeSeconds)}", "提交者 $who· ${formatTime(commit.commitTimeSeconds)}"), fontSize = TypeScale.Small, color = Palette.Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
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
        Text(if (copied) t("Copied", "已复制") else t("Copy", "复制"), fontSize = TypeScale.Small, color = if (copied) Palette.Added else Palette.Muted)
    }
}

@Composable
private fun ChangedFiles(
    commit: CommitInfo,
    parentIndex: Int,
    onParent: (Int) -> Unit,
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
            Text(t(countOf(list?.size ?: 0, "file"), "${list?.size ?: 0} 个文件"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            if (list != null) {
                Text("+${list.sumOf { it.additions }}", fontSize = TypeScale.Small, color = Palette.Added)
                Text("−${list.sumOf { it.deletions }}", fontSize = TypeScale.Small, color = Palette.Removed)
            }
        }
        HorizontalDivider()
        if (commit.parents.size > 1) {
            Row(
                Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(t("Compare", "对比"), fontSize = TypeScale.Small, color = Palette.Muted)
                Segmented(commit.parents.mapIndexed { i, id -> i to t("Parent ${i + 1} · ${id.take(7)}", "父 ${i + 1} · ${id.take(7)}") }, parentIndex, onSelect = onParent)
            }
            HorizontalDivider()
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (failure != null) item {
                Text(t("Couldn't read changes: ${failure.message ?: failure}", "读取改动失败：${failure.message ?: failure}"), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp))
            }
            else if (list == null) item { Text(t("Loading…", "加载中…"), Modifier.padding(14.dp), fontSize = TypeScale.Body) }
            else if (list.isEmpty()) item { Text(t("No file changes", "没有文件改动"), Modifier.padding(14.dp), fontSize = TypeScale.Body) }
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
        f.modeChange?.let { m ->
            Text("${m.old.takeLast(3)}→${m.new.takeLast(3)}", fontSize = TypeScale.Caption, fontFamily = CodeFont,
                color = Palette.Modified, maxLines = 1)
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
    onShowFileHistory: (String) -> Unit,
    modifier: Modifier,
    parentIndex: Int = 0,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(file.displayPath, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Segmented(listOf(FileTab.Diff to t("Changes", "改动"), FileTab.History to t("History", "历史"), FileTab.Blame to "Blame"), tab, onSelect = onTab)
            if (tab == FileTab.Diff) {
                Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), mode, onSelect = onMode)
            }
        }
        HorizontalDivider()
        val content = Modifier.fillMaxWidth().weight(1f)
        when (tab) {
            FileTab.Diff -> DiffContent(repo, file, mode, content)
            FileTab.History -> HistoryContent(repo, commit, file, onOpenCommit, onShowFileHistory, content)
            FileTab.Blame -> BlameTraceView(repo, commit, file, onOpenCommit, content, parentIndex)
        }
    }
}

/** 在 IO 线程读数据，读完之前转圈，失败时显示原因。 */
@Composable
internal fun <T : Any> Loaded(key: Any, modifier: Modifier, load: () -> T, content: @Composable (T) -> Unit) {
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key) { result = withContext(Dispatchers.IO) { runCatching(load) } }
    val current = result
    when {
        current == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        current.isFailure -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(t("Couldn't read: ${current.exceptionOrNull()?.message}", "读取失败：${current.exceptionOrNull()?.message}"), fontSize = TypeScale.Body, color = Palette.Removed)
        }
        else -> content(current.getOrThrow())
    }
}

@Composable
internal fun Message(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, fontSize = TypeScale.Body, color = Palette.Muted) }
}

/** 代码列表：字符多的时候可以横向滚动，所有行一起动。 */
@Composable
internal fun CodeList(longestLine: Int, gutter: Dp, modifier: Modifier, listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val width = max(maxWidth, gutter + CharWidth * longestLine + 24.dp)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(width).fillMaxHeight(), state = listState, contentPadding = PaddingValues(vertical = 4.dp), content = content)
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun LineNumber(no: Int?) {
    Text(
        no?.toString().orEmpty(), fontFamily = CodeFont, fontSize = TypeScale.Small, lineHeight = 18.sp, color = Palette.Faint,
        softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.width(NumberColumn).padding(end = 8.dp),
    )
}

@Composable
internal fun CodeText(text: String, color: Color, modifier: Modifier = Modifier) {
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
    Loaded(file, modifier, { SourceDiff(parseDiffLines(repo.diffText(file)), repo.diffSources(file)) }) { diff -> DiffBody(diff.lines, mode, modifier, sources = diff.sources) }
}

/**
 * 显示一个文件的 diff。没有 @@ 段时（二进制、纯重命名、只改权限）说明原因；
 * 内容和权限都改了时，在顶上加一行权限变化。
 */
@Composable
internal fun DiffBody(lines: List<DiffLine>, mode: DiffMode, modifier: Modifier, selection: DiffSelection? = null, sources: DiffSources? = null) {
    var syntax by remember(sources) { mutableStateOf<DiffSyntax?>(null) }
    LaunchedEffect(sources) { sources?.let { syntax = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.Default) { diffSyntaxCache.highlight(it) } } }
    val body = remember(lines) { lines.filter { it.kind != DiffLineKind.HEADER } }
    if (body.isEmpty()) {
        Message(remember(lines) { emptyDiffMessage(lines) }, modifier)
        return
    }
    val modeChange = remember(lines) { modeChange(lines) }
    val lfs = remember(sources) { listOfNotNull(sources?.old, sources?.new).mapNotNull { parseLfsPointer(it.text.toByteArray()) }.distinct() }
    Column(modifier) {
        if (lfs.isNotEmpty()) {
            Text(t("Git LFS pointer · ", "Git LFS 指针 · ") + lfs.joinToString(" → ") { "${it.size} bytes" } + t(" · Object content is not loaded", " · 未加载对象内容"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            HorizontalDivider()
        }
        if (modeChange != null) {
            Text(modeChange.text, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Modified,
                modifier = Modifier.fillMaxWidth().background(Palette.Modified.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 4.dp))
            HorizontalDivider()
        }
        val content = Modifier.fillMaxWidth().weight(1f)
        if (mode == DiffMode.Unified) UnifiedDiff(body, content, selection, syntax) else SplitDiff(lines, content, selection, syntax)
    }
}

@Composable
internal fun UnifiedDiff(lines: List<DiffLine>, modifier: Modifier, selection: DiffSelection? = null, syntax: DiffSyntax? = null) {
    val longest = remember(lines) { lines.maxOf { it.text.length } }
    CodeList(longest, NumberColumn * 2 + 14.dp, modifier) {
        items(lines) { line ->
            val (fg, bg) = line.colors()
            Row(Modifier.fillMaxWidth().height(LineHeight).background(if (selection?.selected?.invoke(line) == true) Palette.Accent.copy(alpha = 0.25f) else bg)
                .clickable(enabled = selection != null && line.kind in setOf(DiffLineKind.HUNK, DiffLineKind.ADDED, DiffLineKind.REMOVED)) { selection?.toggle?.invoke(line) }) {
                LineNumber(line.oldNo)
                LineNumber(line.newNo)
                val sign = when (line.kind) { DiffLineKind.ADDED -> "+"; DiffLineKind.REMOVED -> "−"; else -> "" }
                CodeText(sign, fg, Modifier.width(14.dp))
                DiffCodeText(line.code, fg, syntax?.line(line, line.kind == DiffLineKind.REMOVED))
            }
        }
    }
}

@Composable
internal fun SplitDiff(lines: List<DiffLine>, modifier: Modifier, selection: DiffSelection? = null, syntax: DiffSyntax? = null) {
    val rows = remember(lines) { toSplitRows(lines) }
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
            items(rows) { row ->
                Row(Modifier.fillMaxWidth().height(LineHeight)) {
                    SplitCell(row.left, left = true, Modifier.weight(1f).fillMaxHeight(), selection, syntax)
                    VerticalDivider()
                    SplitCell(row.right, left = false, Modifier.weight(1f).fillMaxHeight(), selection, syntax)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun SplitCell(line: DiffLine?, left: Boolean, modifier: Modifier, selection: DiffSelection? = null, syntax: DiffSyntax? = null) {
    if (line == null) {
        Spacer(modifier)
        return
    }
    val (fg, bg) = line.colors()
    Row(modifier.background(if (selection?.selected?.invoke(line) == true) Palette.Accent.copy(alpha = 0.25f) else bg)
        .clickable(enabled = selection != null && line.kind in setOf(DiffLineKind.HUNK, DiffLineKind.ADDED, DiffLineKind.REMOVED)) { selection?.toggle?.invoke(line) }) {
        LineNumber(if (left) line.oldNo else line.newNo)
        DiffCodeText(line.code, fg, syntax?.line(line, left), Modifier.clipToBounds())
    }
}

@Composable
private fun HistoryContent(
    repo: GitRepo,
    commit: CommitInfo,
    file: FileChange,
    onOpenCommit: (String) -> Unit,
    onShowFileHistory: (String) -> Unit,
    modifier: Modifier,
) {
    Loaded(file to commit.id, modifier, { repo.fileHistory(commit.id, file.path) }) { history ->
        if (history.isEmpty()) {
            Message(t("No history for this file in this commit", "这个提交里没有这个文件的历史"), modifier)
            return@Loaded
        }
        val listState = rememberLazyListState()
        val now = rememberNowSeconds()
        Box(modifier) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "show-in-graph") {
                    Text(t("Show only this file in the graph", "在主图中只看这个文件"), fontSize = TypeScale.Small, color = Palette.Link,
                        modifier = Modifier.clickable { onShowFileHistory(file.path) }.padding(horizontal = 14.dp, vertical = 8.dp))
                    HorizontalDivider()
                }
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
                        RelativeTime(c.timeSeconds, now)
                    }
                    HorizontalDivider()
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}


@Composable
private fun DiffCodeText(code: String, color: Color, syntax: SyntaxLine?, modifier: Modifier = Modifier) {
    val text = code.removeSuffix("\r")
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text.ifEmpty { " " })
        if (syntax?.text == text) syntax.tokens.forEach { token ->
            val tokenColor = when (token.kind) {
                SyntaxKind.KEYWORD -> Palette.Copied
                SyntaxKind.STRING -> Palette.Added
                SyntaxKind.COMMENT -> Palette.Faint
                SyntaxKind.NUMBER -> Palette.Modified
            }
            addStyle(androidx.compose.ui.text.SpanStyle(color = tokenColor), token.start, token.end)
        }
    }
    Text(annotated, fontFamily = CodeFont, fontSize = TypeScale.Small, lineHeight = 18.sp,
        color = if (syntax?.text == text) Palette.Ink else color, softWrap = false, maxLines = 1, modifier = modifier)
}
