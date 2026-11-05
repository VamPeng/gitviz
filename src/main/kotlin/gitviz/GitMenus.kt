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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberCursorPositionProvider
import java.io.File

sealed interface MenuEntry

data object MenuSeparator : MenuEntry

data class MenuItem(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    /** 名字下面的一行小字，比如仓库所在的文件夹。 */
    val detail: String? = null,
    /** 当前选中的项，前面打勾。 */
    val checked: Boolean = false,
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
    val target = branch ?: t("HEAD", "当前提交")
    return listOf(
        MenuItem(t("Check out this commit", "检出这个提交")) {
            a.ask(DialogSpec(t("Check out commit", "检出提交"), t("The working tree will switch to $short, not on any branch.", "工作区会切换到 $short，不在任何分支上。"), t("Check out", "检出")) { _, _ ->
                a.run(t("Checking out", "正在检出"), t("Checked out $short", "已检出 $short")) { checkout(c.id) }
            })
        },
        MenuItem(t("New branch…", "新建分支…")) { askNewBranch(c, a) },
        MenuItem(t("Add tag…", "打标签…")) {
            a.ask(DialogSpec(t("Add tag", "打标签"), t("Add a tag to $short.", "给 $short 打一个标签。"), t("Create tag", "创建标签"), input = t("Tag name", "标签名")) { name, _ ->
                a.run(t("Creating tag", "正在创建标签"), t("Created tag $name", "已创建标签 $name")) { tag(name, c.id) }
            })
        },
        MenuSeparator,
        MenuItem(t("Merge into $target…", "合并到 $target…"), enabled = !isHead) {
            a.ask(DialogSpec(t("Merge", "合并"), t("Merge $short into $target.", "把 $short 合并到 $target。"), t("Merge", "合并")) { _, _ ->
                a.run(t("Merging", "正在合并"), t("Merged $short", "已合并 $short")) { merge(c.id) }
            })
        },
        MenuItem(t("Cherry-pick onto $target…", "Cherry-pick 到 $target…"), enabled = !isHead) {
            a.ask(DialogSpec("Cherry-pick", t("Apply the changes from $short to $target.", "把 $short 的改动应用到 $target。"), t("Apply", "应用")) { _, _ ->
                a.run(t("Applying", "正在应用"), t("Applied $short", "已应用 $short")) { cherryPick(c) }
            })
        },
        MenuItem(t("Revert this commit…", "回滚这个提交…")) {
            a.ask(DialogSpec(t("Revert commit", "回滚提交"), t("Add a commit on $target that undoes the changes from $short.", "在 $target 上新增一个提交，撤销 $short 的改动。"), t("Revert", "回滚")) { _, _ ->
                a.run(t("Reverting", "正在回滚"), t("Reverted $short", "已回滚 $short")) { revert(c) }
            })
        },
        MenuItem(t("Reset $target to here…", "把 $target 重置到这里…"), enabled = !isHead, danger = true) {
            a.ask(DialogSpec(t("Reset $target", "重置 $target"), t("Move $target to $short.", "把 $target 移到 $short。"), t("Reset", "重置"), danger = true, modes = true) { _, mode ->
                a.run(t("Resetting", "正在重置"), t("Reset to $short", "已重置到 $short")) { reset(c.id, mode) }
            })
        },
        MenuSeparator,
        MenuItem(t("Copy hash", "复制哈希")) { a.copy(c.id) },
        MenuItem(t("Copy commit message", "复制提交说明")) { a.copy(c.fullMessage.trim()) },
    )
}

internal fun askNewBranch(c: CommitInfo, a: GitActions) {
    a.ask(DialogSpec(t("New branch", "新建分支"), t("Create a branch from ${c.id.take(7)}.", "从 ${c.id.take(7)} 创建分支。"), t("Create branch", "创建分支"), input = t("Branch name", "分支名")) { name, _ ->
        a.run(t("Creating branch", "正在创建分支"), t("Created branch $name", "已创建分支 $name")) { createBranch(name, c.id) }
    })
}

