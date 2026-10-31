package gitviz

import java.io.File
import java.util.concurrent.TimeUnit

enum class GitFailure { REJECTED, AUTH, CONFLICT, OTHER }

enum class ResetMode(val flag: String, val label: String, val hint: String) {
    SOFT("--soft", "软重置", "撤销提交，改动保留在暂存区"),
    MIXED("--mixed", "混合重置", "撤销提交，改动保留在工作区"),
    HARD("--hard", "硬重置", "撤销提交并丢弃所有改动，无法恢复"),
}

/** 一次网络操作的结果。failure 为 null 表示成功，output 是 git 的原始输出。 */
data class GitResult(val failure: GitFailure?, val output: String) {
    val ok: Boolean get() = failure == null
}

/** 工作区里一个有改动的文件。index、worktree 是 git status 的两列状态字母。 */
data class WorkingFile(val path: String, val oldPath: String?, val index: Char, val worktree: Char) {
    val untracked: Boolean get() = index == '?'
    val hasStaged: Boolean get() = index != ' ' && index != '?'
    val hasUnstaged: Boolean get() = worktree != ' '
}

/** 一条储藏。ref 是 stash@{n}，序号会随增删变化，id 才是它不变的身份。 */
data class StashEntry(val ref: String, val id: String, val message: String, val branch: String, val timeSeconds: Long)

/** 储藏里的一个文件。code 是 A、M、D 这样的状态字母，untracked 表示储藏时还是未跟踪的新文件。 */
data class StashFile(val path: String, val code: Char, val untracked: Boolean = false)

private const val FieldSeparator = '\u001f'

/** 解析 stash list，每行是 ref、哈希、时间、说明。说明形如 “On main: 消息” 或 “WIP on main: abc1234 消息”。 */
internal fun parseStashList(text: String): List<StashEntry> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
    val fields = line.split(FieldSeparator, limit = 4)
    if (fields.size < 4) return@mapNotNull null
    val subject = fields[3]
    val branch = subject.substringBefore(':').removePrefix("WIP on ").removePrefix("On ").trim()
    StashEntry(fields[0], fields[1], subject.substringAfter(": ", subject), branch, fields[2].toLongOrNull() ?: 0)
}.toList()

/** 解析 git diff --name-status -z：状态和路径用 NUL 分隔，重命名和复制有两段路径。 */
internal fun parseNameStatus(text: String): List<StashFile> {
    val parts = text.split('\u0000').filter { it.isNotEmpty() }
    val files = ArrayList<StashFile>()
    var i = 0
    while (i < parts.size) {
        val code = parts[i++].first()
        if (code == 'R' || code == 'C') i++ // 跳过旧路径
        parts.getOrNull(i++)?.let { files += StashFile(it, code) }
    }
    return files
}

/** 解析 git status --porcelain -z：用 NUL 分隔，路径不转义；重命名多带一段旧路径。 */
internal fun parseStatus(text: String): List<WorkingFile> {
    val parts = text.split('\u0000')
    val files = ArrayList<WorkingFile>()
    var i = 0
    while (i < parts.size) {
        val entry = parts[i++]
        if (entry.length < 4) continue
        val index = entry[0]
        val worktree = entry[1]
        val moved = index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C'
        val oldPath = if (moved) parts.getOrNull(i++) else null
        files += WorkingFile(entry.substring(3), oldPath, index, worktree)
    }
    return files
}

/** 从 git 的输出判断失败原因，界面据此决定显示什么、提供什么操作。 */
internal fun classifyFailure(output: String): GitFailure = when {
    "CONFLICT" in output || "Automatic merge failed" in output || "after resolving the conflicts" in output -> GitFailure.CONFLICT
    "[rejected]" in output || "non-fast-forward" in output || "fetch first" in output -> GitFailure.REJECTED
    listOf(
        "Permission denied", "Authentication failed", "could not read Username", "could not read Password",
        "Host key verification failed", "terminal prompts disabled",
    ).any { it in output } -> GitFailure.AUTH
    else -> GitFailure.OTHER
}

