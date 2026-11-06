@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.key.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class LayerThreeUiTest {
    @Test fun `new repository and service views render without network in both themes`() = RepositoryFixture().use { r ->
        r.commit(); r.commit("b", "feature\n")
        val dark = Palette.isDark; val language = Settings.language
        val actions = GitActions({}, { _, _, _ -> }, {})
        try {
            repeat(2) {
                Settings.language = if (it == 0) Language.EN else Language.ZH
                val theme = if (Palette.isDark) "dark" else "light"
                fun render(name: String, click: Offset? = null, content: @Composable () -> Unit) {
                    val scene = ImageComposeScene(1112, 800) { GitVizTheme { Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) { content() } } }
                    try {
                        repeat(24) { frame -> scene.render(frame * 16_000_000L); Thread.sleep(40) }
                        if (click != null) {
                            scene.sendPointerEvent(PointerEventType.Press, click); scene.sendPointerEvent(PointerEventType.Release, click)
                            repeat(24) { frame -> scene.render((frame + 24) * 16_000_000L); Thread.sleep(40) }
                        }
                        save(scene, "$name-$theme")
                    }
                    finally { scene.close() }
                }
                render("tools") { RepositoryToolsView(r.root, r.cli, emptyList(), actions, 0, false, {}, {}, Modifier.fillMaxSize()) }
                render("lfs", Offset(270f, 97f)) { RepositoryToolsView(r.root, r.cli, emptyList(), actions, 0, false, {}, {}, Modifier.fillMaxSize()) }
                render("subtree", Offset(355f, 97f)) { RepositoryToolsView(r.root, r.cli, emptyList(), actions, 0, false, {}, {}, Modifier.fillMaxSize()) }
                render("forge") { ForgeView(r.cli, Modifier.fillMaxSize()) }
                render("ai", Offset(1000f, 261f)) { AiView(r.cli, AiScope(AiKind.BRANCH), {}, remember { AiConnection() }, remember { CommitDraft() }, actions, 0, false, {}, Modifier.fillMaxSize()) }
                render("interactive-rebase") { InteractiveRebaseDialog(r.cli, "HEAD^", false, {}, {}) }
                Palette.toggle()
            }
        } finally {
            if (Palette.isDark != dark) Palette.toggle()
            Settings.language = language
        }
    }

    @Test fun `interactive rebase base input edits and loads the reviewed range`() = RepositoryFixture().use { r ->
        r.commit(); r.commit("b", "second\n"); r.commit("c", "third\n")
        var reviewed: OperationIntent? = null
        val scene = ImageComposeScene(1112, 800) { GitVizTheme { InteractiveRebaseDialog(r.cli, "HEAD^", false, {}, { reviewed = it }) } }
        try {
            var frame = 0L
            fun draw() { repeat(20) { scene.render(++frame * 16_000_000L); Thread.sleep(30) } }
            fun click(x: Float, y: Float) { scene.sendPointerEvent(PointerEventType.Press, Offset(x, y)); scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)); draw() }
            draw(); click(200f, 175f)
            repeat(5) {
                scene.sendKeyEvent(KeyEvent(Key.Backspace, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Backspace, KeyEventType.KeyUp))
            }
            "HEAD~2".forEach { c -> scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = c.code,
                nativeEvent = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, c))) }
            draw(); click(990f, 175f); click(965f, 720f)
            save(scene, "interactive-input")
            assertEquals(2, reviewed?.interactive?.commits?.size)
            assertEquals("third", r.git("log", "-1", "--format=%s"))
        } finally { scene.close() }
    }
    @Test fun `insights and expanded teaching feedback render in both themes`() {
        val teaching = Settings.teachingMode
        val dark = Palette.isDark
        val language = Settings.language
        Settings.updateTeachingMode(true)
        Settings.language = Language.ZH
        try {
            repeat(2) {
                val date = LocalDate.of(2026, 11, 6)
                val activity = (0L..300L).filter { it % 3 != 0L }.associate { date.minusDays(it) to (it % 9 + 1) }
                val data = RepositoryInsights(emptyList(), date.minusDays(364), date, ZoneId.of("Asia/Taipei"), 1000, activity.values.sum(), 12, activity,
                    listOf(ContributorActivity("开发者 A", "a@example.test", 420), ContributorActivity("开发者 B", "b@example.test", 255)),
                    listOf(FileActivity("src/main/kotlin/gitviz/Main.kt", 50), FileActivity("docs/ROADMAP.md", 25)))
                val theme = if (Palette.isDark) "dark" else "light"
                val scene = ImageComposeScene(1000, 700) {
                    GitVizTheme { Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) { InsightsContents(data, Modifier.fillMaxSize()) } }
                }
                try { scene.render(0); save(scene, "insights-$theme") } finally { scene.close() }
                val lessons = Teaching.capture(true) {
                    Teaching.command(listOf("add", "--", ":(literal)src/main.kt"))
                    Teaching.command(listOf("apply", "--cached", "--check", "--whitespace=nowarn", "-"), input = true, temporaryIndex = true)
                    GitResult(null, "")
                }
                val status = describeAction("正在暂存", "已暂存", lessons)
                val banner = ImageComposeScene(1000, 440) {
                    GitVizTheme { Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                        Column { NetBanner(status, {}, {}) }
                    } }
                }
                try {
                    banner.render(0)
                    repeat(3) { banner.render((it + 1) * 16_000_000L) }
                    save(banner, "teaching-$theme")
                } finally { banner.close() }
                Palette.toggle()
            }
        } finally {
            if (Palette.isDark != dark) Palette.toggle()
            Settings.updateTeachingMode(teaching)
            Settings.language = language
        }
    }

    private fun save(scene: ImageComposeScene, name: String) {
        val out = File("build/screens/$name.png").apply { parentFile.mkdirs() }
        out.writeBytes(scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        assertTrue(out.length() > 0)
    }
}
