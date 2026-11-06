package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal class BranchDragState(val enabled: Boolean, val onDrop: (RefLabel, RefLabel) -> Unit) {
    val targets = mutableMapOf<Any, Pair<RefLabel, Rect>>()
    val blockers = mutableMapOf<Any, Rect>()
    var source by mutableStateOf<RefLabel?>(null)
    var over by mutableStateOf<RefLabel?>(null)
    var point = Offset.Zero
    fun move(point: Offset) { this.point = point; over = if (blockers.values.any { it.contains(point) }) null else targets.values.lastOrNull { it.second.contains(point) && it.first != source }?.first }
    fun cancel() { source = null; over = null }
    fun drop() { val a = source; val b = over; cancel(); if (enabled && a != null && b != null && (a.name != b.name || a.kind != b.kind)) onDrop(a, b) }
}
internal val LocalBranchDrag = staticCompositionLocalOf<BranchDragState?> { null }

@Composable
internal fun Modifier.branchDrag(ref: RefLabel): Modifier {
    val state = LocalBranchDrag.current ?: return this
    if (!state.enabled || ref.kind !in setOf(RefKind.BRANCH, RefKind.REMOTE)) return this
    val token = remember { Any() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(state, token) { onDispose { state.targets.remove(token) } }
    return this.onGloballyPositioned { val rect = it.boundsInRoot(); origin = it.localToRoot(Offset.Zero); state.targets[token] = ref to rect }
        .background(if (state.over == ref) Palette.Accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
        .pointerInput(state, ref) {
            detectDragGestures(onDragStart = { state.source = ref; state.move(origin + it) },
                onDragEnd = { state.drop() }, onDragCancel = { state.cancel() }) { change, _ ->
                change.consume(); state.move(origin + change.position)
            }
        }
}

internal fun branchDropIntent(source: RefLabel, target: RefLabel, kind: PreviewKind): OperationIntent {
    require(source != target)
    fun full(ref: RefLabel) = when (ref.kind) { RefKind.BRANCH -> "refs/heads/${ref.name}"; RefKind.REMOTE -> "refs/remotes/${ref.name}"; else -> error("Not a branch") }
    return when (kind) {
        PreviewKind.MERGE -> { require(target.kind == RefKind.BRANCH); OperationIntent(kind, full(source), switchBranch = target.name) }
        PreviewKind.REBASE -> { require(source.kind == RefKind.BRANCH); OperationIntent(kind, full(target), switchBranch = source.name) }
        else -> error("Unsupported branch gesture")
    }
}

@Composable
internal fun Modifier.blockBranchDrop(): Modifier {
    val state = LocalBranchDrag.current ?: return this
    val token = remember { Any() }
    DisposableEffect(state, token) { onDispose { state.blockers.remove(token) } }
    return onGloballyPositioned { state.blockers[token] = it.boundsInRoot() }
}
