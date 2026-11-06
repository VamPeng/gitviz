package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

@Composable
internal fun PreviewDialog(cli: GitCli, repo: GitRepo, intent: OperationIntent, busy: Boolean,
    onDismiss: () -> Unit, onExecute: (OperationPlan) -> Unit) {
    var generation by remember { mutableStateOf(0) }
    var mode by remember(intent) { mutableStateOf(intent.mode) }
    var mainline by remember(intent) { mutableStateOf(intent.mainline) }
    var data by remember(intent, generation, mode, mainline) { mutableStateOf<Result<OperationPlan>?>(null) }
    var file by remember(intent, generation, mode, mainline) { mutableStateOf<PreviewFile?>(null) }
    var showAfter by remember { mutableStateOf(true) }
    var showExpected by remember { mutableStateOf(false) }
    LaunchedEffect(cli, intent, generation, mode, mainline) {
        try {
            data = Result.success(runInterruptible(Dispatchers.IO) { cli.planOperation(intent.copy(mode = mode, mainline = mainline)) })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { data = Result.failure(e) }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.size(1060.dp, 740.dp).background(Palette.Panel).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Operation preview", "操作预览") + " · ${intent.kind}", fontSize = TypeScale.Title)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { generation++ }, enabled = !busy) { Text(t("Preview again", "重新预览")) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(t("Close", "关闭")) }
            }
            if (intent.kind == PreviewKind.RESET) Segmented(ResetMode.entries.map { it to it.label }, mode, onSelect = { mode = it ?: mode })
            val plan = data?.getOrNull()
            if (plan != null && intent.kind == PreviewKind.CHERRY_PICK && plan.parentCount > 1) Row {
                Text(t("Mainline parent", "主父提交"), fontSize = TypeScale.Small)
                Segmented((1..plan.parentCount).map { it to "#$it" }, mainline, onSelect = { mainline = it ?: mainline })
            }
            when {
                data == null -> Message(t("Rehearsing in an isolated repository… Close to cancel.", "正在独立仓库演练… 关闭可取消。"), Modifier.weight(1f).fillMaxWidth())
                data!!.isFailure -> Message(data!!.exceptionOrNull()?.message.orEmpty(), Modifier.weight(1f).fillMaxWidth())
                plan != null -> {
                    val label = when { plan.verified -> t("Rehearsal completed", "演练完成"); plan.paused -> t("Verified only up to the first conflict / pause", "只验证到首个冲突／暂停点"); else -> t("Result not fully verified", "结果未完整验证") }
                    Text(label, color = if (plan.verified) Palette.Added else Palette.Modified, fontSize = TypeScale.Body)
                    Text("HEAD: ${plan.beforeHead.take(8)} → ${plan.afterHead.removePrefix("preview:").take(8)} · ${plan.branch ?: "detached"} · +${plan.added.size} / −${plan.removed.size}", fontSize = TypeScale.Small)
                    if (intent.kind == PreviewKind.RESET) Text(mode.hint, fontSize = TypeScale.Small)
                    Text(t("Index: ${if (plan.indexChanged) "changes" else "unchanged"}; working tree: ${plan.workingImpact.size} affected paths", "暂存区：${if (plan.indexChanged) "改变" else "不变"}；工作区：${plan.workingImpact.size} 个受影响路径"), fontSize = TypeScale.Small)
                    Column(Modifier.fillMaxWidth().heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
                        plan.warnings.forEach { Text(it, color = Palette.Modified, fontSize = TypeScale.Small) }
                        if (plan.outcome.output.isNotBlank()) Text(plan.outcome.output, fontSize = TypeScale.Small, fontFamily = CodeFont)
                        if (plan.workingImpact.isNotEmpty()) Text(plan.workingImpact.joinToString(" · "), fontSize = TypeScale.Small)
                        if (plan.projected.isNotEmpty()) Text(t("Expected, not yet verified:\n", "预计，尚未验证：\n") + plan.projected.joinToString("\n"), fontSize = TypeScale.Small, color = Palette.Modified)
                    }
                    Row {
                        TextButton(onClick = { showAfter = false; showExpected = false }) { Text(t("Before", "操作前")) }
                        TextButton(onClick = { showAfter = true; showExpected = false }) { Text(t("Rehearsal graph (independent layout)", "演练图（独立布局）")) }
                        if (plan.expected.isNotEmpty()) TextButton(onClick = { showAfter = true; showExpected = true; file = null }) { Text(t("Expected topology (unverified)", "预计拓扑（未验证）")) }
                        if (file != null) TextButton(onClick = { file = null }) { Text(t("Back to graph", "返回图形")) }
                    }
                    HorizontalDivider()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.width(230.dp).fillMaxHeight()) {
                            items(plan.files) { f -> Text(f.path, fontSize = TypeScale.Small, modifier = Modifier.fillMaxWidth().clickable { file = f }.padding(8.dp)) }
                        }
                        VerticalDivider()
                        val selectedFile = file
                        if (selectedFile != null) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            Text(selectedFile.diff, fontFamily = CodeFont, fontSize = TypeScale.Small)
                        } else {
                            val commits = if (showExpected) (plan.expected + plan.after).take(200) else if (showAfter) plan.after else plan.before
                            val graph = remember(plan, showAfter, showExpected) { RepoState.create(repo, LoadResult(commits, false, plan.branch, commits.flatMap { c -> c.refs.map { RefTarget(it, c.id) } }), if (showAfter) plan.afterHead else plan.beforeHead) }
                            CommitList(graph, commits.indices.toList(), null, rememberLazyListState(), {}, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    if (showExpected) Text(t("Projection assumes resolving each pause and keeping each commit. File results after the first pause have not been verified.", "预计拓扑假设逐次解决暂停并保留提交；首个暂停点后的文件结果尚未验证。"), color = Palette.Modified, fontSize = TypeScale.Small)
                    Text(t("Graph shows up to 200 commits. Simulated IDs are not real execution IDs. Local state is saved before execution.", "图形最多展示 200 条提交；模拟 ID 不是真实执行 ID。执行前会保存本地恢复点。"), fontSize = TypeScale.Small, color = Palette.Muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy && plan.outcome.failure != GitFailure.UNKNOWN, onClick = { onExecute(plan) }) { Text(t("Execute reviewed operation", "执行已预览操作")) }
                    }
                }
            }
        }
    }
}
