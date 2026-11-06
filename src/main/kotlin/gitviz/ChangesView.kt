package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Selection(val path: String, val staged: Boolean)

/** 本地改动：未暂存、已暂存两个列表，提交说明，右边是所选文件的 diff。 */
@Composable
internal fun ChangesView(
    cli: GitCli,
    files: List<WorkingFile>,
    branch: String?,
    onChanged: (historyChanged: Boolean) -> Unit,
    onRun: OperationRunner,
    modifier: Modifier,
    busy: Boolean = false,
    externalRevision: Int = 0,
    draft: CommitDraft = remember(cli) { CommitDraft() },
    /** 从别处（比如文件页）带过来、要选中的文件。选中后调用 onFocused。 */
    focus: String? = null,
    onFocused: () -> Unit = {},
) {
    var selection by remember(cli) { mutableStateOf<Selection?>(null) }
    LaunchedEffect(focus) {
        val path = focus ?: return@LaunchedEffect
        files.firstOrNull { it.path == path }?.let { selection = Selection(it.path, staged = !it.hasUnstaged) }
        onFocused()
    }
    var mode by remember { mutableStateOf(Settings.defaultDiffMode) }
    var toDiscard by remember { mutableStateOf<WorkingFile?>(null) }
    var revision by remember { mutableStateOf(0) }

    val unstaged = files.filter { it.hasUnstaged }
    val staged = files.filter { it.hasStaged }

    fun apply(running: String, success: String, historyChanged: Boolean = false, op: GitCli.() -> GitResult, done: () -> Unit = {}) {
        onRun(running, success, op) { ok ->
            if (ok) done()
            onChanged(historyChanged)
            revision++
        }
    }

    // 选中的文件没了（比如已提交）就回到第一个
    val current = selection?.takeIf { s -> (if (s.staged) staged else unstaged).any { it.path == s.path } }
        ?: unstaged.firstOrNull()?.let { Selection(it.path, false) }
        ?: staged.firstOrNull()?.let { Selection(it.path, true) }
    val currentFile = current?.let { s -> files.firstOrNull { it.path == s.path } }

    Row(modifier) {
        Column(Modifier.width(340.dp).fillMaxHeight()) {
            ScrollList(Modifier.fillMaxWidth().weight(1f)) {
                item { GroupHeader(t("Unstaged", "未暂存"), unstaged.size, divider = false) }
                items(unstaged, key = { "u:" + it.path }) { f ->
                    ChangeRow(f, f.worktree, current == Selection(f.path, false), { selection = Selection(f.path, false) }) {
                        RowAction(t("Discard", "丢弃"), Palette.Removed, !busy) { toDiscard = f }
                        RowAction(t("Stage", "暂存"), Palette.Link, !busy) { apply(t("Staging", "正在暂存"), t("Staged ${f.path}", "已暂存 ${f.path}"), op = { stage(f) }) }
                    }
                }
                item { GroupHeader(t("Staged", "已暂存"), staged.size) }
                items(staged, key = { "s:" + it.path }) { f ->
                    ChangeRow(f, f.index, current == Selection(f.path, true), { selection = Selection(f.path, true) }) {
                        RowAction(t("Unstage", "取消暂存"), Palette.Link, !busy) { apply(t("Unstaging", "正在取消暂存"), t("Unstaged ${f.path}", "已取消暂存 ${f.path}"), op = { unstage(f) }) }
                    }
                }
            }
            HorizontalDivider()
            CommitBox(cli, branch, staged.size, !busy, draft, onCommit = { message, amend, done ->
                apply(t("Committing", "正在提交"), t("Committed", "已提交"), historyChanged = true, op = { commit(message, amend) }, done = done)
            })
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Panel)) {
            if (current == null || currentFile == null) {
                Message(t("Working tree is clean", "工作区是干净的"), Modifier.fillMaxSize())
            } else {
                Row(
                    Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(current.path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), mode) { mode = it }
                }
                HorizontalDivider()
                val content = Modifier.fillMaxWidth().weight(1f)
                PartialStageView(cli, currentFile, current.staged, revision + externalRevision, mode, busy,
                    onApply = { document, selected -> apply(t("Updating selected changes", "正在更新选中改动"), t("Selected changes updated", "选中改动已更新"), op = { applySelection(document, selected) }) }, modifier = content)
            }
        }
    }

    toDiscard?.let { f ->
        AlertDialog(
            onDismissRequest = { toDiscard = null },
            title = { Text(t("Discard changes", "丢弃改动"), fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (f.untracked) t("“${f.path}” is a new file. It will be deleted after saving a recovery point.", "「${f.path}」是新文件，保存恢复点后将被删除。") else t("Unstaged changes to “${f.path}” will be discarded after saving a recovery point.", "「${f.path}」未暂存的改动会在保存恢复点后被丢弃。"),
                    fontSize = TypeScale.Body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toDiscard = null
                    apply(t("Discarding", "正在丢弃"), t("Discarded changes to ${f.path}", "已丢弃 ${f.path} 的改动"), op = { discard(f) })
                }) { Text(t("Discard", "丢弃"), color = Palette.Removed) }
            },
            dismissButton = { TextButton(onClick = { toDiscard = null }) { Text(t("Cancel", "取消"), color = Palette.Ink) } },
            containerColor = Palette.Field,
            shape = RoundedCornerShape(10.dp),
        )
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, divider: Boolean = true) {
    // 分隔线画在标题上方，把上一组和这一组隔开；第一组上面已经有工具栏的分隔线
    if (divider) HorizontalDivider()
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$title · $count", fontSize = TypeScale.Small, color = Palette.Muted)
    }
}

