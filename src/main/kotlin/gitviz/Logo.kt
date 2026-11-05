package gitviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** V. 家族的琥珀色，只用在 Logo 上，不跟主题变。 */
private val LogoAmber = Color(0xFFFFB000)

/**
 * GitViz 的图形标志：两条线从左上的提交分开，在右下的琥珀色提交合并。
 * 和 design/icon/gitviz-*.svg 是同一套坐标（100 × 100 的图标格子），这里去掉底板，只画图形，
 * 线和普通节点用 [Palette.Ink]，所以浅色、深色主题都能看清。
 */
@Composable
fun GitVizMark(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val ink = Palette.Ink
    Canvas(modifier.size(size)) {
        // 图形在格子里大约占 17..85，裁掉四周空白
        val origin = 17f
        val k = this.size.width / 68f
        fun p(x: Float, y: Float) = Offset((x - origin) * k, (y - origin) * k)

        val main = Path().apply {
            moveTo(p(26f, 26f).x, p(26f, 26f).y)
            cubicTo(p(27.33f, 55.33f).x, p(27.33f, 55.33f).y, p(42.67f, 70.67f).x, p(42.67f, 70.67f).y, p(72f, 72f).x, p(72f, 72f).y)
        }
        val branch = Path().apply {
            moveTo(p(26f, 26f).x, p(26f, 26f).y)
            cubicTo(p(55.33f, 27.33f).x, p(55.33f, 27.33f).y, p(70.67f, 42.67f).x, p(70.67f, 42.67f).y, p(72f, 72f).x, p(72f, 72f).y)
        }
        // 比图标里的线细一些：没有底板时，同样粗的线在小尺寸下会把中间的空隙挤掉
        drawPath(main, ink, style = Stroke(6.5f * k, cap = StrokeCap.Round))
        drawPath(branch, ink, style = Stroke(4.8f * k, cap = StrokeCap.Round))
        commit(p(26f, 26f), 15f * k, 2.8f * k, ink)
        commit(p(59.5f, 38.5f), 13f * k, 2.4f * k, ink)
        commit(p(72f, 72f), 21f * k, 3.8f * k, LogoAmber)
    }
}

/** 倾斜 -12° 的圆角方块，V. 句点的形状。 */
private fun DrawScope.commit(center: Offset, side: Float, radius: Float, color: Color) {
    rotate(-12f, center) {
        drawRoundRect(
            color,
            topLeft = Offset(center.x - side / 2, center.y - side / 2),
            size = Size(side, side),
            cornerRadius = CornerRadius(radius),
        )
    }
}
