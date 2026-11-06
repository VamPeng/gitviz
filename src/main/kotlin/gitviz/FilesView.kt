package gitviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TreeWidth = 260.dp
private val StatusColumn = 96.dp
private val SizeColumn = 72.dp
private val TimeColumn = 92.dp

/**
 * 文件：左边是工作区的目录树，右边是当前目录的文件表，被忽略的也列出来。
 * 目录结构直接读磁盘，git status 只用来标状态、补上已删除的文件。不预览内容，双击交给系统打开。
 * reloadKey 变了（工作区状态重读过）就重新读一遍。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilesView(
    cli: GitCli,
    root: File,
    reloadKey: Any,
    onShowChanges: (String) -> Unit,
    onShowHistory: (String) -> Unit,
    onFailure: (title: String, detail: String) -> Unit,
    modifier: Modifier,
    onRun: OperationRunner,
    onSuccess: (String) -> Unit,
    busy: Boolean = false,
    /** 一开始看哪个目录，默认根目录。 */
    start: String = "",
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showIgnored by remember { mutableStateOf(Settings.showIgnoredFiles) }
    var expanded by remember(root) { mutableStateOf(if (start.isEmpty()) emptySet() else (ancestorsOf(start) + start).toSet()) }
    var current by remember(root) { mutableStateOf(start) }
    var selected by remember(root) { mutableStateOf<String?>(null) }
    var status by remember(root) { mutableStateOf<TreeStatus?>(null) }
    var listings by remember(root) { mutableStateOf(emptyMap<String, DirListing>()) }
    // listings 是按哪一次 status 读的；status 换了就要整个重读
    var listedWith by remember(root) { mutableStateOf<TreeStatus?>(null) }
    var revision by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf<List<MenuEntry>?>(null) }
    // 传进来的可能是 . 这样的相对路径，显示名字要用真实的目录名
    val rootName = remember(root) { root.canonicalFile.name }

    LaunchedEffect(cli, reloadKey, revision) {
        status = withContext(Dispatchers.IO) { TreeStatus(cli.treeStatus()) }
    }

    LaunchedEffect(status, expanded, current) {
        val st = status ?: return@LaunchedEffect
        val stale = listedWith !== st
        val wanted = directoriesToRead(expanded, current)
        val toRead = if (stale) wanted else wanted.filter { it !in listings }
        if (toRead.isEmpty()) return@LaunchedEffect
        val read = withContext(Dispatchers.IO) {
            // 点去别的目录时这次读取会被取消，预读子目录也跟着停
            val context = currentCoroutineContext()
            toRead.associateWith { dir -> runCatching { listDirectory(root, dir, st, cancelled = { !context.isActive }) }.getOrNull() }
        }
        ensureActive()
        // 看着的目录被删掉了（磁盘上没有、git 也不认识），退回到还在的上一级
        val gone = read.filter { (dir, listing) -> dir.isNotEmpty() && (listing == null || !File(root, dir).isDirectory && listing.entries.isEmpty()) }.keys
        val fresh = read.filterValues { it != null }.mapValues { it.value!! }
        listings = (if (stale) fresh else listings + fresh) - gone
        listedWith = st
        if (current in gone) current = (ancestorsOf(current) + "").first { it !in gone }
        if (gone.isNotEmpty()) expanded = expanded - gone
    }

    /** dir 里有没有（看得见的）子目录：读过它就看内容，否则看列父目录时预读的结果，不知道时为 null。 */
    fun hasSubdirs(dir: String): Boolean? {
        listings[dir]?.let { l -> return l.entries.any { it.isDir && (showIgnored || !it.ignored) } }
        return listings[parentOf(dir)]?.entries?.firstOrNull { it.path == dir }?.subdirs?.has(showIgnored)
    }

    /** 右边打开 dir，左边展开它的所有上级。它自己只在可能有子目录时展开，免得冒出一行“正在读取”又消失。 */
    fun open(dir: String) {
        current = dir
        selected = null
        val self = if (dir.isNotEmpty() && hasSubdirs(dir) != false) listOf(dir) else emptyList()
        expanded = expanded + ancestorsOf(dir) + self
    }

    fun systemOpen(entry: FileEntry) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { openWithSystem(File(root, entry.path)) }.exceptionOrNull() }
            if (error != null && error !is CancellationException) {
                onFailure(t("Couldn't open ${entry.name}", "打不开 ${entry.name}"), error.message ?: error.toString())
            }
        }
    }

    fun entryMenu(entry: FileEntry): List<MenuEntry> {
        val file = File(root, entry.path)
        val items = ArrayList<MenuEntry>()
        if (!entry.missing) {
            items += MenuItem(if (entry.isDir) t("Open", "打开") else t("Open with default app", "用默认程序打开")) {
                if (entry.isDir) open(entry.path) else systemOpen(entry)
            }
            items += MenuItem(t(if (isMac) "Show in Finder" else "Show in folder", if (isMac) "在 Finder 中显示" else "在文件夹中显示")) {
                scope.launch {
                    val error = withContext(Dispatchers.IO) { runCatching { revealInFileManager(file) }.exceptionOrNull() }
                    if (error is CancellationException) throw error
                    if (error != null) onFailure(t("Couldn't show ${entry.name}", "无法显示 ${entry.name}"), error.message ?: error.toString())
                }
            }
            items += MenuSeparator
        }
        items += MenuItem(t("Copy path", "复制路径")) { clipboard.setText(AnnotatedString(file.path)); onSuccess(t("Path copied", "已复制路径")) }
        items += MenuItem(t("Copy relative path", "复制相对路径")) { clipboard.setText(AnnotatedString(entry.path)); onSuccess(t("Relative path copied", "已复制相对路径")) }
        val working = status?.working(entry.path)
        val showChanges = !entry.isDir && working != null && !working.conflicted && !entry.ignored
        val showHistory = !entry.ignored && entry.mark != FileMark.UNTRACKED && entry.mark != FileMark.ADDED && !(entry.isDir && entry.missing)
        val canIgnore = !entry.isDir && (entry.mark == FileMark.UNTRACKED || entry.mark == FileMark.REMOVED)
        if (showChanges || showHistory || canIgnore) items += MenuSeparator
        if (showChanges) items += MenuItem(t("Show changes", "查看改动")) { onShowChanges(entry.path) }
        if (showHistory) items += MenuItem(t("Show history", "查看历史")) { onShowHistory(entry.path) }
        if (canIgnore) {
            items += MenuItem(t("Add to .gitignore", "加入 .gitignore"), enabled = !busy) {
                onRun(t("Updating .gitignore", "正在更新 .gitignore"), t("Added ${entry.name} to .gitignore", "已将 ${entry.name} 加入 .gitignore"), {
                    ignore(entry.path, entry.isDir)
                }) { revision++ }
            }
        }
        return items
    }

    Row(modifier) {
        // 左边：目录树
        Column(Modifier.width(TreeWidth).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().height(32.dp).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CheckBox(showIgnored) {
                    showIgnored = !showIgnored
                    Settings.updateShowIgnoredFiles(showIgnored)
                    // 正在看的目录被藏起来了，回到根目录
                    if (!showIgnored && status?.isIgnored(current) == true) open("")
                }
                Text(t("Show ignored", "显示忽略的"), fontSize = TypeScale.Small, color = Palette.Muted,
                    modifier = Modifier.weight(1f).clickable {
                        showIgnored = !showIgnored
                        Settings.updateShowIgnoredFiles(showIgnored)
                        if (!showIgnored && status?.isIgnored(current) == true) open("")
                    })
                IconButton(Icons.Refresh) { revision++ }
            }
            HorizontalDivider()
            val rows = remember(listings, expanded, showIgnored) { flattenDirTree(listings, expanded, showIgnored) }
            val treeState = rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = treeState, contentPadding = PaddingValues(vertical = 4.dp)) {
                    item(key = "/") {
                        TreeRow(0, current == "", null, onToggle = null, onClick = { open("") }) {
                            Icon(Icons.Folder, Palette.Muted, size = 14.dp)
                            Text(rootName, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is DirRow.Loading -> AfterDelay {
                                Text(
                                    t("Loading…", "正在读取…"), fontSize = TypeScale.Small, color = Palette.Faint,
                                    modifier = Modifier.height(24.dp).padding(start = treeIndent(row.depth + 1) + 18.dp).wrapContentHeight(),
                                )
                            }
                            is DirRow.Item -> {
                                val e = row.entry
                                TreeRow(
                                    row.depth + 1, current == e.path, row.expanded.takeIf { row.hasSubdirs != false },
                                    onToggle = { expanded = if (row.expanded) expanded - e.path else expanded + e.path },
                                    onClick = { open(e.path) },
                                ) {
                                    val ink = if (e.ignored) Palette.Faint else Palette.Ink
                                    Icon(Icons.Folder, if (e.ignored) Palette.Faint else Palette.Muted, size = 14.dp)
                                    Text(e.name, fontSize = TypeScale.Body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textDecoration = if (e.missing) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f, fill = false))
                                    if (e.dirty) Dot(Palette.Modified)
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(treeState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        VerticalDivider()
        // 右边：当前目录的文件表
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Panel)) {
            Breadcrumb(rootName, current, onOpen = ::open)
            HorizontalDivider()
            TableHeader()
            HorizontalDivider()
            val listing = listings[current]
            val now = rememberNowSeconds()
            if (listing == null) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AfterDelay { Message(t("Loading…", "正在读取…"), Modifier.fillMaxSize()) }
                }
            } else {
                val shown = remember(listing, showIgnored) { listing.entries.filter { showIgnored || !it.ignored } }
                val hidden = listing.entries.size - shown.size
                if (shown.isEmpty()) {
                    Message(if (hidden > 0) t("Only ignored files here", "这里只有被忽略的文件") else t("Empty folder", "空文件夹"), Modifier.fillMaxWidth().weight(1f))
                } else {
                    val tableState = rememberLazyListState()
                    LaunchedEffect(current) { tableState.scrollToItem(0) }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize(), state = tableState) {
                            items(shown, key = { it.path }) { e ->
                                FileTableRow(
                                    e, now, e.path == selected,
                                    onClick = { selected = e.path },
                                    onOpen = {
                                        if (e.isDir) open(e.path)
                                        else if (e.missing) onFailure(t("${e.name} was deleted", "${e.name} 已删除"), t("It's no longer on disk. You can restore it in Changes.", "磁盘上已经没有这个文件，可以在“本地改动”里恢复。"))
                                        else systemOpen(e)
                                    },
                                    onMenu = {
                                        selected = e.path
                                        menu = entryMenu(e)
                                    },
                                )
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(tableState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val parts = buildList {
                        add(t(countOf(shown.size, "item"), "${shown.size} 项"))
                        if (listing.omitted > 0) add(t("${listing.omitted} more not listed", "还有 ${listing.omitted} 项没有列出"))
                        if (hidden > 0) add(t("${countOf(hidden, "ignored item")} hidden", "已隐藏 $hidden 个被忽略的"))
                    }
                    Text(parts.joinToString(" · "), fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1)
                }
            }
        }
    }
    menu?.let { GitMenuPopup(it, onDismiss = { menu = null }) }
}

private fun treeIndent(depth: Int): Dp = (8 + 14 * depth).dp

/** 读目录一般只要几毫秒，“正在读取”一闪而过反而像在闪烁，所以过一会儿还没读完才显示。之前不占位置。 */
private const val LOADING_DELAY_MS = 200L

@Composable
private fun AfterDelay(content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_DELAY_MS)
        show = true
    }
    if (show) content()
}

