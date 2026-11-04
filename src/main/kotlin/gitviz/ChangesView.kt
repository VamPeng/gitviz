package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    onFailure: (title: String, detail: String) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var selection by remember(cli) { mutableStateOf<Selection?>(null) }
    var mode by remember { mutableStateOf(Settings.defaultDiffMode) }
    var toDiscard by remember { mutableStateOf<WorkingFile?>(null) }
    var revision by remember { mutableStateOf(0) }

    val unstaged = files.filter { it.hasUnstaged }
    val staged = files.filter { it.hasStaged }

    fun apply(title: String, historyChanged: Boolean = false, op: GitCli.() -> GitResult, done: () -> Unit = {}) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { cli.op() }
            if (result.ok) done() else onFailure(title, result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty())
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
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item { GroupHeader("未暂存", unstaged.size) }
                items(unstaged, key = { "u:" + it.path }) { f ->
                    ChangeRow(f, f.worktree, current == Selection(f.path, false), { selection = Selection(f.path, false) }) {
                        RowAction("丢弃", Palette.Removed) { toDiscard = f }
                        RowAction("暂存", Palette.Link) { apply("暂存失败", op = { stage(f) }) }
                    }
                }
                item { GroupHeader("已暂存", staged.size) }
                items(staged, key = { "s:" + it.path }) { f ->
                    ChangeRow(f, f.index, current == Selection(f.path, true), { selection = Selection(f.path, true) }) {
                        RowAction("取消暂存", Palette.Link) { apply("取消暂存失败", op = { unstage(f) }) }
                    }
                }
            }
            HorizontalDivider()
            CommitBox(cli, branch, staged.size, onCommit = { message, amend, done ->
                apply("提交失败", historyChanged = true, op = { commit(message, amend) }, done = done)
            })
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Panel)) {
            if (current == null || currentFile == null) {
                Message("工作区是干净的", Modifier.fillMaxSize())
            } else {
                Row(
                    Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(current.path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Segmented(listOf(DiffMode.Unified to "统一", DiffMode.Split to "并排"), mode) { mode = it }
                }
                HorizontalDivider()
                val content = Modifier.fillMaxWidth().weight(1f)
                Loaded(Triple(current, currentFile, revision), content, { parseDiffLines(cli.diff(currentFile, current.staged)) }) { lines ->
                    DiffBody(lines, mode, content)
                }
            }
        }
    }

    toDiscard?.let { f ->
        AlertDialog(
            onDismissRequest = { toDiscard = null },
            title = { Text("丢弃改动", fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (f.untracked) "「${f.path}」是新文件，会被删除，无法恢复。" else "「${f.path}」未暂存的改动会被丢弃，无法恢复。",
                    fontSize = TypeScale.Body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toDiscard = null
                    apply("丢弃失败", op = { discard(f) })
                }) { Text("丢弃", color = Palette.Removed) }
            },
            dismissButton = { TextButton(onClick = { toDiscard = null }) { Text("取消", color = Palette.Ink) } },
            containerColor = Palette.Field,
            shape = RoundedCornerShape(10.dp),
        )
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$title · $count", fontSize = TypeScale.Small, color = Palette.Muted)
    }
    HorizontalDivider()
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
        val dir = file.path.substringBeforeLast('/', "")
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.path.substringAfterLast('/'), fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (dir.isNotEmpty()) {
                Text(dir, fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
        }
        actions()
    }
}

@Composable
private fun RowAction(label: String, color: Color, onClick: () -> Unit) {
    Text(label, fontSize = TypeScale.Caption, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 5.dp, vertical = 3.dp))
}

@Composable
private fun CommitBox(cli: GitCli, branch: String?, stagedCount: Int, onCommit: (String, Boolean, () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember(cli) { mutableStateOf("") }
    var amend by remember(cli) { mutableStateOf(false) }
    val canCommit = message.isNotBlank() && (amend || stagedCount > 0)
    val shape = RoundedCornerShape(5.dp)

    Column(Modifier.fillMaxWidth().background(Palette.Rail).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("提交说明", fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            Row(
                Modifier.clickable {
                    amend = !amend
                    if (amend) scope.launch { message = withContext(Dispatchers.IO) { cli.lastCommitMessage() } } else message = ""
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(12.dp).background(if (amend) Palette.Accent else Palette.Field, RoundedCornerShape(3.dp)).border(1.dp, Palette.Line, RoundedCornerShape(3.dp)))
                Text("修改上一次提交", fontSize = TypeScale.Small, color = Palette.Muted)
            }
        }
        BasicTextField(
            message, { message = it },
            textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body, fontFamily = UiFont),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().height(64.dp).background(Palette.Ground, shape).border(1.dp, Palette.Line, shape).padding(8.dp),
        )
        val target = branch ?: "当前提交"
        Text(
            if (amend) "修改上一次提交" else "提交到 $target · $stagedCount 个文件",
            fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().alpha(if (canCommit) 1f else 0.45f).clip(shape).background(Palette.Accent)
                .clickable(enabled = canCommit) {
                    onCommit(message.trim(), amend) {
                        message = ""
                        amend = false
                    }
                }
                .padding(vertical = 6.dp),
        )
    }
}
