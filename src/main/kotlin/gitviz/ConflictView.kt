package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ContextLines = 3 // 冲突块前后各留几行，太长的相同内容折叠

/** 解决冲突：左边是还没解决的文件，右边按块选择保留哪一边。 */
@Composable
internal fun ConflictView(
    cli: GitCli,
    files: List<WorkingFile>,
    operation: GitOperation?,
    onAbort: () -> Unit,
    onFinish: () -> Unit,
    onChanged: () -> Unit,
    onFailure: (String, String) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedPath by remember(cli) { mutableStateOf<String?>(null) }
    var total by remember(cli, operation) { mutableStateOf(0) }
    total = maxOf(total, files.size)
    val current = files.firstOrNull { it.path == selectedPath } ?: files.firstOrNull()

    fun apply(op: GitCli.() -> GitResult) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { cli.op() }
            if (!result.ok) onFailure(t("Save failed", "保存失败"), errorSummary(result.output))
            onChanged()
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(operation?.running ?: t("Conflicts", "有冲突"), fontSize = TypeScale.Heading, fontWeight = FontWeight.SemiBold)
            Text(t("Resolved ${total - files.size} / $total", "已解决 ${total - files.size} / $total"), fontSize = TypeScale.Small, color = Palette.Muted)
            Spacer(Modifier.weight(1f))
            if (operation != null) {
                BarButton(t("Abort", "放弃"), enabled = true, primary = false, onClick = onAbort)
                BarButton(operation.finish, enabled = files.isEmpty(), primary = true, onClick = onFinish)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ScrollList(Modifier.width(248.dp).fillMaxHeight().background(Palette.Rail)) {
                items(files, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().height(32.dp).hoverRow().rowHighlight(f == current)
                            .clickable { selectedPath = f.path }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChangeBadge("!", Palette.Modified)
                        Text(f.path.substringAfterLast('/'), fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            VerticalDivider()
            val pane = Modifier.weight(1f).fillMaxHeight().background(Palette.Panel)
            if (current == null) {
                Message(if (operation != null) t("All conflicts resolved. Click “${operation.finish}” to finish.", "冲突都解决了，点「${operation.finish}」结束") else t("All conflicts resolved", "冲突都解决了"), pane)
            } else {
                Loaded(current, pane, { cli.readWorkingFile(current.path)?.let(::parseConflict) ?: Unparsed }) { content ->
                    if (content is ConflictFile) {
                        HunkPane(current.path, content, pane) { text -> apply { resolveFile(current.path, text) } }
                    } else {
                        WholeFilePane(current, pane) { ours -> apply { resolveWhole(current.path, ours) } }
                    }
                }
            }
        }
    }
}

private object Unparsed

@Composable
private fun BarButton(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        label, fontSize = TypeScale.Small, maxLines = 1, color = if (primary) Palette.OnAccent else Palette.Ink,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f).clip(shape)
            .background(if (primary) Palette.Accent else Color.Transparent, shape)
            .then(if (primary) Modifier else Modifier.border(1.dp, Palette.Line, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** 删除对修改、二进制文件这些没有冲突标记的情况，只能整个文件选一边。 */
@Composable
private fun WholeFilePane(file: WorkingFile, modifier: Modifier, onPick: (ours: Boolean) -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(file.path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted)
            Text(t("This file can't be merged hunk by hunk. Choose which side to keep.", "这个文件不能按块合并，选择保留哪一边的版本。"), fontSize = TypeScale.Body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton(t("Use current", "用当前分支"), enabled = true, primary = false) { onPick(true) }
                BarButton(t("Use incoming", "用传入分支"), enabled = true, primary = false) { onPick(false) }
            }
        }
    }
}

@Composable
private fun HunkPane(path: String, file: ConflictFile, modifier: Modifier, onSave: (String) -> Unit) {
    var choices by remember(path, file) { mutableStateOf(emptyMap<Int, Choice>()) }
    val hunkCount = file.hunks.size
    val indexed = remember(file) {
        var next = 0
        file.parts.map { part -> part to if (part is ConflictHunk) next++ else -1 }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColumnTitle(t("Current ${file.oursLabel}", "当前 ${file.oursLabel}"), Palette.Link, Modifier.weight(1f))
            ColumnTitle(t("Result", "合并结果"), Palette.Ink, Modifier.weight(1f))
            ColumnTitle(t("Incoming ${file.theirsLabel}", "传入 ${file.theirsLabel}"), Palette.AccentInk, Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(indexed) { position, (part, hunkIndex) ->
                when (part) {
                    is SameLines -> SameBlock(part.lines, isFirst = position == 0, isLast = position == indexed.lastIndex)
                    is ConflictHunk -> HunkBlock(part, choices[hunkIndex]) { choices = choices + (hunkIndex to it) }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(t("${choices.size} / $hunkCount hunks chosen in this file", "这个文件 ${choices.size} / $hunkCount 块已选择"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            BarButton(t("Save file", "保存这个文件"), enabled = choices.size == hunkCount, primary = true) { onSave(resolveConflict(file, choices)) }
        }
    }
}

@Composable
private fun ColumnTitle(text: String, color: Color, modifier: Modifier) {
    Text(text, fontSize = TypeScale.Small, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier.padding(start = 14.dp))
}

@Composable
private fun CodeLine(text: String, color: Color) {
    Text(text.trimEnd('\r').ifEmpty { " " }, fontFamily = CodeFont, fontSize = TypeScale.Small, lineHeight = 18.sp, color = color,
        softWrap = false, maxLines = 1)
}

@Composable
private fun SameBlock(lines: List<String>, isFirst: Boolean, isLast: Boolean) {
    val head = if (isFirst) emptyList() else lines.take(ContextLines)
    val tail = if (isLast) emptyList() else lines.takeLast(ContextLines)
    val fold = lines.size > head.size + tail.size
    val shown = if (fold) head + tail else lines
    if (fold) Text(t("⋯ ${lines.size - shown.size} lines hidden", "⋯ 省略 ${lines.size - shown.size} 行"), fontSize = TypeScale.Caption, color = Palette.Faint, modifier = Modifier.padding(start = 14.dp))
    for (line in shown) {
        Row(Modifier.fillMaxWidth()) {
            repeat(3) {
                Box(Modifier.weight(1f).padding(start = 14.dp).clipToBounds()) { CodeLine(line, Palette.Muted) }
            }
        }
    }
}

@Composable
private fun HunkBlock(hunk: ConflictHunk, choice: Choice?, onChoose: (Choice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            CodeCell(hunk.ours, Palette.Link, Modifier.weight(1f))
            if (choice == null) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Palette.Modified.copy(alpha = 0.14f)).padding(start = 14.dp)) {
                    Text(t("Unresolved: choose below", "未解决：在下方选择"), fontSize = TypeScale.Small, lineHeight = 18.sp, color = Palette.Modified, maxLines = 1)
                }
            } else {
                CodeCell(hunk.lines(choice), Palette.Ink, Modifier.weight(1f))
            }
            CodeCell(hunk.theirs, Palette.AccentInk, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Segmented(
                listOf(Choice.OURS to t("Use current", "用当前分支"), Choice.THEIRS to t("Use incoming", "用传入分支"), Choice.BOTH to t("Keep both", "两边都保留")),
                choice, onSelect = { picked -> picked?.let(onChoose) },
            )
        }
    }
}

@Composable
private fun CodeCell(lines: List<String>, color: Color, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(color.copy(alpha = 0.09f)).padding(start = 14.dp).clipToBounds()) {
        if (lines.isEmpty()) Text(t("(empty)", "（空）"), fontSize = TypeScale.Small, lineHeight = 18.sp, color = Palette.Faint)
        for (line in lines) CodeLine(line, color)
    }
}
