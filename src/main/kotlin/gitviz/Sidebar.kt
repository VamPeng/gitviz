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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val Sections = listOf("本地分支" to RefKind.BRANCH, "远程" to RefKind.REMOTE, "标签" to RefKind.TAG)

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
                                    row, ref, ref.commitId == selectedId, onClick = { onSelect(ref) },
                                    onOpen = { if (ref.label.kind == RefKind.BRANCH) onBranchOpen(ref) },
                                    onMenu = { if (ref.label.kind == RefKind.BRANCH) onBranchMenu(ref) },
                                )
                        }
                    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RefLeafRow(
    row: RefRow,
    ref: RefTarget,
    selected: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val current = ref.label.isCurrent
    Row(
        Modifier.fillMaxWidth().height(25.dp)
            .onRightClick(onMenu)
            .rowHighlight(selected)
            .combinedClickable(onClick = onClick, onDoubleClick = onOpen)
            .indented(row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前分支用和分支图一样的站点标记
        Canvas(Modifier.width(14.dp).height(10.dp)) {
            if (current) drawCircle(Palette.Accent, 4.dp.toPx(), Offset(4.dp.toPx(), size.height / 2))
        }
        Text(
            row.node.name, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) Palette.AccentInk else colors.onSurface,
        )
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
