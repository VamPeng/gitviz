package gitviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LaneColors = listOf(
    Color(0xFF4DA3FF), Color(0xFFFF7A59), Color(0xFF3DD68C), Color(0xFFE66CC8),
    Color(0xFFFFD23F), Color(0xFF2FD4D4), Color(0xFFA78BFA), Color(0xFFFF9F45),
)

fun laneColor(lane: Int) = LaneColors[lane % LaneColors.size]

val LaneWidth: Dp = 14.dp
const val MAX_VISIBLE_LANES = 10

@Composable
fun GraphCell(row: GraphRow, lanesShown: Int, isHeadCommit: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.width(LaneWidth * lanesShown).fillMaxHeight()) {
        val w = LaneWidth.toPx()
        val h = size.height
        val mid = h / 2
        val stroke = 3.dp.toPx()
        fun x(lane: Int) = w * lane + w / 2

        for (lane in row.passThrough) {
            if (lane < lanesShown) drawLine(laneColor(lane), Offset(x(lane), 0f), Offset(x(lane), h), stroke)
        }
        for (lane in row.incoming) {
            if (lane < lanesShown) curve(Offset(x(lane), 0f), Offset(x(row.node), mid), laneColor(lane), stroke)
        }
        for (lane in row.outgoing) {
            if (lane < lanesShown) {
                // 颜色取下方那一列，合并线就和被合并分支同色
                curve(Offset(x(row.node), mid), Offset(x(lane), h), laneColor(lane), stroke)
            }
        }

        if (row.node < lanesShown) {
            val center = Offset(x(row.node), mid)
            // 提交是站点；HEAD 是更大的换乘站
            val r = (if (isHeadCommit) 6.5.dp else 4.5.dp).toPx()
            drawCircle(laneColor(row.node), r, center)
            drawCircle(Palette.Ink, r - 2.dp.toPx(), center)
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
