package gitviz

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 夜间线路图配色：蓝灰底色，琥珀色只用来标“当前位置”。 */
object Palette {
    val Ground = Color(0xFF1A2330)
    val Rail = Color(0xFF141C27)
    val Raised = Color(0xFF232E3D)
    val Line = Color(0xFF2F3C4E)
    val Ink = Color(0xFFE6EBF2)
    val Muted = Color(0xFF93A1B3)
    val Faint = Color(0xFF64748A)
    val Signal = Color(0xFFF2B233)

    val Added = Color(0xFF3DD68C)
    val Removed = Color(0xFFFF6B6B)
    val Modified = Color(0xFFFFB454)
    val Renamed = Color(0xFF4DA3FF)
    val Copied = Color(0xFFA78BFA)
}

object TypeScale {
    val Caption = 11.sp
    val Small = 12.sp
    val Body = 13.sp
    val Heading = 15.sp
    val Title = 18.sp
}

val isMac = System.getProperty("os.name").startsWith("Mac")

// 其他系统没有这两款字体，用平台默认
@OptIn(ExperimentalTextApi::class)
val UiFont: FontFamily = if (!isMac) FontFamily.Default else FontFamily(
    SystemFont("PingFang SC", FontWeight.Normal),
    SystemFont("PingFang SC", FontWeight.Medium),
    SystemFont("PingFang SC", FontWeight.SemiBold),
)

@OptIn(ExperimentalTextApi::class)
val CodeFont: FontFamily = if (!isMac) FontFamily.Monospace else FontFamily(
    SystemFont("Menlo", FontWeight.Normal),
    SystemFont("Menlo", FontWeight.Bold),
)

private val ColorScheme = darkColorScheme(
    primary = Palette.Signal,
    onPrimary = Palette.Rail,
    background = Palette.Ground,
    onBackground = Palette.Ink,
    surface = Palette.Ground,
    onSurface = Palette.Ink,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLow = Palette.Rail,
    surfaceContainerHighest = Palette.Raised,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Palette.Removed,
)

private val Scrollbar = ScrollbarStyle(
    minimalHeight = 32.dp,
    thickness = 6.dp,
    shape = RoundedCornerShape(3.dp),
    hoverDurationMillis = 150,
    unhoverColor = Color.White.copy(alpha = 0.14f),
    hoverColor = Color.White.copy(alpha = 0.3f),
)

@Composable
fun GitVizTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = UiFont),
            LocalScrollbarStyle provides Scrollbar,
            content = content,
        )
    }
}

/** 选中行：抬高一层底色，左边一条琥珀色标记。 */
fun Modifier.rowHighlight(selected: Boolean) = drawBehind {
    if (selected) {
        drawRect(Palette.Raised)
        drawRect(Palette.Signal, size = Size(2.dp.toPx(), size.height))
    }
}
