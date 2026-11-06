package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal class ConflictDraft(val sources: ConflictSources) {
    var manual by mutableStateOf(false)
    var text by mutableStateOf(sources.working.orEmpty())
    var choices by mutableStateOf<Map<Int, Choice>>(emptyMap())
    val dirty get() = choices.isNotEmpty() || text != sources.working.orEmpty()
}

@Composable
internal fun ConflictEditor(sources: ConflictSources, draft: ConflictDraft, busy: Boolean, rebasing: Boolean,
    onReload: () -> Unit, onSave: (String) -> Unit, onWhole: (Boolean) -> Unit, modifier: Modifier) {
    var showBase by remember(sources.path) { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var wholeChoice by remember { mutableStateOf<Boolean?>(null) }
    val file = remember(draft.sources) { draft.sources.working?.let(::parseConflict) }
    val stale = draft.sources.signature != sources.signature
    val ours = if (rebasing) t("Rebased history", "已变基的历史") else t("Current version", "当前版本")
    val theirs = if (rebasing) t("Commit being replayed", "正在重放的提交") else t("Incoming version", "传入版本")
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = { showBase = !showBase }) { Text(t("Common base", "共同祖先")) }
            if (sources.textSupported) TextButton(enabled = !busy && !stale, onClick = {
                if (draft.manual && draft.dirty) confirmReset = true
                else if (draft.manual) draft.manual = false
                else {
                    if (file != null) draft.text = conflictDraftText(file, draft.choices)
                    draft.manual = true
                }
            }) { Text(if (draft.manual) t("Hunk choices", "逐块选择") else t("Edit result", "编辑结果")) }
            if (stale) Text(t("Inputs changed; reload before saving", "输入已变化，保存前请重新加载"), color = Palette.Modified, modifier = Modifier.weight(1f))
            if (stale) TextButton(onClick = { confirmReset = true }) { Text(t("Reload", "重新加载")) }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            listOf(true to sources.ours, false to sources.theirs).forEach { (useOurs, version) ->
                val exists = sources.stages.split('\u0000').any { it.substringBefore('\t').endsWith(if (useOurs) " 2" else " 3") }
                TextButton(enabled = !busy && !stale, onClick = { if (draft.dirty) wholeChoice = useOurs else onWhole(useOurs) }) {
                    Text((if (useOurs) ours else theirs) + if (!exists) t(" · delete file", " · 删除文件") else t(" · whole file", " · 整个文件"))
                }
            }
        }
        if (showBase) {
            Text(t("Common ancestor (read only)", "共同祖先（只读）"), fontSize = TypeScale.Small, modifier = Modifier.padding(horizontal = 12.dp))
            SourceLines(sources.base, Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp))
            HorizontalDivider()
        }
        if (!sources.textSupported) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
                Text(t("Binary, non-UTF-8 or large content: choose an entire version.", "二进制、非 UTF-8 或大文件：请选择完整版本。"))
            }
        } else if (draft.manual || file == null) {
            Row(Modifier.fillMaxWidth()) {
                listOf(ours, t("Editable result", "可编辑结果"), theirs).forEach { Text(it, fontSize = TypeScale.Small, modifier = Modifier.weight(1f).padding(8.dp)) }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                SourceLines(sources.ours, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                BasicTextField(draft.text, { draft.text = it }, enabled = !busy,
                    textStyle = TextStyle(color = Palette.Ink, fontFamily = CodeFont, fontSize = TypeScale.Small), cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.textInputFocus().weight(1f).fillMaxHeight().background(Palette.Field).verticalScroll(rememberScrollState()).padding(8.dp))
                VerticalDivider()
                SourceLines(sources.theirs, Modifier.weight(1f).fillMaxHeight())
            }
            val hasMarkers = Regex("(?m)^(<{7,}|>{7,}|={7,}|\\|{7,})(?: |$)").containsMatchIn(draft.text)
            Row(Modifier.padding(8.dp)) {
                if (hasMarkers) Text(t("Remove conflict markers before saving", "保存前请处理所有冲突标记"), color = Palette.Modified, fontSize = TypeScale.Small)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy && !stale && !hasMarkers, onClick = {
                    val text = editedLineEndings(draft.text, draft.sources.working)
                    onSave(text)
                }) { Text(t("Save and stage", "保存并标记已解决")) }
            }
        } else {
            HunkPane(sources.path, file, Modifier.fillMaxWidth().weight(1f), !busy && !stale,
                choices = draft.choices, onChoices = { draft.choices = it }, oursLabel = ours, theirsLabel = theirs, onSave = onSave)
        }
    }
    wholeChoice?.let { choice -> AlertDialog(onDismissRequest = { wholeChoice = null }, title = { Text(t("Replace the draft?", "替换当前草稿？")) },
        text = { Text(t("Use the selected whole-file version and discard this draft.", "使用所选完整版本，并放弃当前草稿。")) },
        confirmButton = { TextButton(enabled = !busy && !stale, onClick = { wholeChoice = null; onWhole(choice) }) { Text(t("Replace", "替换")) } },
        dismissButton = { TextButton(onClick = { wholeChoice = null }) { Text(t("Cancel", "取消")) } }) }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(t("Discard the current draft?", "放弃当前草稿？")) },
        text = { Text(t("Manual edits and hunk choices will be cleared.", "手动编辑和逐块选择将被清除。")) },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReload() }) { Text(t("Discard and reload", "放弃并重新加载")) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(t("Keep editing", "继续编辑")) } })
}

@Composable
private fun SourceLines(text: String?, modifier: Modifier) {
    val lines = remember(text) { text?.lines() }
    if (lines == null) Message(t("Version absent", "该版本不存在"), modifier)
    else LazyColumn(modifier.background(Palette.Panel).padding(8.dp)) {
        itemsIndexed(lines) { i, line -> Text("${i + 1}  ${line.trimEnd('\r')}", fontFamily = CodeFont, fontSize = TypeScale.Small, color = Palette.Muted, softWrap = false) }
    }
}
