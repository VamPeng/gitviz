package gitviz

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.util.prefs.Preferences

private class Scheme(
    val ground: Color,
    val rail: Color,
    val panel: Color,
    val field: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val node: Color,
    val accent: Color,
    val accentInk: Color,
    val onAccent: Color,
    val link: Color,
    val added: Color,
    val removed: Color,
    val modified: Color,
    val copied: Color,
    val lanes: List<Color>,
) {
    val raised = accent.copy(alpha = 0.15f)
    val hover = accent.copy(alpha = 0.08f)
}

private val Light = Scheme(
    ground = Color(0xFFF7F7F6), rail = Color(0xFFECECEA), panel = Color(0xFFF2F2F0), field = Color(0xFFFFFFFF),
    line = Color(0xFFDADAD6), ink = Color(0xFF1F1F1E), muted = Color(0xFF5C5C58), faint = Color(0xFF6C6C68),
    node = Color(0xFFFFFFFF), accent = Color(0xFFC2570C), accentInk = Color(0xFFA54A0A), onAccent = Color(0xFFFFFFFF),
    link = Color(0xFF1F56C4), added = Color(0xFF1A7F4B), removed = Color(0xFFCF222E), modified = Color(0xFF9A6700),
    copied = Color(0xFF6D4FC4),
    lanes = listOf(
        Color(0xFF1F6FD6), Color(0xFFC2570C), Color(0xFF1A7F4B), Color(0xFFB8399A),
        Color(0xFF9A6700), Color(0xFF0E7C86), Color(0xFF6D4FC4), Color(0xFFCF222E),
    ),
)

private val Dark = Scheme(
    ground = Color(0xFF232323), rail = Color(0xFF1B1B1B), panel = Color(0xFF1F1F1F), field = Color(0xFF2C2C2C),
    line = Color(0xFF3A3A3A), ink = Color(0xFFEAEAE8), muted = Color(0xFFA8A8A4), faint = Color(0xFF92928E),
    node = Color(0xFFEAEAE8), accent = Color(0xFFF08A3C), accentInk = Color(0xFFF7AE74), onAccent = Color(0xFF241203),
    link = Color(0xFF8AB4FF), added = Color(0xFF3FB950), removed = Color(0xFFF85149), modified = Color(0xFFD29922),
    copied = Color(0xFFA48BF0),
    lanes = listOf(
        Color(0xFF4DA3FF), Color(0xFFFF7A59), Color(0xFF3FB950), Color(0xFFE66CC8),
        Color(0xFFFFD23F), Color(0xFF2FD4D4), Color(0xFFA78BFA), Color(0xFFFF9F45),
    ),
)

/**
 * 石墨橙配色：底色接近纯灰，主色只用在选中项和主按钮上。
 * 颜色跟着 isDark 这个状态走，切换主题时读过它的地方都会刷新。
 */
object Palette {
    private val prefs = Preferences.userRoot().node("gitviz")

    var isDark by mutableStateOf(prefs.getBoolean("dark", false))
        private set

    fun toggle() {
        isDark = !isDark
        prefs.putBoolean("dark", isDark)
    }

    private val s get() = if (isDark) Dark else Light

    val Ground get() = s.ground
    val Rail get() = s.rail
    val Panel get() = s.panel
    val Field get() = s.field
    val Line get() = s.line
    val Raised get() = s.raised
    val Hover get() = s.hover
    val Ink get() = s.ink
    val Muted get() = s.muted
    val Faint get() = s.faint
    val Node get() = s.node
    val Accent get() = s.accent
    val AccentInk get() = s.accentInk
    val OnAccent get() = s.onAccent
    val Link get() = s.link
    val Lanes get() = s.lanes

    val Added get() = s.added
    val Removed get() = s.removed
    val Modified get() = s.modified
    val Renamed get() = s.link
    val Copied get() = s.copied
}

object TypeScale {
    val Caption = 11.sp
    val Small = 12.sp
    val Body = 13.sp
    val Heading = 15.sp
    val Title = 16.sp
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

@Composable
fun GitVizTheme(content: @Composable () -> Unit) {
    val dark = Palette.isDark
    val colors = remember(dark) {
        val build = if (dark) ::darkScheme else ::lightScheme
        build()
    }
    val scrollbarInk = Palette.Ink
    val scrollbar = remember(dark) {
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 6.dp,
            shape = RoundedCornerShape(3.dp),
            hoverDurationMillis = 150,
            unhoverColor = scrollbarInk.copy(alpha = 0.16f),
            hoverColor = scrollbarInk.copy(alpha = 0.32f),
        )
    }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = UiFont),
            LocalScrollbarStyle provides scrollbar,
            content = content,
        )
    }
}

private fun darkScheme() = darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent, background = Palette.Ground, onBackground = Palette.Ink,
    surface = Palette.Ground, onSurface = Palette.Ink, onSurfaceVariant = Palette.Muted, surfaceContainerLow = Palette.Rail,
    surfaceContainerHighest = Palette.Field, outline = Palette.Line, outlineVariant = Palette.Line, error = Palette.Removed,
)

private fun lightScheme() = lightColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent, background = Palette.Ground, onBackground = Palette.Ink,
    surface = Palette.Ground, onSurface = Palette.Ink, onSurfaceVariant = Palette.Muted, surfaceContainerLow = Palette.Rail,
    surfaceContainerHighest = Palette.Field, outline = Palette.Line, outlineVariant = Palette.Line, error = Palette.Removed,
)

/** 选中行：抬高一层底色，左边一条主色标记。 */
fun Modifier.rowHighlight(selected: Boolean) = drawBehind {
    if (selected) {
        drawRect(Palette.Raised)
        drawRect(Palette.Accent, size = Size(2.dp.toPx(), size.height))
    }
}
