package gitviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 24×24 画布上的线条图标路径。 */
object Icons {
    const val Refresh = "M3 12a9 9 0 0 1 15-6.7L21 8 M21 3v5h-5 M21 12a9 9 0 0 1-15 6.7L3 16 M3 21v-5h5"
    const val Pull = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3"
    const val Push = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M17 8l-5-5-5 5 M12 3v12"
    const val Branch = "M6 3v12 M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M18 9a9 9 0 0 1-9 9"
    const val Stash = "M21 8v13H3V8 M1 3h22v5H1z M10 12h4"
    const val Search = "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M21 21l-4.3-4.3"
    const val Cloud = "M18 10h-1.3A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"
    const val Tag = "M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L2 12V2h10l8.6 8.6a2 2 0 0 1 0 2.8z M7 7h.01"
    const val History = "M3 12a9 9 0 1 0 3-6.7L3 8 M3 3v5h5 M12 7v5l4 2"
    const val Sun = "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z M12 1v2 M12 21v2 M4.2 4.2l1.4 1.4 M18.4 18.4l1.4 1.4 M1 12h2 M21 12h2 M4.2 19.8l1.4-1.4 M18.4 5.6l1.4-1.4"
    const val Moon = "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"
    const val ChevronDown = "M6 9l6 6 6-6"
    const val Settings = "M4 7h10 M18 7h2 M4 17h2 M10 17h10 M16 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M8 20a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
    const val Edit = "M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"
}

@Composable
fun Icon(path: String, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp, strokeWidth: Float = 2f) {
    val outline = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val scaled = androidx.compose.ui.graphics.Path().apply { addPath(outline) }
        scaled.transform(androidx.compose.ui.graphics.Matrix().apply { scale(k, k) })
        drawPath(scaled, color, style = Stroke(strokeWidth * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 工具栏按钮：上图标下文字。 */
@Composable
fun ToolButton(label: String, icon: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier.width(54.dp).height(42.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(6.dp))
            .background(if (hovered && enabled) Palette.Raised else Color.Transparent)
            .hoverable(interaction, enabled)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, Palette.Ink)
        Text(label, fontSize = TypeScale.Caption, color = Palette.Muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 只有图标的小按钮。 */
@Composable
fun IconButton(icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(5.dp))
            .background(if (hovered && enabled) Palette.Hover else Color.Transparent)
            .hoverable(interaction, enabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, Palette.Muted)
    }
}

/** 一组互斥的小按钮，比如 diff 的统一、并排。 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, bordered: Boolean = true, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        (if (bordered) Modifier.border(1.dp, Palette.Line, shape) else Modifier).clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((value, label) in options) {
            Box(
                Modifier.height(20.dp)
                    .background(if (value == selected) Palette.Raised else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = TypeScale.Caption, color = if (value == selected) Palette.Ink else Palette.Muted)
            }
        }
    }
}

/** 作者头像：名字的前两个字母，颜色由名字决定，同一个人始终同色。 */
@Composable
fun Avatar(name: String) {
    val initials = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    Box(
        Modifier.size(18.dp).background(laneColor(name.hashCode().and(0xFF)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Node, maxLines = 1)
    }
}

/** 文件改动的 A、M、D 标记。 */
@Composable
fun ChangeBadge(tag: String, color: Color) {
    Box(
        Modifier.size(16.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(tag, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = CodeFont, color = color)
    }
}

/** 第一个文字元素的中线对齐用的小圆点，比如状态栏的仓库状态。 */
@Composable
fun Dot(color: Color) {
    Canvas(Modifier.size(7.dp)) { drawCircle(color, center = Offset(size.width / 2, size.height / 2)) }
}

/** 鼠标移到行上时加一层很淡的底色。 */
fun Modifier.hoverRow() = composed {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    hoverable(interaction).background(if (hovered) Palette.Hover else Color.Transparent)
}

/** 鼠标右键按下。 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onRightClick(action: () -> Unit) = onPointerEvent(PointerEventType.Press) {
    if (it.buttons.isSecondaryPressed) action()
}
