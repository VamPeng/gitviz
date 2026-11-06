package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RecoveryView(cli: GitCli, actions: GitActions, revision: Int, busy: Boolean, modifier: Modifier) {
    var refresh by remember(cli) { mutableStateOf(0) }
    var selected by remember(cli) { mutableStateOf<String?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Local recovery", "本地恢复"), fontSize = TypeScale.Heading)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { actions.run(t("Undoing", "正在撤销"), t("Undone", "已撤销")) { undo() } }) { Text(t("Undo latest", "撤销最近操作")) }
            TextButton(onClick = { refresh++ }) { Text(t("Refresh", "刷新")) }
        }
        Loaded(Triple(cli, revision, refresh), Modifier.fillMaxWidth().weight(1f), {
            val store = cli.recovery
            RecoveryViewData(store.records(), store.retentionDays, store.quotaBytes, store.size())
        }) { data ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("${data.days} days · ${data.used / 1048576} / ${data.quota / 1048576} MiB", "${data.days} 天 · ${data.used / 1048576} / ${data.quota / 1048576} MiB"), fontSize = TypeScale.Small, color = Palette.Muted)
                TextButton(enabled = !busy, onClick = {
                    actions.ask(DialogSpec(t("Recovery quota (GiB)", "恢复配额（GiB）"), t("Active operations are never automatically removed.", "活动操作不会自动清理。"), t("Save", "保存"), input = "GiB", initial = (data.quota / (1024L * 1024 * 1024)).toString()) { text, _ ->
                        val gib = text.toLongOrNull()?.takeIf { it in 1..1024 } ?: return@DialogSpec
                        actions.run(t("Saving settings", "正在保存设置"), t("Settings saved", "设置已保存")) { configureRecovery(data.days, gib * 1024 * 1024 * 1024) }
                    })
                }) { Text(t("Quota", "配额")) }
                TextButton(enabled = !busy, onClick = {
                    actions.ask(DialogSpec(t("Retention days", "保留天数"), "", t("Save", "保存"), input = t("Days", "天数"), initial = data.days.toString()) { text, _ ->
                        val days = text.toIntOrNull()?.takeIf { it in 1..36500 } ?: return@DialogSpec
                        actions.run(t("Saving settings", "正在保存设置"), t("Settings saved", "设置已保存")) { configureRecovery(days, data.quota) }
                    })
                }) { Text(t("Retention", "保留时间")) }
                TextButton(enabled = !busy, onClick = {
                    actions.ask(DialogSpec(t("Clear completed recovery points", "清理已完成的恢复点"), t("These local recovery points will be permanently removed. Active operations are kept.", "这些本地恢复点将永久删除，活动操作保留。"), t("Clear", "清理"), danger = true) { _, _ ->
                        actions.run(t("Cleaning recovery points", "正在清理恢复点"), t("Recovery points cleaned", "恢复点已清理")) { configureRecovery(data.days, data.quota, clear = true) }
                    })
                }) { Text(t("Clear", "清理")) }
            }
            HorizontalDivider()
            if (data.records.isEmpty()) Message(t("No local operations recorded yet", "还没有本地操作记录"), Modifier.weight(1f).fillMaxWidth())
            else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(data.records, key = { it.id }) { record ->
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(record.label, fontSize = TypeScale.Body)
                        if (record.requiresReview) Text(t("Review impact before restoring this operation", "此操作恢复前需查看影响"), fontSize = TypeScale.Small, color = Palette.Modified)
                        Text("${formatTime(record.time / 1000)} · ${record.state.label}", fontSize = TypeScale.Small, color = Palette.Muted)
                        if (record.detail.isNotBlank() && record.state in setOf(RecordState.NEEDS_RECOVERY, RecordState.RESTORING)) Text(record.detail, color = Palette.Modified, fontSize = TypeScale.Small)
                        Row {
                            if (record.before.operation == null && record.state != RecordState.UNDONE) TextButton(enabled = !busy, onClick = { selected = record.id }) { Text(t("Review recovery…", "查看恢复影响…")) }
                            if (record.state in setOf(RecordState.RUNNING, RecordState.PREPARED, RecordState.NEEDS_RECOVERY)) TextButton(enabled = !busy, onClick = {
                                actions.run(t("Inspecting", "正在核对"), t("State recorded", "状态已记录")) { inspectPoint(record.id) }
                            }) { Text(t("Inspect current state", "核对当前状态")) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        selected?.let { id ->
            Loaded(Triple(cli, id, revision), Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp), { cli.recovery.preview(id) }) { preview ->
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(t("Restore affects ${preview.files.size} paths and ${preview.refs.size} references", "恢复将影响 ${preview.files.size} 个路径和 ${preview.refs.size} 个引用"), fontSize = TypeScale.Body)
                    Column(Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())) {
                        if (preview.indexChanged) Text(t("Index contents will change", "暂存区内容将改变"), fontSize = TypeScale.Small)
                        if (preview.configKeys.isNotEmpty()) Text(t("Configuration: ", "配置：") + preview.configKeys.joinToString(), fontSize = TypeScale.Small)
                        if (preview.oldStash != preview.newStash) {
                            Text("Stash: ${preview.oldStash.size} → ${preview.newStash.size}", fontSize = TypeScale.Small)
                            Text(preview.newStash.joinToString("\n") { "${it.first.take(8)} ${it.second}" }, fontSize = TypeScale.Small)
                        }
                        Text((preview.files + preview.refs).joinToString("\n"), fontSize = TypeScale.Small, color = Palette.Muted)
                    }
                    Text("HEAD: ${preview.oldHead} → ${preview.newHead}", fontSize = TypeScale.Small)
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            actions.ask(DialogSpec(t("Restore local state", "恢复本地状态"), t("Replace the listed local state with this recovery point. Your current state will be saved first. Remote repositories are unchanged.", "将列出的本地状态恢复到此恢复点，先保存当前状态。远程仓库不受影响。"), t("Restore", "恢复"), danger = true) { _, _ ->
                                actions.run(t("Restoring", "正在恢复"), t("Restored", "已恢复")) { restorePoint(id, preview.fingerprint) }
                                selected = null
                            })
                        }) { Text(t("Restore…", "恢复…")) }
                        TextButton(onClick = { selected = null }) { Text(t("Cancel", "取消")) }
                    }
                }
            }
        }
    }
}
private data class RecoveryViewData(val records: List<OperationRecord>, val days: Int, val quota: Long, val used: Long)
internal val RecordState.label: String get() = when (this) {
    RecordState.PREPARED, RecordState.RUNNING -> t("Interrupted / inspect", "未完成／待核对")
    RecordState.CONFLICTED -> t("Awaiting resolution", "等待解决冲突")
    RecordState.COMPLETED -> t("Completed", "已完成")
    RecordState.RESTORING -> t("Restore interrupted", "恢复未完成")
    RecordState.NEEDS_RECOVERY -> t("Needs inspection", "需要核对")
    RecordState.UNDONE -> t("Restored", "已恢复")
    RecordState.CHILD -> t("Part of an operation", "操作中的步骤")
}