/** 目录树的一行。expanded 为 null 时不画箭头（没有子目录）；onToggle 为 null 时这一行不能折叠（根目录）。 */
@Composable
private fun TreeRow(
    depth: Int,
    selected: Boolean,
    expanded: Boolean?,
    onToggle: (() -> Unit)?,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).hoverRow().rowHighlight(selected).clickable(onClick = onClick)
            .padding(start = treeIndent(depth), end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(14.dp).then(if (onToggle != null && expanded != null) Modifier.clickable(onClick = onToggle) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (onToggle != null && expanded != null) Chevron(expanded)
        }
        content()
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val color = Palette.Muted
    Canvas(Modifier.size(10.dp).rotate(if (expanded) 90f else 0f)) {
        val c = Offset(size.width / 2, size.height / 2)
        val arm = 2.5.dp.toPx()
        val path = Path().apply {
            moveTo(c.x - arm / 2, c.y - arm)
            lineTo(c.x + arm / 2, c.y)
            lineTo(c.x - arm / 2, c.y + arm)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun CheckBox(checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.size(13.dp).background(if (checked) Palette.Accent else Palette.Field, shape).border(1.dp, if (checked) Palette.Accent else Palette.Line, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Check, Palette.OnAccent, size = 10.dp, strokeWidth = 3f)
    }
}

/** 当前目录的路径，每一段都能点，回到那一级。 */
@Composable
private fun Breadcrumb(rootName: String, current: String, onOpen: (String) -> Unit) {
    val parts = if (current.isEmpty()) emptyList() else current.split('/')
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val segments = listOf("" to rootName) + parts.indices.map { i -> parts.take(i + 1).joinToString("/") to parts[i] }
        segments.forEachIndexed { i, (path, name) ->
            if (i > 0) Text("/", fontSize = TypeScale.Small, color = Palette.Faint)
            val last = i == segments.lastIndex
            Text(
                name, fontSize = TypeScale.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) Palette.Ink else Palette.Muted,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = !last) { onOpen(path) }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Name", "名称"), fontSize = TypeScale.Caption, color = Palette.Faint, modifier = Modifier.weight(1f))
        HeaderCell(t("Status", "状态"), StatusColumn)
        HeaderCell(t("Size", "大小"), SizeColumn)
        HeaderCell(t("Modified", "修改时间"), TimeColumn)
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(text, fontSize = TypeScale.Caption, color = Palette.Faint, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTableRow(entry: FileEntry, now: Long, selected: Boolean, onClick: () -> Unit, onOpen: () -> Unit, onMenu: () -> Unit) {
    val ink = if (entry.ignored) Palette.Faint else Palette.Ink
    Row(
        Modifier.fillMaxWidth().height(28.dp).hoverRow().rowHighlight(selected)
            .onRightClick(onMenu)
            .combinedClickable(onClick = onClick, onDoubleClick = onOpen)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (entry.isDir) Icons.Folder else Icons.File, if (entry.ignored) Palette.Faint else Palette.Muted, size = 14.dp)
            Text(entry.name, fontSize = TypeScale.Body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (entry.missing) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false))
            badgeCode(entry.mark)?.let { val (tag, color) = statusBadge(it); ChangeBadge(tag, color) }
        }
        val (label, color) = statusLabel(entry)
        Text(label, fontSize = TypeScale.Small, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(StatusColumn))
        Text(if (entry.isDir || entry.missing) "" else formatSize(entry.size), fontSize = TypeScale.Small, color = Palette.Muted,
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(SizeColumn))
        Box(Modifier.width(TimeColumn), contentAlignment = Alignment.CenterEnd) {
            if (entry.modified > 0) RelativeTime(entry.modified / 1000, now)
        }
    }
}

/** 行里的 M、A、D 小标记，和本地改动页一样。 */
private fun badgeCode(mark: FileMark): Char? = when (mark) {
    FileMark.MODIFIED -> 'M'
    FileMark.ADDED -> 'A'
    FileMark.DELETED, FileMark.REMOVED -> 'D'
    FileMark.RENAMED -> 'R'
    FileMark.UNTRACKED -> '?'
    FileMark.CONFLICTED -> 'U'
    FileMark.CLEAN, FileMark.IGNORED -> null
}

private fun statusLabel(e: FileEntry): Pair<String, Color> = when (e.mark) {
    FileMark.MODIFIED -> t("Modified", "已修改") to Palette.Modified
    FileMark.ADDED -> t("Added", "新增") to Palette.Added
    FileMark.DELETED -> t("Deleted", "已删除") to Palette.Removed
    FileMark.RENAMED -> t("Renamed", "重命名") to Palette.Renamed
    FileMark.UNTRACKED -> t("Untracked", "未跟踪") to Palette.Link
    FileMark.CONFLICTED -> t("Conflict", "冲突") to Palette.Removed
    FileMark.REMOVED -> t("Untracked now", "已移出版本库") to Palette.Removed
    FileMark.IGNORED -> (if (e.ignoredRoot) t("Ignored", "已忽略") else "") to Palette.Faint
    FileMark.CLEAN -> (if (e.dirty) t("Has changes", "有改动") else "") to Palette.Modified
}

/** 用系统默认程序打开。 */
private fun openWithSystem(file: File) {
    if (isMac) {
        runSystem("open", file.path)
    } else {
        java.awt.Desktop.getDesktop().open(file)
    }
}

/** 在 Finder（或系统的文件管理器）里显示并选中这个文件。 */
private fun revealInFileManager(file: File) {
    val desktop = java.awt.Desktop.getDesktop()
    when {
        isMac -> runSystem("open", "-R", file.path)
        desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
        else -> desktop.open(if (file.isDirectory) file else file.parentFile)
    }
}

private fun runSystem(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) error(output.trim().ifEmpty { "${command.first()} failed" })
}
