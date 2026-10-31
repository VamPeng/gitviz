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

enum class NetOp(val running: String, val done: String) {
    PULL("正在拉取", "已拉取"),
    PUSH("正在推送", "已推送"),
}

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
            NetStatus("推送被拒绝", "远程有你本地没有的新提交。先拉取，再推送。", NetState.FAILED, NetOp.PULL, "先拉取")
        GitFailure.AUTH ->
            NetStatus("连不上远程", "验证失败。检查 SSH 密钥和 ssh-agent，或访问令牌。", NetState.FAILED, op, "重试")
        GitFailure.CONFLICT ->
            NetStatus("拉取时有冲突", "冲突界面还没做。先在终端解决，或运行 git merge --abort 放弃合并。", NetState.FAILED)
        GitFailure.OTHER -> NetStatus("${op.running.removePrefix("正在")}失败", lastLine, NetState.FAILED, op, "重试")
    }
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
        if (failed) BannerButton("关闭", onDismiss)
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