/**
 * 用系统的 git 做网络操作，SSH 配置、钥匙串和 credential helper 都沿用用户现有的。
 * 所有方法都会阻塞，请在 IO 线程调用。
 */
class GitCli(private val workTree: File) {

    /** 推送当前分支；没有上游时设到 origin，以后直接 git push 也能用。 */
    fun push(): GitResult {
        val branch = capture("symbolic-ref", "--short", "-q", "HEAD")
            ?: return GitResult(GitFailure.OTHER, "现在不在任何分支上，无法推送")
        val hasUpstream = capture("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}") != null
        return if (hasUpstream) run("push") else run("push", "-u", "origin", branch)
    }

    fun pull(): GitResult = run("pull", "--no-rebase", "--no-edit")

    fun status(): List<WorkingFile> {
        val (exit, output) = exec(listOf("--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=all"))
        return if (exit == 0) parseStatus(output) else emptyList()
    }

    fun stage(file: WorkingFile): GitResult = run("add", "--", literal(file.path))

    fun unstage(file: WorkingFile): GitResult =
        if (hasCommits()) run("restore", "--staged", "--", literal(file.path))
        else run("rm", "--cached", "-q", "--", literal(file.path))

    /** 丢弃工作区的改动，已暂存的部分不动。未跟踪的文件会被删除。 */
    fun discard(file: WorkingFile): GitResult =
        if (file.untracked) run("clean", "-f", "-q", "--", literal(file.path))
        else run("restore", "--", literal(file.path))

    fun diff(file: WorkingFile, staged: Boolean): String {
        val args = when {
            file.untracked -> listOf("diff", "--no-color", "--no-index", "--", "/dev/null", file.path)
            staged -> listOf("diff", "--no-color", "--cached", "--", literal(file.path))
            else -> listOf("diff", "--no-color", "--", literal(file.path))
        }
        return exec(args).second.take(GitRepo.MAX_DIFF_CHARS)
    }

    fun commit(message: String, amend: Boolean): GitResult =
        if (amend) run("commit", "--amend", "-m", message) else run("commit", "-m", message)

    fun lastCommitMessage(): String = capture("log", "-1", "--format=%B").orEmpty()

    fun createBranch(name: String, commitId: String): GitResult = run("branch", name, commitId)

    fun switchTo(branch: String): GitResult = run("switch", branch)

    /** 检出某个提交，进入分离 HEAD。 */
    fun checkout(commitId: String): GitResult = run("switch", "--detach", commitId)

    fun renameBranch(from: String, to: String): GitResult = run("branch", "-m", from, to)

    /** 只删已合并的分支；没合并的 git 会拒绝，提交不会丢。 */
    fun deleteBranch(name: String): GitResult = run("branch", "-d", name)

    fun tag(name: String, commitId: String): GitResult = run("tag", name, commitId)

    fun merge(ref: String): GitResult = run("merge", "--no-edit", ref)

    fun cherryPick(commit: CommitInfo): GitResult = run("cherry-pick", *mainlineFor(commit), commit.id)

    fun revert(commit: CommitInfo): GitResult = run("revert", "--no-edit", *mainlineFor(commit), commit.id)

    /** 合并提交有两个父提交，要指明相对哪一个，这里取第一个。 */
    private fun mainlineFor(commit: CommitInfo) = if (commit.parents.size > 1) arrayOf("-m", "1") else emptyArray()

    fun reset(commitId: String, mode: ResetMode): GitResult = run("reset", mode.flag, commitId)

    fun stashList(): List<StashEntry> {
        val format = listOf("%gd", "%H", "%ct", "%gs").joinToString("%x1f")
        val (exit, output) = exec(listOf("stash", "list", "--format=$format"))
        return if (exit == 0) parseStashList(output) else emptyList()
    }

    /** 收起工作区的全部改动，包括未跟踪的文件。 */
    fun stash(message: String): GitResult =
        if (message.isBlank()) run("stash", "push", "--include-untracked")
        else run("stash", "push", "--include-untracked", "-m", message)

