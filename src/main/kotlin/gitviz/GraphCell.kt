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
    Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFE57373),
    Color(0xFFBA68C8), Color(0xFFFFF176), Color(0xFF4DB6AC), Color(0xFFF06292),
)

fun laneColor(lane: Int) = LaneColors[lane % LaneColors.size]

val LaneWidth: Dp = 14.dp
const val MAX_VISIBLE_LANES = 24

@Composable
fun GraphCell(row: GraphRow, lanesShown: Int, isHeadCommit: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.width(LaneWidth * lanesShown).fillMaxHeight()) {
        val w = LaneWidth.toPx()
        val h = size.height
        val mid = h / 2
        val stroke = 2.dp.toPx()
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
            val r = 4.5.dp.toPx()
            drawCircle(laneColor(row.node), r, center)
            if (isHeadCommit) drawCircle(Color.White, r + 2.5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
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
