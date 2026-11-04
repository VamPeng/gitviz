package gitviz

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val fullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val shortDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

internal fun formatTime(seconds: Long): String = fullDate.format(Instant.ofEpochSecond(seconds))

/**
 * 列表里用的相对时间。一天以内按秒差算，再往前按日历天算，所以昨晚 23 点的提交今早看是“昨天”。
 * 30 天以前直接写日期。未来的时间（时钟不准）当作“刚刚”。
 */
internal fun relativeTime(seconds: Long, nowSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = nowSeconds - seconds
    if (diff < 60) return "刚刚"
    if (diff < 3600) return "${diff / 60} 分钟前"
    if (diff < 86400) return "${diff / 3600} 小时前"
    val then = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate())
    return when {
        days <= 1 -> "昨天"
        days < 30 -> "$days 天前"
        else -> shortDate.format(then)
    }
}

/** 当前时间（秒），每分钟更新一次，让列表里的相对时间跟着走。 */
@Composable
internal fun rememberNowSeconds(): Long {
    val now by produceState(Instant.now().epochSecond) {
        while (true) {
            delay(60_000)
            value = Instant.now().epochSecond
        }
    }
    return now
}

/** 相对时间，鼠标停留时显示完整时间。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RelativeTime(seconds: Long, nowSeconds: Long, modifier: Modifier = Modifier) {
    TooltipArea(
        tooltip = {
            Text(formatTime(seconds), fontSize = TypeScale.Caption, color = Palette.Ink,
                modifier = Modifier.background(Palette.Raised, RoundedCornerShape(4.dp)).border(1.dp, Palette.Line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        },
        modifier = modifier,
    ) {
        Text(relativeTime(seconds, nowSeconds), maxLines = 1, softWrap = false, fontSize = TypeScale.Small, color = Palette.Muted)
    }
}

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
    onMenu: (CommitInfo) -> Unit = {},
    onNearEnd: () -> Unit = {},
    footer: String? = null,
    onFooter: (() -> Unit)? = null,
) {
    val widest = remember(state) { state.rows.maxOfOrNull { it.width } ?: 1 }
    val now = rememberNowSeconds()

    // 离底部还剩不到 NEAR_END_ROWS 行时要下一页；数据变了重新判断，读完一页还不够就接着要
    val latestOnNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(listState, shown.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shown.size - NEAR_END_ROWS }
            .distinctUntilChanged()
            .filter { it }
            .collect { latestOnNearEnd() }
    }

    BoxWithConstraints(modifier) {
        // 图最多占列表宽度的 40%，再宽就挤掉说明了；至少能放 MIN_VISIBLE_LANES 列
        val maxLanes = maxOf(MIN_VISIBLE_LANES, ((maxWidth * 0.4f) / LaneWidth).toInt())
        val lanesShown = widest.coerceIn(1, maxLanes)
        Column(Modifier.fillMaxSize()) {
            ColumnHeader(lanesShown)
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
                    items(shown, key = { state.result.commits[it].id }) { index ->
                        val c = state.result.commits[index]
                        CommitRow(c, state.rows[index], lanesShown, c.id == state.headId, c.id == selected?.id, now, onSelect, onMenu)
                    }
                    if (footer != null) item(key = "footer") {
                        Text(
                            footer, fontSize = TypeScale.Small, color = if (onFooter != null) Palette.Link else Palette.Faint,
                            modifier = Modifier.fillMaxWidth()
                                .then(if (onFooter != null) Modifier.clickable(onClick = onFooter) else Modifier)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

private const val NEAR_END_ROWS = 200

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
    nowSeconds: Long,
    onSelect: (CommitInfo) -> Unit,
    onMenu: (CommitInfo) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(Settings.rowHeight.dp)
            .onRightClick { onMenu(c) }
            .hoverRow()
            .rowHighlight(isSelected)
            .clickable { onSelect(c) }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GraphCell(row, lanesShown, isHead, isStash = c.isStash)
        Spacer(Modifier.width(10.dp))
        c.refs.take(MAX_VISIBLE_REFS).forEach { RefChip(it, laneColor(row.node)) }
        if (c.refs.size > MAX_VISIBLE_REFS) {
            Text("+${c.refs.size - MAX_VISIBLE_REFS}", fontSize = TypeScale.Caption, color = Palette.Faint,
                modifier = Modifier.padding(end = 6.dp))
        }
        Text(
            c.shortMessage, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TypeScale.Body,
            fontWeight = if (isHead) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
            color = if (c.isStash) Palette.Muted else Color.Unspecified,
        )
        Row(
            Modifier.width(AuthorColumn).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Avatar(c.author)
            Text(c.author, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TypeScale.Small, color = Palette.Muted)
        }
        RelativeTime(c.timeSeconds, nowSeconds, Modifier.width(DateColumn).padding(start = 8.dp))
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
        ref.kind == RefKind.STASH -> Chip(ref.name, Color.Transparent, Palette.Muted, modifier, outline = Palette.Line)
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
