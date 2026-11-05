package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class NetOp {
    PULL, PUSH, ABORT;

    val running: String get() = when (this) {
        PULL -> t("Pulling", "正在拉取")
        PUSH -> t("Pushing", "正在推送")
        ABORT -> t("Aborting", "正在放弃")
    }

    val done: String get() = when (this) {
        PULL -> t("Pulled", "已拉取")
        PUSH -> t("Pushed", "已推送")
        ABORT -> t("Aborted; back to where you were", "已放弃，回到操作之前")
    }
}

private val ConflictHint: String
    get() = t("Resolve them under “Resolve conflicts”, or abort to go back to where you were.", "到「解决冲突」里处理，或者放弃回到操作之前。")

/** running 形如「正在合并」或 Merging，得到「合并失败」或 Merging failed。 */
private fun failedTitle(running: String): String = t("$running failed", "${running.removePrefix("正在")}失败")

enum class NetState { RUNNING, DONE, FAILED }

/** 工具栏下方横条的内容。next 是失败时可以直接做的下一步。 */
data class NetStatus(
    val title: String,
    val detail: String,
    val state: NetState,
    val next: NetOp? = null,
    val nextLabel: String = "",
)

internal fun describe(op: NetOp, result: GitResult): NetStatus {
    val lastLine = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
    return when (result.failure) {
        null -> NetStatus(op.done, lastLine, NetState.DONE)
        GitFailure.REJECTED ->
            NetStatus(t("Push rejected", "推送被拒绝"), t("The remote has commits you don't have. Pull first, then push.", "远程有你本地没有的新提交。先拉取，再推送。"), NetState.FAILED, NetOp.PULL, t("Pull first", "先拉取"))
        GitFailure.AUTH ->
            NetStatus(t("Can't reach the remote", "连不上远程"), t("Authentication failed. Check your SSH key and ssh-agent, or your access token.", "验证失败。检查 SSH 密钥和 ssh-agent，或访问令牌。"), NetState.FAILED, op, t("Retry", "重试"))
        GitFailure.CONFLICT ->
            NetStatus(t("Conflicts while pulling", "拉取时有冲突"), ConflictHint, NetState.FAILED, NetOp.ABORT, t("Abort", "放弃"))
        GitFailure.NOT_MERGED, GitFailure.OTHER ->
            NetStatus(failedTitle(op.running), errorSummary(result.output), NetState.FAILED, op, t("Retry", "重试"))
    }
}

/** 分支、合并这些本地操作的结果。running 形如「正在合并」。 */
internal fun describeAction(running: String, done: String, result: GitResult): NetStatus = when (result.failure) {
    null -> NetStatus(done, "", NetState.DONE)
    GitFailure.CONFLICT -> NetStatus(t("Conflicts", "有冲突"), ConflictHint, NetState.FAILED, NetOp.ABORT, t("Abort", "放弃"))
    GitFailure.NOT_MERGED -> NetStatus(
        t("Not deleted", "没有删除"),
        t("This branch has commits that aren't merged anywhere else, and deleting it would lose them. If you're sure, right-click the branch and choose “Force delete…”.", "这个分支有还没合并到别处的提交，删除会丢掉它们。确定不要了，右键分支选「强制删除…」。"),
        NetState.FAILED,
    )
    else -> NetStatus(failedTitle(running), errorSummary(result.output), NetState.FAILED)
}

@Composable
internal fun NetBanner(status: NetStatus, onAction: (NetOp) -> Unit, onDismiss: () -> Unit) {
    val failed = status.state == NetState.FAILED
    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp)
            .background(if (failed) Palette.Removed.copy(alpha = 0.1f) else Palette.Panel)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (status.state == NetState.RUNNING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(status.title, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (failed) Palette.Removed else Palette.Ink)
        Text(status.detail, fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        status.next?.let { op -> BannerButton(status.nextLabel) { onAction(op) } }
        if (failed) BannerButton(t("Close", "关闭"), onDismiss)
    }
}

@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        label, fontSize = TypeScale.Small, maxLines = 1,
        modifier = Modifier.clip(shape).border(1.dp, Palette.Line, shape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
