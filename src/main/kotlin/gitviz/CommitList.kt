package gitviz

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

internal fun formatTime(seconds: Long): String = fullDate.format(Instant.ofEpochSecond(seconds))

private const val MAX_VISIBLE_REFS = 3

private val AuthorColumn = 132.dp
private val DateColumn = 124.dp
private val HashColumn = 72.dp

@Composable
internal fun CommitList(
    state: RepoState,
    shown: List<Int>,
    selected: CommitInfo?,
    listState: LazyListState,
    onSelect: (CommitInfo) -> Unit,
    modifier: Modifier,
) {
    val lanesShown = remember(state) {
        (state.rows.maxOfOrNull { it.width } ?: 1).coerceIn(1, MAX_VISIBLE_LANES)
    }
    Column(modifier) {
        ColumnHeader(lanesShown)
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
                items(shown, key = { state.result.commits[it].id }) { index ->
                    val c = state.result.commits[index]
                    CommitRow(c, state.rows[index], lanesShown, c.id == state.headId, c.id == selected?.id, onSelect)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ColumnHeader(lanesShown: Int) {
    @Composable
    fun Title(text: String, modifier: Modifier) {
        Text(text, fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1, modifier = modifier)
    }
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(start = 12.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(LaneWidth * lanesShown + 10.dp))
        Title("描述", Modifier.weight(1f))
        Title("作者", Modifier.width(AuthorColumn).padding(start = 8.dp))
        Title("时间", Modifier.width(DateColumn).padding(start = 8.dp))
        Title("哈希", Modifier.width(HashColumn).padding(start = 8.dp))
    }
}

@Composable
private fun CommitRow(
    c: CommitInfo,
    row: GraphRow,
    lanesShown: Int,
    isHead: Boolean,
    isSelected: Boolean,
    onSelect: (CommitInfo) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(28.dp)
            .hoverRow()
            .rowHighlight(isSelected)
            .clickable { onSelect(c) }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GraphCell(row, lanesShown, isHead)
        Spacer(Modifier.width(10.dp))
        c.refs.take(MAX_VISIBLE_REFS).forEach { RefChip(it, laneColor(row.node)) }
        if (c.refs.size > MAX_VISIBLE_REFS) {
            Text("+${c.refs.size - MAX_VISIBLE_REFS}", fontSize = TypeScale.Caption, color = Palette.Faint,
                modifier = Modifier.padding(end = 6.dp))
        }
        Text(
            c.shortMessage, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TypeScale.Body,
            fontWeight = if (isHead) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.width(AuthorColumn).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Avatar(c.author)
            Text(c.author, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TypeScale.Small, color = Palette.Muted)
        }
        Text(formatTime(c.timeSeconds), maxLines = 1, softWrap = false, fontSize = TypeScale.Small, color = Palette.Muted,
            modifier = Modifier.width(DateColumn).padding(start = 8.dp))
        Text(c.id.take(7), fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Faint, maxLines = 1,
            modifier = Modifier.width(HashColumn).padding(start = 8.dp))
    }
}

/** 引用标记：当前分支用主色实心，本地分支浅底，远程和标签描边。 */
@Composable
private fun RefChip(ref: RefLabel, lane: Color) {
    val modifier = Modifier.padding(end = 6.dp)
    when {
        ref.kind == RefKind.HEAD -> Chip(ref.name, Palette.Ink, Palette.Rail, modifier, bold = true)
        ref.kind == RefKind.TAG -> Chip(ref.name, Color.Transparent, Palette.Muted, modifier, outline = Palette.Faint)
        ref.kind == RefKind.REMOTE -> Chip(ref.name, Color.Transparent, lane, modifier, outline = lane)
        ref.isCurrent -> Chip(ref.name, Palette.Accent, Palette.OnAccent, modifier, bold = true)
        else -> Chip(ref.name, lane.copy(alpha = 0.2f), lane, modifier)
    }
}

@Composable
private fun Chip(label: String, fill: Color, text: Color, modifier: Modifier, outline: Color? = null, bold: Boolean = false) {
    val shape = RoundedCornerShape(3.dp)
    Text(
        label, fontSize = TypeScale.Caption, color = text, maxLines = 1, softWrap = false,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        modifier = modifier.background(fill, shape)
            .then(if (outline != null) Modifier.border(1.dp, outline, shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
