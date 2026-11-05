package gitviz

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

internal const val DEFAULT_DETAIL_FRACTION = 0.54f
private val SheetBottom = 8.dp

/** 展开到整个主区域，收起恢复原有默认高度。 */
internal class DetailSheetBounds(available: Dp) {
    val maximum = available.coerceAtLeast(0.dp)
    val minimum = minOf(180.dp, maximum)
    val default = (available * DEFAULT_DETAIL_FRACTION).coerceIn(minimum, maximum)
    fun clamp(height: Dp) = height.coerceIn(minimum, maximum)
}

/** 列表始终铺满底层；详情是局部浮层，不阻断列表、工具栏或侧边栏。 */
@Composable
internal fun CommitHistoryLayout(
    hasSelection: Boolean,
    modifier: Modifier = Modifier,
    list: @Composable (bottomPadding: Dp) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val available = maxHeight
        val bounds = DetailSheetBounds(available)
        // 用比例记住手动高度，窗口缩放时仍保持相同占比。
        var fraction by remember { mutableStateOf(DEFAULT_DETAIL_FRACTION) }
        var dragging by remember { mutableStateOf(false) }
        val targetHeight = bounds.clamp(available * fraction)
        val height by animateDpAsState(targetHeight, if (dragging) snap() else tween(220))
        // 越接近全高，越贴近主区域边缘；完全展开时不露出底层列表。
        val coverage = if (available > bounds.default)
            ((height - bounds.default) / (available - bounds.default)).coerceIn(0f, 1f) else 1f
        val inset = 1f - coverage
        val visible = remember { MutableTransitionState(false) }
        // 不以提交 ID 为 key：切换提交只替换内容，不重播进场动画。
        SideEffect { visible.targetState = hasSelection }
        val bottomPadding = if (hasSelection) maxOf(bounds.default, height) + SheetBottom + 12.dp else 0.dp
        list(bottomPadding)

        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(tween(260)) { it },
            exit = slideOutVertically(tween(180)) { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp * inset).padding(bottom = SheetBottom * inset),
        ) {
            val shape = RoundedCornerShape(12.dp * inset)
            Column(
                Modifier.fillMaxWidth().height(height)
                    .shadow(16.dp, shape).clip(shape).background(Palette.Ground).border(1.dp, Palette.Line, shape)
                    // 空白处也接收指针，避免点击或滚轮穿透到被遮住的提交行。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent()
                        }
                    },
            ) {
                val density = LocalDensity.current
                val expanded = height > bounds.default + 0.5.dp
                val label = if (expanded) t("Collapse commit details", "收起提交详情") else t("Expand commit details", "展开提交详情")
                val resize = rememberDraggableState { delta ->
                    val next = bounds.clamp(available * fraction - with(density) { delta.toDp() })
                    fraction = if (available > 0.dp) next / available else DEFAULT_DETAIL_FRACTION
                }
                Box(
                    Modifier.fillMaxWidth().height(24.dp)
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
                        .draggable(resize, Orientation.Vertical,
                            onDragStarted = { fraction = height / available; dragging = true },
                            onDragStopped = { dragging = false })
                        .semantics { contentDescription = t("Drag to resize commit details", "拖动调整提交详情高度") },
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.Line.copy(alpha = 0.6f))
                    Hint(label) {
                        Box(
                            Modifier.width(48.dp).height(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(Palette.Ground).hoverRow()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(role = Role.Button, onClickLabel = label) {
                                    fraction = (if (expanded) bounds.default else bounds.maximum) / available
                                }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (expanded) Icons.ChevronDown else Icons.ChevronUp, Palette.Muted, size = 16.dp)
                        }
                    }
                }
                detail(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
