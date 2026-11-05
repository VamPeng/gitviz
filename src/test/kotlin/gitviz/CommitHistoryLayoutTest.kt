package gitviz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitHistoryLayoutTest {
    @Test
    fun `sheet opens once resizes and leaves the last commit reachable`() {
        var selected by mutableStateOf<Int?>(0)
        var detailBounds = Rect.Zero
        var listBounds = Rect.Zero
        var lastBounds = Rect.Zero
        var clicks = 0
        val listState = LazyListState()
        val scene = ImageComposeScene(800, 700, Density(1f)) {
            GitVizTheme {
                CommitHistoryLayout(selected != null, Modifier.fillMaxSize(),
                    list = { padding ->
                        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { listBounds = it.boundsInRoot() },
                            state = listState, contentPadding = PaddingValues(bottom = padding)) {
                            items((0 until 30).toList()) { index ->
                                Text("Commit $index", modifier = Modifier.fillMaxWidth().height(30.dp)
                                    .onGloballyPositioned { if (index == 29) lastBounds = it.boundsInRoot() }
                                    .clickable { selected = index; clicks++ })
                            }
                        }
                    },
                    detail = { modifier ->
                        Box(modifier.onGloballyPositioned { detailBounds = Rect(it.localToRoot(Offset.Zero), it.size.toSize()) }) {
                            Text("Details $selected")
                        }
                    },
                )
            }
        }
        var nanos = 0L
        fun frame() { nanos += 16_000_000L; scene.render(nanos) }
        fun settle() { repeat(40) { frame() } }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
        }
        try {
            settle()
            assertEquals(700f, listBounds.height, "The list must fill the entire background")
            val defaultBounds = detailBounds
            assertTrue(defaultBounds.height > 300f)

            click(100f, 45f)
            frame()
            assertEquals(1, selected)
            assertEquals(defaultBounds, detailBounds, "Changing commits must not restart the entrance")

            // 空白详情区域也不能把点击和滚轮传给底层列表。
            click(100f, 600f)
            assertEquals(1, clicks)
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 600f), scrollDelta = Offset(0f, 8f))
            settle()
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)

            click(400f, detailBounds.top - 12f)
            settle()
            assertEquals(Rect(0f, 24f, 800f, 700f), detailBounds, "Expanded details cover the entire main area below the handle")

            click(400f, detailBounds.top - 12f)
            settle()
            assertEquals(defaultBounds, detailBounds, "Down arrow restores the default height")

            runBlocking { listState.scrollToItem(29) }
            settle()
            assertTrue(lastBounds.height > 0f)
            assertTrue(lastBounds.bottom <= detailBounds.top - 24f, "The last row must scroll above the collapsed sheet")

            val start = Offset(100f, detailBounds.top - 12f)
            scene.sendPointerEvent(PointerEventType.Press, start)
            scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, 30f))
            frame()
            scene.sendPointerEvent(PointerEventType.Move, start - Offset(0f, 100f))
            frame()
            scene.sendPointerEvent(PointerEventType.Release, start - Offset(0f, 100f))
            settle()
            assertTrue(detailBounds.top < defaultBounds.top - 50f, "Dragging the top border resizes the sheet")
            click(400f, detailBounds.top - 12f)
            settle()
            assertEquals(defaultBounds, detailBounds, "Arrow direction follows manually resized height")

            selected = null
            settle()
            selected = 0
            frame()
            frame()
            val enteringTop = detailBounds.top
            settle()
            assertTrue(enteringTop > detailBounds.top, "A newly opened sheet slides up from the bottom: $enteringTop -> ${detailBounds.top}")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `sheet bounds stay valid for short windows`() {
        for (height in listOf(0, 100, 300, 700, 1200)) {
            val bounds = DetailSheetBounds(height.dp)
            assertTrue(bounds.minimum >= 0.dp)
            assertTrue(bounds.default in bounds.minimum..bounds.maximum)
            assertEquals(height.dp, bounds.maximum)
            assertEquals(bounds.minimum, bounds.clamp((-200).dp))
            assertEquals(bounds.maximum, bounds.clamp(2000.dp))
        }
    }
}
