@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InsightsHoverTest {
    @Test fun `heatmap immediately updates dates and clears stale hints in both themes`() {
        val language = Settings.language
        val dark = Palette.isDark
        Settings.language = Language.EN
        try {
            repeat(2) {
                HoverScene().use { ui ->
                    ui.frame()
                    val monday = ui.node("Mon").boundsInRoot
                    fun day(week: Int, weekday: Int) = Offset(monday.right + 8.5f + week * 14f, monday.top + 5.5f + weekday * 14f)
                    fun expect(text: String) {
                        // Render two frames, without waiting for a tooltip timer.
                        ui.frame(); ui.frame()
                        assertEquals(listOf(text), ui.hints(), "The displayed hint must describe the current day")
                    }

                    ui.move(day(0, 3))
                    expect("2026-01-01 · 5 commits")
                    ui.capture("heatmap")
                    ui.move(day(0, 4))
                    expect("2026-01-02 · 8 commits")
                    ui.move(day(1, 1))
                    expect("2026-01-06 · 0 commits")
                    ui.move(day(0, 0))
                    expect("2025-12-29 · outside range")
                    ui.move(day(44, 6))
                    expect("2026-11-08 · outside range")

                    // Crossing the gaps and several dates must never revive an old hint.
                    repeat(4) { week ->
                        ui.move(day(week, 2))
                        ui.move(day(week, 2) + Offset(7f, 0f))
                        ui.move(day(week, 4))
                    }
                    expect("2026-01-23 · 0 commits")
                    ui.move(Offset(950f, monday.top + 5f))
                    ui.frame(); ui.frame()
                    assertTrue(ui.hints().isEmpty(), "Leaving the heatmap hides the hint")

                    ui.move(day(0, 3))
                    expect("2026-01-01 · 5 commits")
                    ui.click(ui.node("‹").boundsInRoot.center)
                    ui.frame(); ui.frame()
                    assertTrue(ui.hints().isEmpty(), "Changing years clears the previous year's hint")
                    ui.move(day(1, 0))
                    expect("2025-01-06 · 0 commits")
                    ui.move(ui.node("$contributorName <test@example.test>").boundsInRoot.center)
                    ui.frame(); ui.frame()
                    assertTrue(ui.hints().isEmpty())
                    ui.capture("contributors")
                    ui.move(ui.node(hotFilePath).boundsInRoot.center)
                    ui.frame(); ui.frame()
                    ui.capture("hot-files")
                }
                Palette.toggle()
            }
        } finally {
            if (Palette.isDark != dark) Palette.toggle()
            Settings.language = language
        }
    }

    @Test fun `horizontal scrolling dismisses the old date and next movement shows the visible date`() {
        val language = Settings.language
        Settings.language = Language.EN
        try {
            HoverScene(420).use { ui ->
                ui.frame()
                val monday = ui.node("Mon").boundsInRoot
                val position = Offset(monday.right + 8.5f, monday.top + 47.5f)
                ui.move(position)
                ui.frame(); ui.frame()
                val original = ui.hints().single()
                assertEquals("2026-01-01 · 5 commits", original)
                ui.scroll(position)
                repeat(12) { ui.frame() }
                assertTrue(ui.hints().isEmpty(), "Scrolling must dismiss a date that moved out from under the pointer")
                ui.move(position + Offset(1f, 0f))
                ui.frame(); ui.frame()
                assertNotEquals(original, ui.hints().single(), "The next hover must describe the date now under the pointer")
            }
        } finally { Settings.language = language }
    }

    private class HoverScene(width: Int = 1000) : AutoCloseable {
        private val owners = mutableListOf<SemanticsOwner>()
        private val platform = object : PlatformContext by PlatformContext.Empty {
            override val windowInfo = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = IntSize(width, 700)
            }
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
            }
        }
        private val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(width, 700),
            coroutineContext = Dispatchers.Unconfined, platformContext = platform)
        private val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, 700)
        private var nanos = 0L

        private val data = run {
            val date = LocalDate.of(2026, 11, 7)
            val activity = mapOf(LocalDate.of(2026, 1, 1) to 5L, LocalDate.of(2026, 1, 2) to 8L)
            RepositoryInsights(emptyList(), LocalDate.of(2025, 1, 1), date, ZoneId.of("Asia/Taipei"),
                13, 13, 0, activity, listOf(ContributorActivity(contributorName, "test@example.test", 13)),
                listOf(FileActivity(hotFilePath, 13)))
        }
        init {
            scene.setContent { GitVizTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                    InsightsContents(data, Modifier.fillMaxSize())
                }
            } }
        }

        fun frame() { nanos += 16_000_000L; scene.render(surface.canvas.asComposeCanvas(), nanos) }
        fun move(position: Offset) { scene.sendPointerEvent(PointerEventType.Move, position) }
        fun scroll(position: Offset) { scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(12f, 0f)) }
        fun click(position: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, position)
            scene.sendPointerEvent(PointerEventType.Release, position)
        }
        private fun nodes(): List<SemanticsNode> {
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            return owners.flatMap { descendants(it.rootSemanticsNode) }
        }
        private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun node(text: String) = nodes().first { text(it) == text }
        fun hints() = nodes().mapNotNull(::text).filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2} · .*")) }
        fun capture(name: String) {
            val theme = if (Palette.isDark) "dark" else "light"
            val out = File("build/screens/insights-hover-$name-$theme.png").apply { parentFile.mkdirs() }
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { out.writeBytes(it.bytes) }
            }
        }
        override fun close() { scene.close(); surface.close() }
    }

    private companion object {
        val contributorName = "Contributor with a long display name ".repeat(5).trim()
        val hotFilePath = "src/long-module-name/".repeat(8) + "Main.kt"
    }
}