internal fun branchMenu(name: String, isCurrent: Boolean, target: String?, a: GitActions): List<MenuEntry> = listOf(
    MenuItem(t("Switch to this branch", "切换到这个分支"), enabled = !isCurrent) {
        a.run(t("Switching", "正在切换"), t("Switched to $name", "已切换到 $name")) { switchTo(name) }
    },
    MenuItem(t("Merge into ${target ?: "HEAD"}…", "合并到 ${target ?: "当前提交"}…"), enabled = !isCurrent) {
        a.ask(DialogSpec(t("Merge", "合并"), t("Merge $name into ${target ?: "HEAD"}.", "把 $name 合并到 ${target ?: "当前提交"}。"), t("Merge", "合并")) { _, _ ->
            a.run(t("Merging", "正在合并"), t("Merged $name", "已合并 $name")) { merge(name) }
        })
    },
    MenuSeparator,
    MenuItem(t("Rename…", "重命名…"), enabled = !isCurrent) {
        a.ask(DialogSpec(t("Rename branch", "重命名分支"), t("Give $name a new name.", "把 $name 改成新的名字。"), t("Rename", "重命名"), input = t("New branch name", "新分支名"), initial = name) { to, _ ->
            a.run(t("Renaming", "正在重命名"), t("Renamed to $to", "已重命名为 $to")) { renameBranch(name, to) }
        })
    },
    MenuItem(t("Delete…", "删除…"), enabled = !isCurrent, danger = true) {
        a.ask(DialogSpec(t("Delete branch", "删除分支"), t("Delete the local branch $name. Git refuses if it isn't merged.", "删除本地分支 $name。没有合并的分支 git 会拒绝删除。"), t("Delete", "删除"), danger = true) { _, _ ->
            a.run(t("Deleting", "正在删除"), t("Deleted branch $name", "已删除分支 $name")) { deleteBranch(name) }
        })
    },
    MenuItem(t("Force delete…", "强制删除…"), enabled = !isCurrent, danger = true) {
        a.ask(DialogSpec(
            t("Force delete branch", "强制删除分支"),
            t("Delete the local branch $name even if it has unmerged commits. Those commits can then only be recovered from the reflog.", "删除本地分支 $name，即使它有还没合并的提交。这些提交之后只能从 reflog 找回。"),
            t("Force delete", "强制删除"), danger = true,
        ) { _, _ ->
            a.run(t("Deleting", "正在删除"), t("Deleted branch $name", "已删除分支 $name")) { deleteBranch(name, force = true) }
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
    val home = System.getProperty("user.home")
    val repos: List<MenuEntry> = recent.map { path ->
        val parent = File(path).parent?.let { if (home != null && it.startsWith(home)) "~" + it.removePrefix(home) else it }
        val isCurrent = path == current
        MenuItem(File(path).name, enabled = !isCurrent, detail = parent, checked = isCurrent) { onOpen(path) }
    }
    return repos + (if (repos.isEmpty()) emptyList() else listOf(MenuSeparator)) +
        listOf(MenuItem(t("Open repository…", "打开仓库…"), action = onBrowse), MenuItem(t("Clone repository…", "克隆仓库…"), action = onClone))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GitMenuPopup(entries: List<MenuEntry>, onDismiss: () -> Unit, anchor: IntOffset? = null) {
    val shape = RoundedCornerShape(7.dp)
    val items = entries.filterIsInstance<MenuItem>()
    // 有打勾的项时每一项都留出勾的位置，文字才对得齐
    val checkGutter = items.any { it.checked }
    val width = if (items.any { it.detail != null }) 280.dp else 236.dp
    Popup(
        popupPositionProvider = if (anchor != null) remember(anchor) { AnchorPositionProvider(anchor) } else rememberCursorPositionProvider(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(width).shadow(10.dp, shape).background(Palette.Field, shape).border(1.dp, Palette.Line, shape).padding(4.dp),
        ) {
            for (entry in entries) {
                when (entry) {
                    MenuSeparator -> HorizontalDivider(Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                    is MenuItem -> Row(
                        Modifier.fillMaxWidth().height(if (entry.detail != null) 40.dp else 27.dp).clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = entry.enabled) {
                                onDismiss()
                                entry.action()
                            }
                            .then(if (entry.enabled) Modifier.hoverRow() else Modifier)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (checkGutter) {
                            Box(Modifier.width(20.dp)) {
                                if (entry.checked) Icon(Icons.Check, Palette.Accent, size = 12.dp, strokeWidth = 3f)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                entry.label, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (entry.checked) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    entry.checked -> Palette.Ink
                                    !entry.enabled -> Palette.Faint
                                    entry.danger -> Palette.Removed
                                    else -> Palette.Ink
                                },
                            )
                            entry.detail?.let {
                                Text(it, fontSize = TypeScale.Small, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 菜单左上角放在 anchor（窗口坐标），放不下时往回收，不超出窗口。 */
private class AnchorPositionProvider(private val anchor: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(
            anchor.x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            anchor.y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)),
        )
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "取消"), color = Palette.Ink) } },
        containerColor = Palette.Field,
        shape = RoundedCornerShape(10.dp),
    )
}
