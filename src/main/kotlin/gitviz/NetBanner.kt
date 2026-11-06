package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class NetOp {
    FETCH, PULL, PUSH, ABORT;

    val running: String get() = when (this) {
        FETCH -> t("Fetching", "正在获取")
        PULL -> t("Pulling", "正在拉取")
        PUSH -> t("Pushing", "正在推送")
        ABORT -> t("Aborting", "正在放弃")
    }

    val done: String get() = when (this) {
        FETCH -> t("Fetched", "已获取")
        PULL -> t("Pulled", "已拉取")
        PUSH -> t("Pushed", "已推送")
        ABORT -> t("Aborted; back to where you were", "已放弃，回到操作之前")
    }
}

private val ConflictHint: String
    get() = t("Resolve them under “Resolve conflicts”, or abort to go back to where you were.", "到「解决冲突」里处理，或者放弃回到操作之前。")

/** running 形如「正在合并」或 Merging，得到「合并失败」或 Merging failed。 */
private fun failedTitle(running: String): String = t("$running failed", "${running.removePrefix("正在")}失败")

enum class NetState { RUNNING, DONE, WARNING, FAILED }

private val statusIds = java.util.concurrent.atomic.AtomicLong()

/** 工具栏下方横条的内容。next 是失败时可以直接做的下一步。 */
data class NetStatus(
    val title: String,
    val detail: String,
    val state: NetState,
    val next: NetOp? = null,
    val nextLabel: String = "",
    val output: String = "",
    val id: Long = statusIds.incrementAndGet(),
    val recoveryId: String? = null,
    val lessons: List<GitLesson> = emptyList(),
    val needsRecovery: Boolean = false,
)

internal fun describe(op: NetOp, result: GitResult): NetStatus {
    val lastLine = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
    return (when (result.failure) {
        null -> NetStatus(op.done, lastLine.ifBlank { t("Operation completed successfully.", "操作已成功完成。") }, NetState.DONE)
        GitFailure.REJECTED ->
            NetStatus(t("Push rejected", "推送被拒绝"), t("The remote has commits you don't have. Pull first, then push.", "远程有你本地没有的新提交。先拉取，再推送。"), NetState.FAILED, NetOp.PULL, t("Pull first", "先拉取"))
        GitFailure.AUTH ->
            NetStatus(t("Authentication failed", "身份验证失败"), t("Authentication failed. Check your SSH key and ssh-agent, or your access token.", "验证失败。检查 SSH 密钥和 ssh-agent，或访问令牌。"), NetState.FAILED, op, t("Retry", "重试"))
        GitFailure.CONFLICT ->
            NetStatus(t("Conflicts while pulling", "拉取时有冲突"), ConflictHint, NetState.WARNING, NetOp.ABORT, t("Abort", "放弃"))
        GitFailure.PAUSED -> NetStatus(t("Rebase paused", "变基已暂停"), result.output, NetState.WARNING)
        GitFailure.UNKNOWN -> NetStatus(t("Check repository state", "请核对仓库状态"), result.output, NetState.WARNING)
        GitFailure.NOT_MERGED, GitFailure.OTHER ->
            NetStatus(failedTitle(op.running), errorSummary(result.output), NetState.FAILED, op, t("Retry", "重试"))
    }).copy(output = result.output, recoveryId = result.recoveryId, lessons = result.lessons, needsRecovery = result.needsRecovery)
}

/** 分支、合并这些本地操作的结果。running 形如「正在合并」。 */
internal fun describeAction(running: String, done: String, result: GitResult): NetStatus = (when (result.failure) {
    null -> NetStatus(done, result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty(), NetState.DONE)
    GitFailure.CONFLICT -> NetStatus(t("Conflicts", "有冲突"), ConflictHint, NetState.WARNING, NetOp.ABORT, t("Abort", "放弃"))
    GitFailure.PAUSED -> NetStatus(t("Rebase paused", "变基已暂停"), result.output, NetState.WARNING)
        GitFailure.UNKNOWN -> NetStatus(t("Check repository state", "请核对仓库状态"), result.output, NetState.WARNING)
    GitFailure.NOT_MERGED -> NetStatus(
        t("Not deleted", "没有删除"),
        t("This branch has commits that aren't merged anywhere else, and deleting it would lose them. If you're sure, right-click the branch and choose “Force delete…”.", "这个分支有还没合并到别处的提交，删除会丢掉它们。确定不要了，右键分支选「强制删除…」。"),
        NetState.WARNING,
    )
    else -> NetStatus(failedTitle(running), errorSummary(result.output), NetState.FAILED)
}).copy(output = result.output, recoveryId = result.recoveryId, lessons = result.lessons, needsRecovery = result.needsRecovery)

