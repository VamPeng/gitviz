package gitviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun laneColor(lane: Int) = Palette.Lanes[lane % Palette.Lanes.size]

/** 有高亮的线时，其余的线淡成这个透明度。 */
private const val DIMMED_ALPHA = 0.18f

val LaneWidth: Dp = 14.dp
/** 图至少能放这么多列；窗口宽时按宽度放更多，见 CommitList。 */
const val MIN_VISIBLE_LANES = 10

@Composable
fun GraphCell(
    row: GraphRow,
    lanesShown: Int,
    isHeadCommit: Boolean,
    modifier: Modifier = Modifier,
    isStash: Boolean = false,
    /** 要高亮的线的编号（Track.line）；null 表示不高亮，所有线正常显示。 */
    highlight: Int? = null,
) {
    Canvas(modifier.width(LaneWidth * lanesShown).fillMaxHeight()) {
        val w = LaneWidth.toPx()
        val h = size.height
        val mid = h / 2
        val stroke = 2.5.dp.toPx()
        fun x(lane: Int) = w * lane + w / 2

        fun lit(t: Track) = highlight == null || t.line == highlight
        fun colorOf(t: Track) = laneColor(t.color).let { if (lit(t)) it else it.copy(alpha = DIMMED_ALPHA) }

        // 先画淡的、再画亮的，高亮的线不会被淡线盖住
        for (bright in listOf(false, true)) {
            row.passThrough.forEachIndexed { i, lane ->
                val t = row.passTracks[i]
                if (lane < lanesShown && lit(t) == bright) drawLine(colorOf(t), Offset(x(lane), 0f), Offset(x(lane), h), stroke)
            }
            row.incoming.forEachIndexed { i, lane ->
                val t = row.incomingTracks[i]
                if (lane < lanesShown && lit(t) == bright) curve(Offset(x(lane), 0f), Offset(x(row.node), mid), colorOf(t), stroke)
            }
            row.outgoing.forEachIndexed { i, lane ->
                val t = row.outgoingTracks[i]
                // 颜色取下方那条线，合并线就和被合并分支同色
                if (lane < lanesShown && lit(t) == bright) curve(Offset(x(row.node), mid), Offset(x(lane), h), colorOf(t), stroke)
            }
        }

        // 放不下的列不画，在最右一列的位置画三个点，提示右边还有线
        val overflow = row.node >= lanesShown || row.passThrough.any { it >= lanesShown } ||
            row.incoming.any { it >= lanesShown } || row.outgoing.any { it >= lanesShown }
        if (overflow) {
            val edge = w * lanesShown - 2.dp.toPx()
            for (k in 0..2) drawCircle(Palette.Faint, 1.2.dp.toPx(), Offset(edge, mid + (k - 1) * 4.dp.toPx()))
        }

        if (row.node < lanesShown && isStash) {
            // 储藏不是真正的提交，画成空心方块，和圆形的站点区分开
            val half = 4.dp.toPx()
            val topLeft = Offset(x(row.node) - half, mid - half)
            val box = Size(half * 2, half * 2)
            drawRect(Palette.Node, topLeft, box)
            drawRect(colorOf(row.nodeTrack), topLeft, box, style = Stroke(1.5.dp.toPx()))
        } else if (row.node < lanesShown) {
            val center = Offset(x(row.node), mid)
            // 提交是站点；HEAD 是更大的换乘站
            val r = (if (isHeadCommit) 6.dp else 4.5.dp).toPx()
            drawCircle(colorOf(row.nodeTrack), r, center)
            drawCircle(Palette.Node, r - 2.dp.toPx(), center)
            if (isHeadCommit) drawCircle(Palette.Rail, 1.5.dp.toPx(), center)
        }
    }
}

/** 同一列画直线，跨列画平滑的 S 形曲线。 */
private fun DrawScope.curve(from: Offset, to: Offset, color: Color, stroke: Float) {
    if (from.x == to.x) {
        drawLine(color, from, to, stroke)
        return
    }
    val path = Path().apply {
        moveTo(from.x, from.y)
        val my = (from.y + to.y) / 2
        cubicTo(from.x, my, to.x, my, to.x, to.y)
    }
    drawPath(path, color, style = Stroke(stroke))
}
