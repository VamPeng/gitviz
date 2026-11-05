package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun askStash(count: Int, a: GitActions) {
    a.ask(
        DialogSpec(t("Stash changes", "储藏改动"), t("Set aside ${countOf(count, "change")} from the working tree. You can restore them later.", "把工作区的 $count 个改动收起来，之后可以再恢复。"), t("Stash", "储藏"), input = t("Message (optional)", "说明（可选）"), optional = true) { message, _ ->
            a.run(t("Stashing", "正在储藏"), t("Stashed ${countOf(count, "file")}", "已储藏 $count 个文件")) { stash(message) }
        },
    )
}

/** 储藏：左边是列表，右边是所选储藏的文件和 diff。 */
@Composable
internal fun StashView(
    cli: GitCli,
    stashes: List<StashEntry>,
    actions: GitActions,
    modifier: Modifier,
    selectedId: String? = null,
    onSelect: (String) -> Unit = {},
) {
    val current = stashes.firstOrNull { it.id == selectedId } ?: stashes.firstOrNull()

    Row(modifier) {
        Column(Modifier.width(340.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Stashes · ${stashes.size}", "储藏 · ${stashes.size}"), fontSize = TypeScale.Small, color = Palette.Muted)
            }
            HorizontalDivider()
            if (stashes.isEmpty()) {
                Text(t("No stashes. Click “Stash” in the toolbar to set aside your working tree changes.", "没有储藏。点工具栏的「储藏」，可以把工作区的改动先收起来。"), fontSize = TypeScale.Body, color = Palette.Muted,
                    modifier = Modifier.padding(16.dp))
            }
            ScrollList(Modifier.fillMaxWidth().weight(1f)) {
                items(stashes, key = { it.id }) { entry -> StashRow(entry, entry == current) { onSelect(entry.id) } }
            }
        }
        VerticalDivider()
        if (current != null) StashDetail(cli, current, actions, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StashRow(entry: StashEntry, selected: Boolean, onSelect: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(54.dp).hoverRow().rowHighlight(selected).clickable(onClick = onSelect).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(entry.message, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${entry.ref} · ${entry.branch} · ${formatTime(entry.timeSeconds)}", fontSize = TypeScale.Caption, color = Palette.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}

@Composable
private fun StashDetail(cli: GitCli, entry: StashEntry, a: GitActions, modifier: Modifier) {
    var selectedFile by remember(entry.id) { mutableStateOf<StashFile?>(null) }
    var mode by remember { mutableStateOf(Settings.defaultDiffMode) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.message, fontSize = TypeScale.Heading, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t("Stashed on ${entry.branch}, ${formatTime(entry.timeSeconds)}", "储藏于 ${entry.branch}，${formatTime(entry.timeSeconds)}"), fontSize = TypeScale.Small, color = Palette.Muted,
                    modifier = Modifier.padding(top = 4.dp))
            }
            StashButton(t("Apply", "应用"), Palette.Ink, null) { a.run(t("Applying", "正在应用"), t("Applied; changes are back in the working tree", "已应用，改动回到工作区")) { applyStash(entry) } }
            StashButton(t("Pop", "弹出"), Palette.OnAccent, Palette.Accent) { a.run(t("Popping", "正在弹出"), t("Popped; changes are back in the working tree", "已弹出，改动回到工作区")) { popStash(entry) } }
            StashButton(t("Delete", "删除"), Palette.Removed, null) {
                a.ask(DialogSpec(t("Delete stash", "删除储藏"), t("“${entry.message}” will be permanently deleted.", "「${entry.message}」会被永久删除，无法恢复。"), t("Delete", "删除"), danger = true) { _, _ ->
                    a.run(t("Deleting", "正在删除"), t("Deleted stash", "已删除储藏")) { dropStash(entry) }
                })
            }
        }
        HorizontalDivider()
        Loaded(entry.id, Modifier.fillMaxWidth().weight(1f), { cli.stashFiles(entry) }) { files ->
            val current = selectedFile?.takeIf { it in files } ?: files.firstOrNull()
            Row(Modifier.fillMaxWidth().weight(1f)) {
                ScrollList(Modifier.width(260.dp).fillMaxHeight()) {
                    items(files, key = { it.path }) { f ->
                        Row(
                            Modifier.fillMaxWidth().height(30.dp).hoverRow().rowHighlight(f == current).clickable { selectedFile = f }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val (tag, color) = statusBadge(f.code)
                            ChangeBadge(tag, color)
                            Text(f.path.substringAfterLast('/'), fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Panel)) {
                    if (current == null) {
                        Message(t("This stash has no files", "这条储藏没有文件"), Modifier.fillMaxSize())
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(current.path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), mode) { mode = it }
                        }
                        HorizontalDivider()
                        val content = Modifier.fillMaxWidth().weight(1f)
                        Loaded(entry.id to current, content, { parseDiffLines(cli.stashDiff(entry, current)) }) { lines ->
                            DiffBody(lines, mode, content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StashButton(label: String, color: Color, fill: Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        label, fontSize = TypeScale.Small, color = color, maxLines = 1,
        fontWeight = if (fill != null) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(shape).background(fill ?: Color.Transparent, shape)
            .then(if (fill == null) Modifier.border(1.dp, Palette.Line, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
