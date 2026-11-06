package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable internal fun InteractiveRebaseDialog(cli: GitCli, initialBase: String, busy: Boolean, onDismiss: () -> Unit, onPreview: (OperationIntent) -> Unit) {
    var base by remember(cli) { mutableStateOf(initialBase) }
    var requested by remember(cli) { mutableStateOf(initialBase to 0) }
    var source by remember(cli, requested) { mutableStateOf<Result<InteractiveRebasePlan>?>(null) }
    var edits by remember(cli, requested) { mutableStateOf(emptyList<RebaseEdit>()) }
    var actionMenu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cli, requested) {
        try {
            val range = runInterruptible(Dispatchers.IO) { cli.interactiveRange(requested.first) }
            source = Result.success(range); edits = range.edits
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { source = Result.failure(e) }
    }
    val original = source?.getOrNull()
    val plan = original?.copy(edits = edits)
    val error = plan?.let { runCatching { it.todo() }.exceptionOrNull()?.message }
    fun change(id: String, transform: (RebaseEdit) -> RebaseEdit) { edits = edits.map { if (it.id == id) transform(it) else it } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.size(980.dp, 720.dp).background(Palette.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Interactive rebase", "交互式变基"), fontSize = TypeScale.Title, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text(t("Close", "关闭")) }
            }
            Text(t("Edit commits after the base, oldest first. Only a clean current local branch and a linear range are supported. Published commits will get new IDs; no push is performed.", "编辑基准之后的提交，最早的排在前面。仅支持干净工作区、当前本地分支和线性区间；已发布提交会产生新 ID，不会自动推送。"), fontSize = TypeScale.Small, color = Palette.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(base, { base = it }, label = { Text(t("Base (excluded)", "基准（不包含）")) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f).textInputFocus())
                TextButton(enabled = !busy && base.isNotBlank(), onClick = { requested = base.trim() to (requested.second + 1) }) { Text(t("Load range", "加载区间")) }
            }
            when {
                source == null -> Message(t("Loading commits…", "正在加载提交…"), Modifier.fillMaxWidth().weight(1f))
                source!!.isFailure -> Message(source!!.exceptionOrNull()?.message.orEmpty(), Modifier.fillMaxWidth().weight(1f))
                original != null -> {
                    Text("${original.base.take(8)} → ${original.head.substringAfter('|').take(8)} · ${edits.size} " + t("commits", "个提交"), fontSize = TypeScale.Small)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(edits, key = { _, edit -> edit.id }) { index, edit ->
                            val commit = original.commits.first { it.id == edit.id }
                            Column(Modifier.fillMaxWidth().background(Palette.Ground).padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(enabled = !busy && index > 0, onClick = { edits = edits.toMutableList().apply { add(index - 1, removeAt(index)) } }) { Text("↑") }
                                    TextButton(enabled = !busy && index < edits.lastIndex, onClick = { edits = edits.toMutableList().apply { add(index + 1, removeAt(index)) } }) { Text("↓") }
                                    TextButton(enabled = !busy, onClick = { actionMenu = edit.id }) { Text(edit.action.name.lowercase()) }
                                    Text(commit.shortId, fontFamily = CodeFont, fontSize = TypeScale.Small, modifier = Modifier.padding(end = 10.dp))
                                    Text(commit.shortMessage, fontSize = TypeScale.Body, color = if (edit.action == RebaseAction.DROP) Palette.Removed else Palette.Ink, modifier = Modifier.weight(1f))
                                }
                                if (edit.action == RebaseAction.REWORD) OutlinedTextField(edit.message, { message -> change(edit.id) { it.copy(message = message) } }, enabled = !busy, label = { Text(t("New commit message", "新的提交说明")) }, modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).textInputFocus())
                            }
                        }
                    }
                    Text(t("squash combines messages; fixup keeps the preceding message; drop removes the commit. Move rows with ↑/↓.", "squash 合并提交说明；fixup 保留前一个说明；drop 丢弃提交。用 ↑/↓ 调整顺序。"), fontSize = TypeScale.Small, color = Palette.Muted)
                    error?.let { Text(it, color = Palette.Removed, fontSize = TypeScale.Small) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy && error == null && base.trim() == requested.first, onClick = {
                            val reviewed = checkNotNull(plan)
                            onPreview(OperationIntent(PreviewKind.REBASE, reviewed.base, expectedHead = reviewed.head, interactive = reviewed))
                        }) { Text(t("Preview edited history", "预览编辑后的历史")) }
                    }
                }
            }
        }
        actionMenu?.let { id -> GitMenuPopup(RebaseAction.entries.map { action -> MenuItem(action.name.lowercase()) { change(id) { it.copy(action = action) } } }, { actionMenu = null }) }
    }
}
