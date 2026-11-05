package gitviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val Sections get() = listOf(t("Branches", "本地分支") to RefKind.BRANCH, t("Remotes", "远程") to RefKind.REMOTE, t("Tags", "标签") to RefKind.TAG)

/** 左侧引用树。点引用跳到它指向的提交，提交还没加载时会先加载到它。 */
@Composable
fun RefSidebar(
    refs: List<RefTarget>,
    filter: String,
    selectedId: String?,
    onSelect: (RefTarget) -> Unit,
    modifier: Modifier,
    onBranchOpen: (RefTarget) -> Unit = {},
    onBranchMenu: (RefTarget) -> Unit = {},
    stashes: List<StashEntry> = emptyList(),
    onStash: (StashEntry) -> Unit = {},
    onStashOpen: (StashEntry) -> Unit = {},
    /** 分支的颜色，和分支图同源。 */
    branchColors: BranchColors = BranchColors.None,
    /** 鼠标移到某个引用上、移开（null）时通知，图上据此高亮那条分支。 */
    onHover: (RefTarget?) -> Unit = {},
) {
    var collapsed by remember { mutableStateOf(setOf("${RefKind.TAG}:")) } // 标签通常很多，默认收起
    val filtering = filter.isNotBlank()
    val trees = remember(refs, filter) {
        Sections.map { (_, kind) ->
            val matching = refs.filter { it.label.kind == kind && it.label.name.contains(filter.trim(), ignoreCase = true) }
            matching.size to buildRefTree(matching)
        }
    }
    val listState = rememberLazyListState()

    fun toggle(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
    }

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
            Sections.forEachIndexed { index, (title, kind) ->
                val (count, tree) = trees[index]
                val sectionKey = "$kind:"
                val expanded = filtering || sectionKey !in collapsed
                item(key = sectionKey) {
                    SectionHeader(title, count, expanded, onClick = { toggle(sectionKey) })
                }
                if (expanded) {
                    // 过滤时全部展开，免得匹配项藏在折叠的文件夹里
                    val rows = flattenRefTree(tree) { !filtering && sectionKey + it in collapsed }
                    items(rows, key = { sectionKey + it.node.path }) { row ->
                        val ref = row.node.ref
                        if (ref == null) {
                            val key = sectionKey + row.node.path
                            FolderRow(row, !filtering && key in collapsed, onClick = { toggle(key) })
                        } else {
                            RefLeafRow(
                                    row, ref, ref.commitId == selectedId, branchColors.index(ref.label),
                                    onHover = onHover, onClick = { onSelect(ref) },
                                    onOpen = { if (ref.label.kind == RefKind.BRANCH) onBranchOpen(ref) },
                                    onMenu = { if (ref.label.kind == RefKind.BRANCH) onBranchMenu(ref) },
                                )
                        }
                    }
                }
            }
            val shownStashes = if (filtering) stashes.filter { it.message.contains(filter.trim(), ignoreCase = true) } else stashes
            val stashKey = "${RefKind.STASH}:"
            val stashExpanded = filtering || stashKey !in collapsed
            item(key = stashKey) {
                SectionHeader(t("Stashes", "储藏"), shownStashes.size, stashExpanded, onClick = { toggle(stashKey) })
            }
            if (stashExpanded) {
                items(shownStashes, key = { stashKey + it.id }) { entry ->
                    StashLeafRow(entry, entry.id == selectedId, onClick = { onStash(entry) }, onOpen = { onStashOpen(entry) })
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(expanded)
        Text(title, fontSize = TypeScale.Small, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count", fontSize = TypeScale.Caption, color = Palette.Faint)
    }
}

@Composable
private fun FolderRow(row: RefRow, collapsed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(25.dp).clickable(onClick = onClick).indented(row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(!collapsed)
        Text(row.node.name, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RefLeafRow(
    row: RefRow,
    ref: RefTarget,
    selected: Boolean,
    colorIndex: Int?,
    onHover: (RefTarget?) -> Unit,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val current = ref.label.isCurrent
    val latestOnHover by rememberUpdatedState(onHover)
    var hovering by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (hovering) latestOnHover(null) } }
    Row(
        Modifier.fillMaxWidth().height(25.dp)
            .onPointerEvent(PointerEventType.Enter) { hovering = true; latestOnHover(ref) }
            .onPointerEvent(PointerEventType.Exit) { hovering = false; latestOnHover(null) }
            .onRightClick(onMenu)
            .rowHighlight(selected)
            .combinedClickable(onClick = onClick, onDoubleClick = onOpen)
            .indented(row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分支前的圆点用分支图上那条线的颜色：本地分支实心，远程描边，当前分支画成 HEAD 那样的换乘站
        Canvas(Modifier.width(14.dp).height(10.dp)) {
            if (colorIndex == null) return@Canvas
            val c = laneColor(colorIndex)
            val center = Offset(4.dp.toPx(), size.height / 2)
            when {
                current -> {
                    drawCircle(c, 5.dp.toPx(), center)
                    drawCircle(Palette.Node, 3.dp.toPx(), center)
                    drawCircle(Palette.Rail, 1.5.dp.toPx(), center)
                }
                ref.label.kind == RefKind.REMOTE -> drawCircle(c, 3.5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                else -> drawCircle(c, 4.dp.toPx(), center)
            }
        }
        Text(
            row.node.name, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) Palette.AccentInk else colors.onSurface,
        )
    }
}

/** 储藏：单击在图上找到它，双击打开储藏页。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StashLeafRow(entry: StashEntry, selected: Boolean, onClick: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(25.dp)
            .rowHighlight(selected)
            .combinedClickable(onClick = onClick, onDoubleClick = onOpen)
            .indented(0),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        Text(entry.message.ifBlank { entry.ref }, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
        Text(entry.ref.removePrefix("stash"), fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1)
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.padding(end = 4.dp).size(10.dp).rotate(if (expanded) 90f else 0f)) {
        val c = Offset(size.width / 2, size.height / 2)
        val arm = 2.5.dp.toPx()
        val path = Path().apply {
            moveTo(c.x - arm / 2, c.y - arm)
            lineTo(c.x + arm / 2, c.y)
            lineTo(c.x - arm / 2, c.y + arm)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx()))
    }
}

private fun Modifier.indented(depth: Int) = padding(start = (10 + 14 * (depth + 1)).dp, end = 8.dp)
