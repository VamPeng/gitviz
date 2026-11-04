package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberCursorPositionProvider
import java.io.File

sealed interface MenuEntry

data object MenuSeparator : MenuEntry

data class MenuItem(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val action: () -> Unit = {},
) : MenuEntry

/** 需要用户确认或输入的操作。input 不为空时显示输入框，modes 为真时显示重置方式。 */
data class DialogSpec(
    val title: String,
    val message: String,
    val confirm: String,
    val input: String? = null,
    val optional: Boolean = false,
    val initial: String = "",
    val danger: Boolean = false,
    val modes: Boolean = false,
    val onConfirm: (text: String, mode: ResetMode) -> Unit,
)

/** 菜单项要用到的动作，由窗口提供。run 的两段文字是进行中和完成后的提示。 */
internal class GitActions(
    val ask: (DialogSpec) -> Unit,
    val run: (running: String, done: String, op: GitCli.() -> GitResult) -> Unit,
    val copy: (String) -> Unit,
)

internal fun commitMenu(c: CommitInfo, isHead: Boolean, branch: String?, a: GitActions): List<MenuEntry> {
    val short = c.id.take(7)
    val target = branch ?: "当前提交"
    return listOf(
        MenuItem("检出这个提交") {
            a.ask(DialogSpec("检出提交", "工作区会切换到 $short，不在任何分支上。", "检出") { _, _ ->
                a.run("正在检出", "已检出 $short") { checkout(c.id) }
            })
        },
        MenuItem("新建分支…") { askNewBranch(c, a) },
        MenuItem("打标签…") {
            a.ask(DialogSpec("打标签", "给 $short 打一个标签。", "创建标签", input = "标签名") { name, _ ->
                a.run("正在创建标签", "已创建标签 $name") { tag(name, c.id) }
            })
        },
        MenuSeparator,
        MenuItem("合并到 $target…", enabled = !isHead) {
            a.ask(DialogSpec("合并", "把 $short 合并到 $target。", "合并") { _, _ ->
                a.run("正在合并", "已合并 $short") { merge(c.id) }
            })
        },
        MenuItem("Cherry-pick 到 $target…", enabled = !isHead) {
            a.ask(DialogSpec("Cherry-pick", "把 $short 的改动应用到 $target。", "应用") { _, _ ->
                a.run("正在应用", "已应用 $short") { cherryPick(c) }
            })
        },
        MenuItem("回滚这个提交…") {
            a.ask(DialogSpec("回滚提交", "在 $target 上新增一个提交，撤销 $short 的改动。", "回滚") { _, _ ->
                a.run("正在回滚", "已回滚 $short") { revert(c) }
            })
        },
        MenuItem("把 $target 重置到这里…", enabled = !isHead, danger = true) {
            a.ask(DialogSpec("重置 $target", "把 $target 移到 $short。", "重置", danger = true, modes = true) { _, mode ->
                a.run("正在重置", "已重置到 $short") { reset(c.id, mode) }
            })
        },
        MenuSeparator,
        MenuItem("复制哈希") { a.copy(c.id) },
        MenuItem("复制提交说明") { a.copy(c.fullMessage.trim()) },
    )
}

internal fun askNewBranch(c: CommitInfo, a: GitActions) {
    a.ask(DialogSpec("新建分支", "从 ${c.id.take(7)} 创建分支。", "创建分支", input = "分支名") { name, _ ->
        a.run("正在创建分支", "已创建分支 $name") { createBranch(name, c.id) }
    })
}

