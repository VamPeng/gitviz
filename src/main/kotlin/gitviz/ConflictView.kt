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

private const val ContextLines = 3 // 冲突块前后各留几行，太长的相同内容折叠

/** 解决冲突：左边是还没解决的文件，右边按块选择保留哪一边。 */
@Composable
internal fun ConflictView(
    cli: GitCli,
    files: List<WorkingFile>,
    operation: GitOperation?,
    onAbort: () -> Unit,
    onFinish: () -> Unit,
    onRun: OperationRunner,
    busy: Boolean,
    modifier: Modifier,
    revision: Int = 0,
    drafts: MutableMap<String, ConflictDraft>? = null,
    onSkip: () -> Unit = {},
    onKeepEmpty: () -> Unit = {},
) {
    val heldDrafts = drafts ?: remember(cli) { mutableStateMapOf<String, ConflictDraft>() }
    var selectedPath by remember(cli) { mutableStateOf<String?>(null) }
    var total by remember(cli, operation) { mutableStateOf(0) }
    total = maxOf(total, files.size)
    val current = files.firstOrNull { it.path == selectedPath } ?: files.firstOrNull()

    var progress by remember(cli) { mutableStateOf<RebaseProgress?>(null) }
    var progressError by remember(cli) { mutableStateOf<String?>(null) }
    LaunchedEffect(cli, operation, revision) {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { cli.rebaseProgress() } }
        result.onSuccess { progress = it; progressError = null }.onFailure { progress = null; progressError = it.message }
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
            if (operation == GitOperation.REBASE) {
                progress?.let { Text("${it.step ?: "?"} / ${it.total ?: "?"}", fontSize = TypeScale.Small) }
                BarButton(t("Skip commit", "跳过提交"), !busy, false, onSkip)
                if (progress?.canKeepEmpty == true) BarButton(t("Keep empty commit", "保留空提交"), !busy, false, onKeepEmpty)
            }
            if (operation != null) {
                BarButton(t("Abort", "放弃"), enabled = !busy, primary = false, onClick = onAbort)
                BarButton(operation.finish, enabled = !busy && files.isEmpty(), primary = true, onClick = onFinish)
            }
        }
        progressError?.let { Text(it, color = Palette.Modified, fontSize = TypeScale.Small, modifier = Modifier.padding(12.dp)) }
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
                Message(if (operation == GitOperation.REBASE) t("Rebase paused. Continue with the staged result, keep an empty commit, skip, or abort.", "变基已暂停：使用暂存结果继续、保留空提交、跳过或中止。") else if (operation != null) t("All conflicts resolved. Click “${operation.finish}” to finish.", "冲突都解决了，点「${operation.finish}」结束") else t("All conflicts resolved", "冲突都解决了"), pane)
            } else {
                ConflictLoaded(cli, current.path, revision, pane) { sources ->
                    val draft = heldDrafts.getOrPut(current.path) { ConflictDraft(sources) }
                    ConflictEditor(sources, draft, busy, operation?.name == "REBASE",
                        onReload = { heldDrafts[current.path] = ConflictDraft(sources) },
                        onSave = { text ->
                            onRun(t("Saving resolution", "正在保存冲突处理"), t("Resolution saved", "已保存冲突处理"), {
                                resolveFile(current.path, text, sources.signature)
                            }) { ok -> if (ok) heldDrafts.remove(current.path) }
                        },
                        onWhole = { ours ->
                            onRun(t("Saving resolution", "正在保存冲突处理"), t("Resolution saved", "已保存冲突处理"), {
                                resolveWhole(current.path, ours, sources.signature)
                            }) { ok -> if (ok) heldDrafts.remove(current.path) }
                        }, modifier = pane)
                }
            }
        }
    }
}

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

@Composable
internal fun HunkPane(path: String, file: ConflictFile, modifier: Modifier, enabled: Boolean,
    choices: Map<Int, Choice>, onChoices: (Map<Int, Choice>) -> Unit, oursLabel: String, theirsLabel: String, onSave: (String) -> Unit) {
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
            ColumnTitle("$oursLabel · ${file.oursLabel}", Palette.Link, Modifier.weight(1f))
            ColumnTitle(t("Result", "合并结果"), Palette.Ink, Modifier.weight(1f))
            ColumnTitle("$theirsLabel · ${file.theirsLabel}", Palette.AccentInk, Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(indexed) { position, (part, hunkIndex) ->
                when (part) {
                    is SameLines -> SameBlock(part.lines, isFirst = position == 0, isLast = position == indexed.lastIndex)
                    is ConflictHunk -> HunkBlock(part, choices[hunkIndex], enabled, oursLabel, theirsLabel) { onChoices(choices + (hunkIndex to it)) }
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
            BarButton(t("Save file", "保存这个文件"), enabled = enabled && choices.size == hunkCount, primary = true) { onSave(resolveConflict(file, choices)) }
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
private fun HunkBlock(hunk: ConflictHunk, choice: Choice?, enabled: Boolean, oursLabel: String, theirsLabel: String, onChoose: (Choice) -> Unit) {
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
                listOf(Choice.OURS to oursLabel, Choice.THEIRS to theirsLabel, Choice.BOTH to t("Keep both", "两边都保留")),
                choice, onSelect = { picked -> if (enabled) picked?.let(onChoose) },
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

@Composable
private fun ConflictLoaded(cli: GitCli, path: String, revision: Int, modifier: Modifier, content: @Composable (ConflictSources) -> Unit) {
    var result by remember(cli, path) { mutableStateOf<Result<ConflictSources>?>(null) }
    LaunchedEffect(cli, path, revision) {
        val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { cli.conflictSources(path) } }
        if (next.getOrNull()?.signature != result?.getOrNull()?.signature || next.isFailure) result = next
    }
    val value = result
    when {
        value == null -> Message(t("Reading conflict…", "正在读取冲突…"), modifier)
        value.isFailure -> Message(value.exceptionOrNull()?.message.orEmpty(), modifier)
        else -> content(value.getOrThrow())
    }
}
