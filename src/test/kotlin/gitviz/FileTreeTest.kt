package gitviz

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 文件页：目录从磁盘读，状态和已删除的文件来自 git status。 */
class FileTreeTest {
    init { Settings.language = Language.EN }

    private val root: File = createTempDirectory("gitviz-files").toFile()

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

    private fun write(path: String, text: String = "x") {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    /** 一个提交过的仓库：src/a.kt、src/b.kt、src/old/gone.kt、README.md，忽略 build/ 和 *.log。 */
    private fun repo(): GitCli {
        git("init", "-q")
        git("config", "user.name", "t")
        git("config", "user.email", "t@t")
        git("config", "commit.gpgsign", "false")
        write(".gitignore", "build/\n*.log\n")
        write("README.md")
        write("src/a.kt")
        write("src/b.kt")
        write("src/old/gone.kt")
        git("add", ".")
        git("commit", "-q", "-m", "init")
        return GitCli(root)
    }

    private fun listing(cli: GitCli, dir: String) = listDirectory(root, dir, TreeStatus(cli.treeStatus()))

    private fun names(l: DirListing) = l.entries.map { (if (it.isDir) "${it.name}/" else it.name) + if (it.missing) " (missing)" else "" }

    @Test
    fun `lists ignored files from disk and marks where the ignore rule matched`() {
        val cli = repo()
        write("build/libs/app.jar")
        write("debug.log")
        val top = listing(cli, "")
        assertEquals(listOf("build/", "src/", ".gitignore", "debug.log", "README.md"), names(top))
        val build = top.entries.first { it.name == "build" }
        assertEquals(FileMark.IGNORED, build.mark)
        assertTrue(build.ignoredRoot)
        assertEquals(FileMark.IGNORED, top.entries.first { it.name == "debug.log" }.mark)
        // 被忽略目录里面的东西 git 不会逐个报，靠祖先目录判断
        val inside = listing(cli, "build/libs").entries.single()
        assertEquals(FileMark.IGNORED, inside.mark)
        assertFalse(inside.ignoredRoot)
    }

    @Test
    fun `git directory is not listed`() {
        val cli = repo()
        assertFalse(listing(cli, "").entries.any { it.name == ".git" })
    }

    @Test
    fun `marks changes and flags folders that contain them`() {
        val cli = repo()
        write("src/a.kt", "changed")
        write("src/new.kt")
        val top = listing(cli, "")
        assertTrue(top.entries.first { it.name == "src" }.dirty)
        assertEquals(FileMark.CLEAN, top.entries.first { it.name == "README.md" }.mark)
        val src = listing(cli, "src").entries.associateBy { it.name }
        assertEquals(FileMark.MODIFIED, src.getValue("a.kt").mark)
        assertEquals(FileMark.UNTRACKED, src.getValue("new.kt").mark)
        assertEquals(FileMark.CLEAN, src.getValue("b.kt").mark)
    }

    @Test
    fun `deleted files and folders come back from git status`() {
        val cli = repo()
        File(root, "src/b.kt").delete()
        File(root, "src/old").deleteRecursively()
        val src = listing(cli, "src")
        assertEquals(listOf("old/ (missing)", "a.kt", "b.kt (missing)"), names(src))
        assertEquals(FileMark.DELETED, src.entries.first { it.name == "b.kt" }.mark)
        // 整个文件夹都删了，磁盘上没有它，也能展开看到里面删掉的文件
        val old = listing(cli, "src/old").entries.single()
        assertEquals("gone.kt", old.name)
        assertTrue(old.missing)
        assertEquals(FileMark.DELETED, old.mark)
    }

    @Test
    fun `committed deletions are gone`() {
        val cli = repo()
        git("rm", "-q", "src/b.kt")
        assertEquals(FileMark.DELETED, listing(cli, "src").entries.first { it.name == "b.kt" }.mark)
        git("commit", "-q", "-m", "drop b")
        assertEquals(listOf("old/", "a.kt"), names(listing(cli, "src")))
    }

    @Test
    fun `rm --cached shows as removed from the repository, not just untracked`() {
        val cli = repo()
        git("rm", "-q", "--cached", "src/b.kt")
        val status = TreeStatus(cli.treeStatus())
        val b = listDirectory(root, "src", status).entries.first { it.name == "b.kt" }
        assertFalse(b.missing)
        assertEquals(FileMark.REMOVED, b.mark)
        assertEquals('D', status.working("src/b.kt")?.index)
    }

    @Test
    fun `symlinked folders are not followed`() {
        val cli = repo()
        java.nio.file.Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        val link = listing(cli, "").entries.first { it.name == "loop" }
        assertFalse(link.isDir)
    }

    @Test
    fun `huge folders are cut off`() {
        val cli = repo()
        val many = File(root, "build/many").apply { mkdirs() }
        repeat(MAX_DIR_ENTRIES + 3) { File(many, "f$it").createNewFile() }
        val l = listing(cli, "build/many")
        assertEquals(MAX_DIR_ENTRIES, l.entries.size)
        assertEquals(3, l.omitted)
    }

    @Test
    fun `flattened tree has only folders and hides ignored ones on request`() {
        val cli = repo()
        write("build/sub/out.txt")
        val status = TreeStatus(cli.treeStatus())
        val listings = listOf("", "src").associateWith { listDirectory(root, it, status) }
        fun rows(showIgnored: Boolean, expanded: Set<String>) = flattenDirTree(listings, expanded, showIgnored).map {
            when (it) {
                is DirRow.Item -> "  ".repeat(it.depth) + it.entry.name
                is DirRow.Loading -> "  ".repeat(it.depth) + "…"
            }
        }
        assertEquals(listOf("build", "src"), rows(true, emptySet()))
        assertEquals(listOf("src"), rows(false, emptySet()))
        // src/old 还没读过，但列 src 时已经知道它里面没有子目录，展开了也不出“正在读取”
        assertEquals(listOf("build", "src", "  old"), rows(true, setOf("src", "src/old")))
        // 有子目录、还没读过的才先放一行“正在读取”
        assertEquals(listOf("build", "  …", "src"), rows(true, setOf("build")))
    }

    private fun subdirs(l: DirListing, name: String) = l.entries.first { it.name == name }.subdirs!!.let { it.any to it.visible }

    @Test
    fun `knows which folders have subfolders before opening them`() {
        val cli = repo()
        write("build/out/app.jar")
        write("design/logo.svg")
        val top = listing(cli, "")
        assertEquals(true to true, subdirs(top, "src"))      // src/old
        assertEquals(false to false, subdirs(top, "design"))  // 只有文件
        assertEquals(true to false, subdirs(top, "build"))    // 自己被忽略，隐藏被忽略的时候不出现
        assertEquals(null, top.entries.first { it.name == "README.md" }.subdirs)
    }

    @Test
    fun `ignored subfolders only count when ignored files are shown`() {
        val cli = repo()
        write("lib/build/x.class") // lib/build 被 build/ 规则忽略
        write("lib/a.kt")
        assertEquals(true to false, subdirs(listing(cli, ""), "lib"))
    }

    @Test
    fun `deleted subfolders still count`() {
        val cli = repo()
        File(root, "src/old").deleteRecursively()
        assertEquals(true to true, subdirs(listing(cli, ""), "src"))
    }

    @Test
    fun `probing stops at the limits and says it doesn't know`() {
        val cli = repo()
        repeat(5) { write("flat/f$it") }
        repeat(3) { write("d$it/x") }
        val status = TreeStatus(cli.treeStatus())
        // 每个子目录只看 2 项：flat 里 5 个文件看不完，不确定
        assertEquals(null to null, subdirs(listDirectory(root, "", status, ProbeLimits(perDir = 2)), "flat"))
        // 只看 1 个子目录：排在后面的都不确定
        val one = listDirectory(root, "", status, ProbeLimits(maxDirs = 1))
        assertEquals(false to false, subdirs(one, "d0"))
        assertEquals(null to null, subdirs(one, "d1"))
        // 总预算 1 项：第一个看完，后面的不确定
        val tight = listDirectory(root, "", status, ProbeLimits(budget = 1))
        assertEquals(false to false, subdirs(tight, "d0"))
        assertEquals(null to null, subdirs(tight, "d2"))
    }

    @Test
    fun `folders without subfolders get no arrow and never show loading`() {
        val cli = repo()
        write("design/logo.svg")
        val status = TreeStatus(cli.treeStatus())
        val listings = mapOf("" to listDirectory(root, "", status))
        // design 已知没有子目录：即使在展开列表里，也不往下摊，不出“正在读取”
        val rows = flattenDirTree(listings, setOf("design", "src"), showIgnored = true)
        val design = rows.filterIsInstance<DirRow.Item>().first { it.entry.name == "design" }
        assertEquals(false, design.hasSubdirs)
        assertFalse(design.expanded)
        assertEquals(1, rows.count { it is DirRow.Loading }) // 只有 src 下面那一行
    }

    @Test
    fun `reads only folders whose parents are all open`() {
        assertEquals(listOf("", "a", "a/b", "x/y"), directoriesToRead(setOf("a", "a/b", "c/d"), "x/y"))
        assertEquals(listOf("a/b", "a"), ancestorsOf("a/b/c"))
    }

    @Test
    fun `gitignore entries are anchored and literal`() {
        val cli = repo()
        File(root, ".gitignore").writeText("build/") // 没有结尾换行
        write("src/[id].kt")
        write("src/other.kt")
        appendToGitignore(root, "src/[id].kt", isDir = false)
        assertEquals("build/\n/src/\\[id].kt\n", File(root, ".gitignore").readText())
        val src = listing(cli, "src").entries.associateBy { it.name }
        assertEquals(FileMark.IGNORED, src.getValue("[id].kt").mark)
        assertEquals(FileMark.UNTRACKED, src.getValue("other.kt").mark)
    }

    @Test
    fun `sizes read like a file manager`() {
        assertEquals("812 B", formatSize(812))
        assertEquals("4.2 KB", formatSize(4300))
        assertEquals("1.5 MB", formatSize(1_572_864))
    }
}