    fun stashFiles(entry: StashEntry): List<StashFile> {
        val tracked = parseNameStatus(exec(listOf("diff", "--name-status", "-z", "${entry.id}^1", entry.id)).second)
        val untracked = if (hasUntracked(entry)) {
            exec(listOf("ls-tree", "-r", "--name-only", "-z", "${entry.id}^3")).second.split('\u0000')
                .filter { it.isNotEmpty() }.map { StashFile(it, 'A', untracked = true) }
        } else emptyList()
        return tracked + untracked
    }

    fun stashDiff(entry: StashEntry, file: StashFile): String {
        val range = if (file.untracked) listOf(EMPTY_TREE, "${entry.id}^3") else listOf("${entry.id}^1", entry.id)
        return exec(listOf("diff", "--no-color") + range + listOf("--", literal(file.path))).second.take(GitRepo.MAX_DIFF_CHARS)
    }

    fun applyStash(entry: StashEntry): GitResult = stashChange(entry, "apply")

    /** 应用并删除。有冲突时 git 会保留储藏。 */
    fun popStash(entry: StashEntry): GitResult = stashChange(entry, "pop")

    fun dropStash(entry: StashEntry): GitResult = ifStillThere(entry) { run("stash", "drop", "-q", entry.ref) }

    private fun stashChange(entry: StashEntry, command: String) = ifStillThere(entry) {
        val result = run("stash", command, entry.ref)
        // 储藏没有 --abort，冲突的文件已经写进工作区，只能到本地改动里处理
        if (result.failure == GitFailure.CONFLICT) GitResult(GitFailure.OTHER, "有冲突，文件已写入工作区，储藏保留。") else result
    }

    /** stash@{n} 的序号会变，动手前核对它还是用户选的那一条，不然可能删错。 */
    private fun ifStillThere(entry: StashEntry, action: () -> GitResult): GitResult =
        if (capture("rev-parse", "-q", "--verify", entry.ref) == entry.id) action()
        else GitResult(GitFailure.OTHER, "储藏列表已经变了，请重新选择")

    private fun hasUntracked(entry: StashEntry) = capture("rev-parse", "-q", "--verify", "${entry.id}^3") != null

    /** 放弃进行到一半的合并、cherry-pick 或回滚，回到操作之前。 */
    fun abortOperation(): GitResult {
        val gitDir = workTree.resolve(capture("rev-parse", "--git-dir") ?: ".git")
        return when {
            gitDir.resolve("MERGE_HEAD").exists() -> run("merge", "--abort")
            gitDir.resolve("CHERRY_PICK_HEAD").exists() -> run("cherry-pick", "--abort")
            gitDir.resolve("REVERT_HEAD").exists() -> run("revert", "--abort")
            else -> GitResult(GitFailure.OTHER, "没有进行中的操作")
        }
    }

    private fun hasCommits(): Boolean = capture("rev-parse", "-q", "--verify", "HEAD") != null

    /** 按字面匹配路径，不然 [id].tsx 这样的文件名会被当成通配符，可能操作到别的文件。 */
    private fun literal(path: String) = ":(literal)$path"

    private fun capture(vararg args: String): String? {
        val (exit, output) = exec(args.toList())
        return output.trim().takeIf { exit == 0 && it.isNotEmpty() }
    }

    private fun run(vararg args: String): GitResult {
        val (exit, output) = exec(args.toList())
        return GitResult(if (exit == 0) null else classifyFailure(output), output.trim())
    }

    private fun exec(args: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workTree)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" } // 没有终端可以回答，要密码时直接失败，不要卡住
            .start()
        val output = StringBuilder()
        // 原样读取，不能按行拼：-z 的输出用 NUL 分隔，多一个换行就成了多一个文件
        val pump = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return 1 to "git 运行超过 $TIMEOUT_SECONDS 秒，已停止"
        }
        pump.join()
        return process.exitValue() to output.toString()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
        const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904" // git 里空目录的固定哈希
    }
}