/** 成功提示可自动收起；悬停或阅读详情时保留，警告和错误始终等待用户处理。 */
@Composable
internal fun NetBanner(status: NetStatus, onAction: (NetOp) -> Unit, onDismiss: () -> Unit, onUndo: (() -> Unit)? = null, onRecovery: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var expanded by remember(status) { mutableStateOf(false) }
    var copied by remember(status) { mutableStateOf(false) }
    var lessonOpen by remember(status.id) { mutableStateOf(Settings.teachingMode && status.lessons.isNotEmpty()) }
    val clipboard = LocalClipboardManager.current
    val running = status.state == NetState.RUNNING
    val color = when (status.state) {
        NetState.RUNNING -> Palette.Link
        NetState.DONE -> Palette.Added
        NetState.WARNING -> Palette.Modified
        NetState.FAILED -> Palette.Removed
    }
    LaunchedEffect(status, hovered, expanded, lessonOpen) {
        if (status.state == NetState.DONE && !hovered && !expanded && !lessonOpen) {
            delay(5000)
            onDismiss()
        }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
    val details = status.output.ifBlank { status.detail }
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f))
            .hoverable(interaction)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) CircularProgressIndicator(Modifier.padding(top = 2.dp).size(16.dp), color = color, strokeWidth = 2.dp)
            else Icon(if (status.state == NetState.DONE) Icons.Check else "M12 3L2 21h20L12 3z M12 9v5 M12 17v.01", color, Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(status.title, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, color = color)
                if (status.detail.isNotBlank()) Text(status.detail, fontSize = TypeScale.Small, color = Palette.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onUndo != null) BannerButton(t("Undo local change", "撤销本地改动"), onUndo)
            if (status.needsRecovery && !running && onRecovery != null) BannerButton(t("Resolve recovery issue…", "处理恢复问题…"), onRecovery)
            status.next?.let { op -> BannerButton(status.nextLabel) { onAction(op) } }
            if (!running) BannerButton(t("Close", "关闭"), onDismiss)
        }
        if (!running && details.isNotBlank()) {
            Row(Modifier.padding(start = 26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BannerButton(if (expanded) t("Hide details", "收起详情") else t("Show details", "查看详情")) { expanded = !expanded }
                BannerButton(if (copied) t("Copied", "已复制") else t("Copy details", "复制详情")) {
                    clipboard.setText(AnnotatedString(listOf(status.title, status.detail, status.output).filter { it.isNotBlank() }.distinct().joinToString("\n\n")))
                    copied = true
                }
            }
        }
        if (expanded) {
            SelectionContainer {
                Text(details, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Ink,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .background(Palette.Field, RoundedCornerShape(5.dp))
                        .verticalScroll(rememberScrollState()).padding(10.dp))
            }
        }
        if (!running && Settings.teachingMode && status.lessons.isNotEmpty()) {
            Row(Modifier.padding(start = 26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BannerButton(if (lessonOpen) t("Hide Git commands", "收起 Git 命令") else t("Learn Git commands", "查看 Git 命令")) { lessonOpen = !lessonOpen }
                BannerButton(t("Copy commands and notes", "复制命令和说明")) {
                    clipboard.setText(AnnotatedString(status.lessons.joinToString("\n\n") { lesson -> listOfNotNull(lesson.command, "# " + lesson.explanation).joinToString("\n") }))
                }
            }
            if (lessonOpen) Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(start = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Commands requested by this operation. Credentials are redacted; a failed command may not have completed.", "本次操作请求执行的命令。凭证已隐藏；失败的命令可能未执行完成。"), fontSize = TypeScale.Small, color = Palette.Muted)
                status.lessons.forEach { lesson -> SelectionContainer { Column {
                    lesson.command?.let { Text(it, fontFamily = CodeFont, fontSize = TypeScale.Small, color = Palette.Ink) }
                    Text(lesson.explanation, fontSize = TypeScale.Small, color = Palette.Muted)
                } } }
            }
        }
    }
}

@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        label, fontSize = TypeScale.Small, maxLines = 1, color = Palette.Ink,
        modifier = Modifier.clip(shape).border(1.dp, Palette.Line, shape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