internal fun branchMenu(name: String, isCurrent: Boolean, target: String?, a: GitActions): List<MenuEntry> = listOf(
    MenuItem("切换到这个分支", enabled = !isCurrent) {
        a.run("正在切换", "已切换到 $name") { switchTo(name) }
    },
    MenuItem("合并到 ${target ?: "当前提交"}…", enabled = !isCurrent) {
        a.ask(DialogSpec("合并", "把 $name 合并到 ${target ?: "当前提交"}。", "合并") { _, _ ->
            a.run("正在合并", "已合并 $name") { merge(name) }
        })
    },
    MenuSeparator,
    MenuItem("重命名…", enabled = !isCurrent) {
        a.ask(DialogSpec("重命名分支", "把 $name 改成新的名字。", "重命名", input = "新分支名", initial = name) { to, _ ->
            a.run("正在重命名", "已重命名为 $to") { renameBranch(name, to) }
        })
    },
    MenuItem("删除…", enabled = !isCurrent, danger = true) {
        a.ask(DialogSpec("删除分支", "删除本地分支 $name。没有合并的分支 git 会拒绝删除。", "删除", danger = true) { _, _ ->
            a.run("正在删除", "已删除分支 $name") { deleteBranch(name) }
        })
    },
    MenuItem("强制删除…", enabled = !isCurrent, danger = true) {
        a.ask(DialogSpec(
            "强制删除分支",
            "删除本地分支 $name，即使它有还没合并的提交。这些提交之后只能从 reflog 找回。",
            "强制删除", danger = true,
        ) { _, _ ->
            a.run("正在删除", "已删除分支 $name") { deleteBranch(name, force = true) }
        })
    },
)

internal fun repoMenu(
    current: String?,
    recent: List<String>,
    onOpen: (String) -> Unit,
    onBrowse: () -> Unit,
    onClone: () -> Unit,
): List<MenuEntry> {
    val repos: List<MenuEntry> = recent.map { path -> MenuItem(File(path).name, enabled = path != current) { onOpen(path) } }
    return repos + (if (repos.isEmpty()) emptyList() else listOf(MenuSeparator)) +
        listOf(MenuItem("打开仓库…", action = onBrowse), MenuItem("克隆仓库…", action = onClone))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GitMenuPopup(entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Popup(
        popupPositionProvider = rememberCursorPositionProvider(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(236.dp).shadow(10.dp, shape).background(Palette.Field, shape).border(1.dp, Palette.Line, shape).padding(4.dp),
        ) {
            for (entry in entries) {
                when (entry) {
                    MenuSeparator -> HorizontalDivider(Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                    is MenuItem -> Row(
                        Modifier.fillMaxWidth().height(27.dp).clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = entry.enabled) {
                                onDismiss()
                                entry.action()
                            }
                            .then(if (entry.enabled) Modifier.hoverRow() else Modifier)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.label, fontSize = TypeScale.Body, maxLines = 1,
                            color = when {
                                !entry.enabled -> Palette.Faint
                                entry.danger -> Palette.Removed
                                else -> Palette.Ink
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActionDialog(spec: DialogSpec, onDismiss: () -> Unit) {
    var text by remember(spec) { mutableStateOf(spec.initial) }
    var mode by remember(spec) { mutableStateOf(ResetMode.MIXED) }
    val canConfirm = spec.input == null || spec.optional || text.isNotBlank()
    val shape = RoundedCornerShape(5.dp)
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title, fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(spec.message, fontSize = TypeScale.Body, color = Palette.Muted)
                if (spec.input != null) {
                    Text(spec.input, fontSize = TypeScale.Small, color = Palette.Muted)
                    BasicTextField(
                        text, { text = it }, singleLine = true,
                        textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body, fontFamily = UiFont),
                        cursorBrush = SolidColor(Palette.Accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            .background(Palette.Rail, shape).border(1.dp, Palette.Line, shape)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
                if (spec.modes) {
                    for (m in ResetMode.entries) {
                        val on = m == mode
                        Column(
                            Modifier.fillMaxWidth().clip(shape)
                                .background(if (on) Palette.Raised else Color.Transparent)
                                .border(1.dp, if (on) Palette.Accent else Palette.Line, shape)
                                .clickable { mode = m }.padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(m.label, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
                            Text(m.hint, fontSize = TypeScale.Small, color = if (m == ResetMode.HARD) Palette.Removed else Palette.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = {
                onDismiss()
                spec.onConfirm(text.trim(), mode)
            }) { Text(spec.confirm, color = if (spec.danger) Palette.Removed else Palette.AccentInk) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Palette.Ink) } },
        containerColor = Palette.Field,
        shape = RoundedCornerShape(10.dp),
    )
}
