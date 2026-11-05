package gitviz

import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkTreeWatcherTest {
    private val root: File = createTempDirectory("gitviz-watch").toFile().canonicalFile
    private val gitDir = File(root, ".git")
    private val changes = Semaphore(0)
    private var watcher: AutoCloseable? = null

    init {
        val p = ProcessBuilder("git", "init", "-q", root.path).redirectErrorStream(true).start()
        check(p.waitFor() == 0)
    }

    @AfterTest
    fun cleanUp() {
        watcher?.close()
        root.deleteRecursively()
    }

    private fun changed(seconds: Long) = changes.tryAcquire(seconds, TimeUnit.SECONDS).also { changes.drainPermits() }

    /** 等监听真正开始，再把之前的事件清掉。 */
    private fun settle(seconds: Long) {
        Thread.sleep(seconds * 1000)
        changes.drainPermits()
    }

    @Test
    fun `filter ignores object and log churn inside the git dir`() {
        val f = ChangeFilter(listOf("/r/.git"))
        assertTrue(f.isRelevant("/r/src/Main.kt"))
        assertTrue(f.isRelevant("/r/src/"))
        assertTrue(f.isRelevant("/r/.git"))
        assertTrue(f.isRelevant("/r/.git/"))
        assertTrue(f.isRelevant("/r/.git/index"))
        assertTrue(f.isRelevant("/r/.git/refs/heads/main"))
        assertTrue(f.isRelevant("/r/.git/info/exclude"))
        assertFalse(f.isRelevant("/r/.git/objects/ab"))
        assertFalse(f.isRelevant("/r/.git/objects"))
        assertFalse(f.isRelevant("/r/.git/logs/refs/heads/main"))
        assertFalse(f.isRelevant("/r/.git/lfs/objects/x"))
        // 名字只是以 .git 开头的不算 git 目录
        assertTrue(f.isRelevant("/r/.github/objects/x"))
    }

    @Test
    fun `filter handles windows separators`() {
        val f = ChangeFilter(listOf("C:\\r\\.git"))
        assertFalse(f.isRelevant("C:\\r\\.git\\objects\\ab"))
        assertTrue(f.isRelevant("C:\\r\\.git\\index"))
        assertTrue(f.isRelevant("C:\\r\\src\\a.txt"))
    }

    @Test
    fun `platform watcher reports edits in the work tree but not object writes`() {
        watcher = watchWorkTree(listOf(root), listOf(gitDir)) { changes.release() }
        assertNotNull(watcher, "watcher should start on this platform")
        settle(1)

        File(gitDir, "objects/ab").mkdirs()
        File(gitDir, "objects/ab/cdef").writeText("x")
        assertFalse(changed(2), "object writes should not count")

        File(root, "a.txt").writeText("hello")
        assertTrue(changed(5), "editing a file should count")

        File(gitDir, "index").writeText("x")
        assertTrue(changed(5), "touching the index should count")
    }

    @Test
    fun `directory by directory watcher picks up new folders`() {
        // macOS 的 JDK 不支持 FILE_TREE，这里走的是 Linux 那条逐个目录注册的路
        watcher = JdkWatcher(listOf(root), ChangeFilter(listOf(gitDir.path)), { changes.release() }, highSensitivity = true)
        settle(3)

        File(root, "a.txt").writeText("hello")
        assertTrue(changed(10), "editing a file should count")

        File(root, "sub").mkdirs()
        assertTrue(changed(10), "creating a folder should count")
        settle(3) // 等新目录注册上

        File(root, "sub/b.txt").writeText("inside")
        assertTrue(changed(10), "a file in the new folder should count")
    }
}