internal fun statusBadge(code: Char): Pair<String, Color> = when (code) {
    '?' -> "?" to Palette.Link
    'A' -> "A" to Palette.Added
    'D' -> "D" to Palette.Removed
    'R' -> "R" to Palette.Renamed
    'C' -> "C" to Palette.Copied
    'U' -> "U" to Palette.Removed
    else -> "M" to Palette.Modified
}

@Composable
private fun ChangeRow(file: WorkingFile, code: Char, selected: Boolean, onSelect: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverRow().rowHighlight(selected).clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (tag, color) = statusBadge(code)
        ChangeBadge(tag, color)
        // 只显示文件名，完整路径放在悬浮提示里
        Hint(file.path, Modifier.weight(1f)) {
            Text(file.path.substringAfterLast('/'), fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun RowAction(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = TypeScale.Caption, color = color, maxLines = 1,
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 5.dp, vertical = 3.dp))
}

@Composable
private fun CommitBox(cli: GitCli, branch: String?, stagedCount: Int, enabled: Boolean, draft: CommitDraft, onCommit: (String, Boolean, () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by draft::message
    var amend by draft::amend
    val registry = LocalCommands.current
    val canCommit = enabled && message.isNotBlank() && (amend || stagedCount > 0)
    val shape = RoundedCornerShape(5.dp)
    fun commit() {
        if (!canCommit) return
        if (registry != null) { registry.execute("commit"); return }
        onCommit(message.trim(), amend) {
            message = ""
            amend = false
        }
    }

    Column(Modifier.fillMaxWidth().background(Palette.Rail).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Commit message", "提交说明"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            Row(
                Modifier.clickable(enabled = enabled) {
                    amend = !amend
                    if (amend) scope.launch { message = withContext(Dispatchers.IO) { cli.lastCommitMessage() } } else message = ""
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(12.dp).background(if (amend) Palette.Accent else Palette.Field, RoundedCornerShape(3.dp)).border(1.dp, Palette.Line, RoundedCornerShape(3.dp)))
                Text(t("Amend last commit", "修改上一次提交"), fontSize = TypeScale.Small, color = Palette.Muted)
            }
        }
        BasicTextField(
            message, { message = it }, enabled = enabled,
            textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body, fontFamily = UiFont),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().height(110.dp).textInputFocus()
                // Cmd+Enter 提交；不能提交时也吞掉这个按键，免得插进一个换行
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.Enter || !(e.isMetaPressed || e.isCtrlPressed)) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) commit()
                    true
                }
                .background(Palette.Ground, shape).border(1.dp, Palette.Line, shape).padding(8.dp),
        )
        val target = branch ?: t("HEAD", "当前提交")
        Text(
            if (amend) t("Amend last commit", "修改上一次提交") else t("Commit to $target · ${countOf(stagedCount, "file")}", "提交到 $target · $stagedCount 个文件"),
            fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().alpha(if (canCommit) 1f else 0.45f).clip(shape).background(Palette.Accent)
                .clickable(enabled = canCommit) { commit() }
                .padding(vertical = 6.dp),
        )
    }
}
