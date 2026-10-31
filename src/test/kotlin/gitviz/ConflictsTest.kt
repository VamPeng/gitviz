package gitviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictsTest {

    private val sample = "top\n<<<<<<< HEAD\nmine\n=======\nyours\n>>>>>>> feature\nmiddle\n<<<<<<< HEAD\na\n=======\n>>>>>>> feature\nend\n"

    private val root: File = createTempDirectory("gitviz-conflict").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    private fun commit(file: String, content: String) {
        File(root, file).writeText(content)
        git("add", file)
        git("commit", "-q", "-m", "edit $file")
    }

    /** main 和 side 都改了 a 的同一行。 */
    private fun prepare(): GitCli {
        git("init", "-q", "--initial-branch=main")
        git("config", "user.name", "t")
        git("config", "user.email", "t@t")
        git("config", "commit.gpgsign", "false")
        commit("a", "one\n")
        git("checkout", "-q", "-b", "side")
        commit("a", "side\n")
        git("checkout", "-q", "main")
        commit("a", "main\n")
        return GitCli(root)
    }

    @Test
    fun `conflict view renders a real merge`() {
        val cli = prepare()
        cli.merge("side")
        val files = cli.status().filter { it.conflicted }
        val scene = ImageComposeScene(1112 * 2, 560 * 2, Density(2f)) {
            GitVizTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                    ConflictView(cli, files, cli.currentOperation(), {}, {}, {}, { _, _ -> }, Modifier.fillMaxSize())
                }
            }
        }
        var image = scene.render(0)
        repeat(8) { i ->
            Thread.sleep(120)
            image = scene.render((i + 1) * 100_000_000L)
        }
        File("build/screens").mkdirs()
        File("build/screens/conflict.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun `parses hunks and labels`() {
        val file = assertNotNull(parseConflict(sample))
        assertEquals("HEAD", file.oursLabel)
        assertEquals("feature", file.theirsLabel)
        assertEquals(listOf(listOf("mine"), listOf("a")), file.hunks.map { it.ours })
        assertEquals(listOf(listOf("yours"), emptyList()), file.hunks.map { it.theirs })
    }

    @Test
    fun `resolving applies each choice and keeps the rest`() {
        val file = assertNotNull(parseConflict(sample))
        assertEquals("top\nmine\nyours\nmiddle\nend\n", resolveConflict(file, mapOf(0 to Choice.BOTH, 1 to Choice.THEIRS)))
        assertEquals("top\nyours\nmiddle\na\nend\n", resolveConflict(file, mapOf(0 to Choice.THEIRS, 1 to Choice.OURS)))
    }

    @Test
    fun `no markers or broken markers cannot be resolved by hunk`() {
        assertNull(parseConflict("plain\n=======\nfile\n"))
        assertNull(parseConflict("<<<<<<< HEAD\nmine\n=======\nno end\n"))
    }

    @Test
    fun `equals signs outside a conflict stay as they are`() {
        val text = "=======\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> b\n=======\n"
        val file = assertNotNull(parseConflict(text))
        assertEquals("=======\nx\n=======\n", resolveConflict(file, mapOf(0 to Choice.OURS)))
    }

    @Test
    fun `windows line endings and the diff3 style survive`() {
        val crlf = "a\r\n<<<<<<< HEAD\r\nx\r\n=======\r\ny\r\n>>>>>>> b\r\nz\r\n"
        assertEquals("a\r\ny\r\nz\r\n", resolveConflict(assertNotNull(parseConflict(crlf)), mapOf(0 to Choice.THEIRS)))

        val diff3 = "<<<<<<< HEAD\nx\n||||||| base\nold\n=======\ny\n>>>>>>> b\n"
        assertEquals("x\ny\n", resolveConflict(assertNotNull(parseConflict(diff3)), mapOf(0 to Choice.BOTH)))
    }

    @Test
    fun `a merge can be finished after resolving the file`() {
        val cli = prepare()
        assertEquals(GitFailure.CONFLICT, cli.merge("side").failure)
        assertEquals(GitOperation.MERGE, cli.currentOperation())
        val file = cli.status().single { it.conflicted }

        val parsed = assertNotNull(parseConflict(cli.readWorkingFile(file.path)!!))
        assertTrue(cli.resolveFile(file.path, resolveConflict(parsed, mapOf(0 to Choice.BOTH))).ok)
        assertTrue(cli.status().none { it.conflicted })

        assertTrue(cli.continueOperation().ok)
        assertNull(cli.currentOperation())
        assertEquals("main\nside\n", File(root, "a").readText())
        assertEquals(2, git("rev-list", "--parents", "-n", "1", "HEAD").split(" ").size - 1)
    }

    @Test
    fun `a cherry pick can be finished after resolving`() {
        val cli = prepare()
        val side = git("rev-parse", "side")
        val info = CommitInfo(side, listOf("p"), "m", "m", "t", "t@t", 0, emptyList())
        assertEquals(GitFailure.CONFLICT, cli.cherryPick(info).failure)
        assertEquals(GitOperation.CHERRY_PICK, cli.currentOperation())

        assertTrue(cli.resolveWhole("a", ours = false).ok)
        assertTrue(cli.continueOperation().ok)
        assertNull(cli.currentOperation())
        assertEquals("side\n", File(root, "a").readText())
    }

    @Test
    fun `delete against modify picks a whole side`() {
        val cli = prepare()
        git("checkout", "-q", "side")
        git("rm", "-q", "a")
        git("commit", "-q", "-m", "delete a")
        git("checkout", "-q", "main")
        assertEquals(GitFailure.CONFLICT, cli.merge("side").failure)
        val file = cli.status().single { it.conflicted }
        assertNull(parseConflict(cli.readWorkingFile(file.path).orEmpty()))

        assertTrue(cli.resolveWhole("a", ours = false).ok)
        assertFalse(File(root, "a").exists())
        assertTrue(cli.continueOperation().ok)
    }
}
