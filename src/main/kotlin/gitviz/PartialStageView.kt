package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class DiffSelection(val selected: (DiffLine) -> Boolean, val toggle: (DiffLine) -> Unit)

@Composable
internal fun PartialStageView(cli: GitCli, file: WorkingFile, staged: Boolean, revision: Int, mode: DiffMode, busy: Boolean,
    onApply: (PatchDocument, Set<Int>) -> Unit, modifier: Modifier) {
    StablePatchLoaded(cli, file, staged, revision, modifier) { document ->
        var selected by remember(document.signature) { mutableStateOf(emptySet<Int>()) }
        val focus = remember { FocusRequester() }
        val rowIndex = remember(document.signature) { document.lines.withIndex().associate { it.value to it.index } }
        val selection = if (document.unavailable != null || busy) null else DiffSelection(
            selected = { line -> rowIndex[line] in selected },
            toggle = { line ->
                focus.requestFocus()
                val i = rowIndex[line] ?: -1
                val ids = if (line.kind == DiffLineKind.HUNK) document.hunk(i) else setOf(i).intersect(document.changes)
                selected = if (selected.containsAll(ids)) selected - ids else selected + ids
            })
        Column(modifier.focusRequester(focus).onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && selected.isNotEmpty()) { selected = emptySet(); true } else false }.focusable()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(document.unavailable ?: t("Click a hunk header or +/− lines to select", "点击 hunk 标题或 +/− 行进行选择"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
                if (document.unavailable == null) {
                    TextButton(enabled = !busy, onClick = { selected = if (selected == document.changes) emptySet() else document.changes }) { Text(t("All / clear", "全选／清除")) }
                    TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { onApply(document, selected) }) {
                        Text(if (staged) t("Unstage ${selected.size} lines", "取消暂存 ${selected.size} 行") else t("Stage ${selected.size} lines", "暂存 ${selected.size} 行"))
                    }
                }
            }
            DiffBody(document.lines, mode, Modifier.fillMaxWidth().weight(1f), selection, document.sources)
        }
    }
}

@Composable
private fun StablePatchLoaded(cli: GitCli, file: WorkingFile, staged: Boolean, revision: Int, modifier: Modifier, content: @Composable (PatchDocument) -> Unit) {
    var data by remember(cli, file.path, staged) { mutableStateOf<Result<PatchDocument>?>(null) }
    LaunchedEffect(cli, file, staged, revision) {
        val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { cli.patchDocument(file, staged) } }
        if (next.isFailure || next.getOrNull()?.signature != data?.getOrNull()?.signature) data = next
    }
    val current = data
    when { current == null -> Message(t("Reading patch…", "正在读取补丁…"), modifier)
        current.isFailure -> Message(current.exceptionOrNull()?.message.orEmpty(), modifier)
        else -> content(current.getOrThrow()) }
}
