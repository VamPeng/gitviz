package gitviz

import java.io.File
import java.lang.management.ManagementFactory

/**
 * 性能基准，不是单元测试，不会随 gradle test 运行。
 * 用法：java -Xmx1g -XX:+UseSerialGC -cp <测试运行时 classpath> gitviz.PerfBench <仓库目录>...
 * 每个仓库按应用的真实流程走一遍：首屏、翻到底、刷新、搜索、看提交改动、轮询工作区。
 */
object PerfBench {
    private val mx = ManagementFactory.getMemoryMXBean()

    private fun usedMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(50) }
        return mx.heapMemoryUsage.used / (1024 * 1024)
    }

    private inline fun <T> time(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        println("  %-44s %9.1f ms".format(label, (System.nanoTime() - t0) / 1e6))
        return r
    }

    /** 跑 n 次取中位数，用于短操作。 */
    private fun median(label: String, n: Int, block: () -> Unit) {
        val xs = (1..n).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }.sorted()
        println("  %-44s %9.1f ms  (median of $n, min %.1f, max %.1f)".format(label, xs[n / 2], xs.first(), xs.last()))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("JVM ${System.getProperty("java.version")}, max heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
        for (path in args) bench(File(path))
    }

    private fun bench(dir: File) {
        println("\n=== ${dir.path} ===")
        val base = usedMb()

        val repo = time("GitRepo.open") { GitRepo.open(dir) }

        // 首屏：和 App.open 一样读一页并排版
        var state = time("first page (open history + load 2000 + layout)") {
            val h = repo.openHistory()
            val r = h.load(GitRepo.PAGE_SIZE)
            RepoState(repo, r, GraphLayout.compute(r.commits, BranchColors(r.refs)), repo.repository.resolve("HEAD")?.name, h)
        }
        println("  refs: ${state.result.refs.size}, max graph width: ${state.rows.maxOfOrNull { it.width }}")

        // 一页一页翻到底，看每页的耗时会不会越来越慢
        val t0 = System.nanoTime()
        var pages = 1
        var worstPage = 0.0
        var lastPage = 0.0
        while (state.result.truncated) {
            val p0 = System.nanoTime()
            state = state.loadMore(GitRepo.PAGE_SIZE)
            lastPage = (System.nanoTime() - p0) / 1e6
            worstPage = maxOf(worstPage, lastPage)
            pages++
        }
        val n = state.result.commits.size
        println("  %-44s %9.1f ms  ($pages pages, $n commits, last page %.0f ms, worst %.0f ms)"
            .format("scroll to end (page by page)", (System.nanoTime() - t0) / 1e6, lastPage, worstPage))
        println("  max graph width (all): ${state.rows.maxOf { it.width }}")

        val heapAll = usedMb() - base
        println("  %-44s %9d MB".format("retained heap, full history loaded", heapAll))
        if (System.getProperty("histo") != null) {
            val pid = ProcessHandle.current().pid().toString()
            val out = ProcessBuilder("jcmd", pid, "GC.class_histogram").redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
            out.take(25).forEach { println("    $it") }
            return
        }

        // 拆开看全部历史时排序和排版各花多少
        val commits = state.result.commits
        median("topoSort (all loaded)", 5) { GraphLayout.topoSort(commits) }
        median("GraphLayout.compute (all loaded)", 5) { GraphLayout.compute(commits, BranchColors(state.result.refs)) }
        median("RepoState index build (all loaded)", 5) { commits.withIndex().associate { (i, c) -> c.id to i } }

        // 刷新：App.open 会把已加载的那么多一次读回来
        time("refresh with everything loaded (reopen)") {
            GitRepo.open(dir).use { r ->
                r.openHistory().use { h ->
                    val res = h.load(maxOf(GitRepo.PAGE_SIZE, n))
                    GraphLayout.compute(res.commits, BranchColors(res.refs))
                }
            }
        }

        // 搜索：已加载部分即时过滤，全部历史在后台搜
        median("filterCommits 'fix' (loaded, per keystroke)", 5) { filterCommits(commits, "fix") }
        time("searchCommits 'zzqqxx' (full walk, no hit)") { repo.searchCommits("zzqqxx") }
        time("searchCommits 'fix' (stops at 5000 hits)") { repo.searchCommits("fix") }

        // 点提交看改动
        val recent = commits.filter { it.parents.size == 1 }.take(30)
        val changes = time("changes() for 30 recent commits") { recent.map { repo.changes(it.id) } }
        val biggest = recent.zip(changes).maxByOrNull { it.second.size }
        if (biggest != null) {
            println("  biggest of those: ${biggest.second.size} files in ${biggest.first.shortId}")
            median("changes() on that commit", 5) { repo.changes(biggest.first.id) }
            median("diffText for all its files", 3) { biggest.second.forEach { repo.diffText(it) } }
        }
        val merge = commits.firstOrNull { it.parents.size > 1 }
        if (merge != null) time("changes() on a merge commit ${merge.shortId}") { repo.changes(merge.id) }

        // 文件历史和 blame：挑最近一个改动里的文件
        val file = changes.flatten().firstOrNull { it.type != org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE }?.path
        if (file != null) {
            val head = commits.first().id
            time("fileHistory $file") { repo.fileHistory(head, file) }
            time("blame $file") { repo.blame(head, file) }
            time("history filtered to that file (first page)") { repo.openHistory(file).use { it.load(GitRepo.PAGE_SIZE) } }
        }

        // 每 3 秒的工作区轮询
        repo.workTree?.let { wt ->
            val cli = GitCli(wt)
            median("poll: status()", 5) { cli.status() }
            median("poll: stashList()", 5) { cli.stashList() }
            median("poll: currentOperation()", 5) { cli.currentOperation() }
            median("poll: all three (one tick)", 5) { cli.status(); cli.stashList(); cli.currentOperation() }
        }

        repo.close()
        state = RepoState(repo, LoadResult(emptyList(), false, null, emptyList()), emptyList(), null)
    }
}
