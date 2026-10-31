package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** 离屏渲染主界面，图片存到 build/screens，用来看布局和两种主题，不需要打开窗口。 */
class ScreenshotTest {

    private fun render(name: String) {
        val repo = GitRepo.open(File(System.getenv("GITVIZ_SCREENSHOT_REPO") ?: "."))
        val result = repo.loadCommits()
        val state = RepoState(repo, result, GraphLayout.compute(result.commits), repo.repository.resolve("HEAD")?.name)
        val scene = ImageComposeScene(1360 * 2, 860 * 2, Density(2f)) {
            GitVizTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                    var selected by remember { mutableStateOf(result.commits.firstOrNull()) }
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(248.dp).fillMaxHeight().background(Palette.Rail)) {
                            Spacer(Modifier.height(TitleBarHeight))
                            RefSidebar(result.refs, "", selected?.id, { true }, {}, Modifier.fillMaxWidth().weight(1f))
                            HorizontalDivider()
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                IconButton(if (Palette.isDark) Icons.Sun else Icons.Moon) {}
                            }
                        }
                        VerticalDivider()
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Toolbar(state, false, 18.dp, "", {}, {}, {}, netBusy = false, onPull = {}, onPush = {}, onNewBranch = {}, canStash = true, onStash = {})
                            HorizontalDivider()
                            CommitList(state, result.commits.indices.toList(), selected, rememberLazyListState(), { selected = it },
                                Modifier.fillMaxWidth().weight(0.46f))
                            HorizontalDivider()
                            CommitDetail(repo, selected, {}, Modifier.fillMaxWidth().weight(0.54f))
                            HorizontalDivider()
                            StatusBar(state)
                        }
                    }
                }
            }
        }
        // 文件列表和 diff 是异步读的，多渲染几帧等它们出来
        var image = scene.render(0)
        repeat(12) { i ->
            Thread.sleep(150)
            image = scene.render((i + 1) * 100_000_000L)
        }
        val out = File("build/screens/$name.png").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        repo.close()
        assertTrue(out.length() > 0)
    }

    /** 工作区干净时没什么可看，所以只在指定了仓库时才渲染。 */
    @Test
    fun `changes view renders when a repository is given`() {
        val dir = System.getenv("GITVIZ_SCREENSHOT_REPO") ?: return
        val cli = GitCli(File(dir))
        val files = cli.status()
        val scene = ImageComposeScene(1112 * 2, 700 * 2, Density(2f)) {
            GitVizTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                    ChangesView(cli, files, "main", {}, { _, _ -> }, Modifier.fillMaxSize())
                }
            }
        }
        var image = scene.render(0)
        repeat(10) { i ->
            Thread.sleep(150)
            image = scene.render((i + 1) * 100_000_000L)
        }
        val out = File("build/screens/changes.png").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(files.isNotEmpty())
    }

    /** 只在指定了有储藏的仓库时才渲染。 */
    @Test
    fun `stash view renders when a repository is given`() {
        val dir = System.getenv("GITVIZ_SCREENSHOT_REPO") ?: return
        val cli = GitCli(File(dir))
        val stashes = cli.stashList()
        val scene = ImageComposeScene(1112 * 2, 700 * 2, Density(2f)) {
            GitVizTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                    StashView(cli, stashes, GitActions({}, { _, _, _ -> }, {}), Modifier.fillMaxSize())
                }
            }
        }
        var image = scene.render(0)
        repeat(10) { i ->
            Thread.sleep(150)
            image = scene.render((i + 1) * 100_000_000L)
        }
        File("build/screens/stash.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    private fun renderPopup(name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(520 * 2, 460 * 2, Density(2f)) {
            GitVizTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) { content() }
            }
        }
        scene.render(0)
        val image = scene.render(500_000_000L)
        val out = File("build/screens/$name.png").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 0)
    }

    @Test
    fun `menus and dialogs render`() {
        val commit = CommitInfo("abcdef0123", emptyList(), "m", "m", "t", "t@t", 0, emptyList())
        val actions = GitActions({}, { _, _, _ -> }, {})
        renderPopup("menu") { GitMenuPopup(commitMenu(commit, isHead = false, "main", actions)) {} }
        renderPopup("reset") { ActionDialog(DialogSpec("重置 main", "把 main 移到 abcdef0。", "重置", danger = true, modes = true) { _, _ -> }) {} }
    }

    @Test
    fun `main window renders in both themes`() {
        val start = Palette.isDark
        render(if (start) "dark" else "light")
        Palette.toggle()
        render(if (start) "light" else "dark")
        Palette.toggle()
    }
}
