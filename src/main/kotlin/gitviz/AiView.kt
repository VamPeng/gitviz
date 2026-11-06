package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

private class AiGeneration(val context: AiContext, val request: AiModelRequest, val token: String)

@Composable internal fun AiView(cli: GitCli, scope: AiScope, onScope: (AiScope) -> Unit, connection: AiConnection,
                               draft: CommitDraft, actions: GitActions, revision: Int, busy: Boolean, onDraft: () -> Unit, modifier: Modifier) {
    var configure by remember { mutableStateOf(true) }
    var refresh by remember(cli, scope) { mutableStateOf<Int?>(null) }
    var context by remember(cli, scope) { mutableStateOf<AiContext?>(null) }
    var input by remember(cli, scope) { mutableStateOf("") }
    var output by remember(cli, scope) { mutableStateOf("") }
    var error by remember(cli, scope) { mutableStateOf<String?>(null) }
    var preparing by remember(cli, scope) { mutableStateOf(false) }
    var generating by remember(cli, scope) { mutableStateOf(false) }
    var current by remember(cli, scope) { mutableStateOf(false) }
    var generation by remember(cli, scope) { mutableStateOf<AiGeneration?>(null) }
    var confirmSend by remember(cli, scope, context, input, connection.endpoint, connection.model, connection.protocol) { mutableStateOf(false) }
    val tasks = rememberCoroutineScope()
    LaunchedEffect(cli, scope, refresh) {
        if (refresh == null) return@LaunchedEffect
        preparing = true; error = null; context = null; input = ""; output = ""
        try { context = runInterruptible(Dispatchers.IO) { cli.prepareAiContext(scope) }; input = context!!.text }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { preparing = false }
    }
    LaunchedEffect(cli, context, revision, output) {
        current = context?.let { snapshot -> runInterruptible(Dispatchers.IO) { cli.isAiContextCurrent(snapshot) } } ?: false
    }
    LaunchedEffect(cli, scope, generation) {
        val job = generation ?: return@LaunchedEffect
        generating = true; error = null; output = ""
        try {
            output = runInterruptible(Dispatchers.IO) {
                check(cli.isAiContextCurrent(job.context)) { t("Snapshot changed. Reload before sending.", "快照已变化，请重新加载后发送。") }
                generateAiText(job.request, job.token)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { generating = false }
    }
    val occupied = busy || preparing || generating
    val clipboard = LocalClipboardManager.current
    val valid = connection.enabled && runCatching { serviceUri(connection.endpoint); require(connection.model.isNotBlank()) }.isSuccess
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("AI assistance", "AI 辅助"), fontSize = TypeScale.Heading, modifier = Modifier.weight(1f))
            Checkbox(connection.enabled, { connection.enabled = it }, enabled = !generating)
            Text(t("Enable for this session", "本次会话启用"), fontSize = TypeScale.Small)
            TextButton(onClick = { configure = !configure }) { Text(t("Connection", "连接配置")) }
        }
        Text(t("Generation sends the reviewed text to your configured service and may incur fees. No automatic upload, staging or commit. Connection and token stay in app memory.", "生成时会向配置的服务发送已检查的文本，可能产生费用。不自动上传、暂存或提交。连接信息和令牌只保留在应用内存。"), fontSize = TypeScale.Small, color = Palette.Muted)
        if (configure) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf(AiProtocol.RESPONSES to "Responses API", AiProtocol.CHAT_COMPLETIONS to "Chat Completions"), connection.protocol) { if (!generating) connection.protocol = it }
                OutlinedTextField(connection.model, { connection.model = it }, label = { Text(t("Model", "模型")) }, singleLine = true, enabled = !generating, modifier = Modifier.weight(1f).textInputFocus())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(connection.endpoint, { connection.endpoint = it; connection.token = "" }, label = { Text(t("Full request URL", "完整请求 URL")) }, placeholder = { Text("https://api.openai.com/v1/responses") }, singleLine = true, enabled = !generating, modifier = Modifier.weight(1f).textInputFocus())
                OutlinedTextField(connection.token, { connection.token = it }, label = { Text(t("API key (optional for local models)", "API 密钥（本地模型可留空）")) }, singleLine = true, enabled = !generating, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f).textInputFocus())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !occupied, onClick = { onScope(AiScope()) }) { Text(t("Commit draft", "提交草稿"), color = if (scope.kind == AiKind.COMMIT) Palette.Accent else Palette.Muted) }
            TextButton(enabled = !occupied, onClick = { onScope(AiScope(AiKind.BRANCH)) }) { Text(t("Branch summary", "分支摘要"), color = if (scope.kind == AiKind.BRANCH) Palette.Accent else Palette.Muted) }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !occupied, onClick = { refresh = (refresh ?: 0) + 1; generation = null }) { Text(t("Prepare / reload snapshot", "准备／重载快照")) }
            if (preparing || generating) TextButton(onClick = { refresh = null; generation = null }) { Text(t("Cancel", "取消")) }
        }
        if (scope.kind == AiKind.BRANCH) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(scope.leftLabel, { onScope(scope.copy(left = it, leftLabel = it)) }, singleLine = true, label = { Text(t("Base A", "基准 A")) }, enabled = !occupied, modifier = Modifier.weight(1f).textInputFocus())
            OutlinedTextField(scope.rightLabel, { onScope(scope.copy(right = it, rightLabel = it)) }, singleLine = true, label = { Text(t("Target B", "目标 B")) }, enabled = !occupied, modifier = Modifier.weight(1f).textInputFocus())
        }
        context?.let { snapshot ->
            Text(snapshot.notice + if (snapshot.partial) t(" · Partial snapshot; review omissions below", " · 快照不完整，请检查下方省略说明") else "", fontSize = TypeScale.Small, color = if (snapshot.partial) Palette.Modified else Palette.Muted)
            if (!current) Text(t("References or index moved. This text describes the old snapshot; reload before generating or using a draft.", "引用或暂存区已变化；文本对应旧快照，生成或使用草稿前请重新加载。"), fontSize = TypeScale.Small, color = Palette.Modified)
        }
        if (preparing || generating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent)
        error?.let { Text(it, color = Palette.Removed, fontSize = TypeScale.Small) }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("Review text to send · ${input.length} characters", "检查待发送文本 · ${input.length} 字符"), fontSize = TypeScale.Body)
                OutlinedTextField(input, { if (it.length <= AI_INPUT_LIMIT) { input = it; output = "" } }, enabled = context != null && !occupied,
                    modifier = Modifier.fillMaxWidth().weight(1f).textInputFocus(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = TypeScale.Small, fontFamily = CodeFont))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmSend, { confirmSend = it }, enabled = context != null && !occupied)
                    Text(t("I reviewed this text and allow sending it", "已检查文本，允许发送"), fontSize = TypeScale.Small, modifier = Modifier.weight(1f))
                    TextButton(enabled = valid && current && !occupied && confirmSend && input.isNotBlank(), onClick = {
                        generation = AiGeneration(checkNotNull(context), AiModelRequest(connection.protocol, connection.endpoint, connection.model, scope.kind, input), connection.token)
                    }) { Text(t("Generate", "生成")) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (scope.kind == AiKind.COMMIT) t("Editable draft", "可编辑草稿") else t("Summary of the snapshot", "快照摘要"), fontSize = TypeScale.Body)
                OutlinedTextField(output, { output = it }, readOnly = scope.kind == AiKind.BRANCH || generating, modifier = Modifier.fillMaxWidth().weight(1f).textInputFocus(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = TypeScale.Body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = output.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(output)) }) { Text(t("Copy", "复制")) }
                    Spacer(Modifier.weight(1f))
                    if (scope.kind == AiKind.COMMIT) TextButton(enabled = current && output.isNotBlank() && !occupied, onClick = {
                        val message = draft.message; val amend = draft.amend; val replacement = output
                        fun apply() {
                            val reviewed = context ?: return
                            tasks.launch {
                                if (!runInterruptible(Dispatchers.IO) { cli.isAiContextCurrent(reviewed) }) {
                                    current = false; error = t("Snapshot changed. Reload before using this draft.", "快照已变化，请重新加载后使用草稿。")
                                } else if (draft.applyAiText(replacement, message, amend)) onDraft()
                                else error = t("Your commit draft changed. Review it before replacing.", "提交草稿已变化，请检查后再替换。")
                            }
                        }
                        if (message.isBlank()) apply() else actions.ask(DialogSpec(t("Replace commit draft", "替换提交草稿"),
                            t("Replace the current draft with the reviewed generated text? This does not create a commit.", "用已检查的生成文本替换当前草稿？此操作不会创建提交。"), t("Replace draft", "替换草稿")) { _, _ -> apply() })
                    }) { Text(if (draft.message.isBlank()) t("Use draft", "使用草稿") else t("Replace draft…", "替换草稿…")) }
                }
            }
        }
    }
}
