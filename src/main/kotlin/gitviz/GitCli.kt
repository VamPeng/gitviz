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

    /** 还没解决的冲突：UU、AA、DD，以及一边删除一边修改的 AU、UA、DU、UD。 */
    val conflicted: Boolean get() = index == 'U' || worktree == 'U' || (index == worktree && (index == 'A' || index == 'D'))
}

/** 进行到一半、等待解决冲突的操作。 */
enum class GitOperation(val label: String) { MERGE("合并"), CHERRY_PICK("Cherry-pick"), REVERT("回滚") }

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
        return if (hasUpstream) network("push") else network("push", "-u", "origin", branch)
    }

    fun pull(): GitResult = network("pull", "--no-rebase", "--no-edit")

    /** 把 url 克隆到当前目录下的 folder。 */
    fun clone(url: String, folder: String): GitResult = network("clone", "--", url, folder)

    fun config(key: String): String? = capture("config", "--get", key)

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

    fun currentOperation(): GitOperation? {
        val gitDir = workTree.resolve(capture("rev-parse", "--git-dir") ?: ".git")
        return when {
            gitDir.resolve("MERGE_HEAD").exists() -> GitOperation.MERGE
            gitDir.resolve("CHERRY_PICK_HEAD").exists() -> GitOperation.CHERRY_PICK
            gitDir.resolve("REVERT_HEAD").exists() -> GitOperation.REVERT
            else -> null
        }
    }

    /** 放弃进行到一半的合并、cherry-pick 或回滚，回到操作之前。 */
    fun abortOperation(): GitResult = when (currentOperation()) {
        GitOperation.MERGE -> run("merge", "--abort")
        GitOperation.CHERRY_PICK -> run("cherry-pick", "--abort")
        GitOperation.REVERT -> run("revert", "--abort")
        null -> GitResult(GitFailure.OTHER, "没有进行中的操作")
    }

    /** 冲突都解决后完成操作。提交说明用 git 准备好的，不打开编辑器。 */
    fun continueOperation(): GitResult = when (currentOperation()) {
        GitOperation.MERGE -> run("commit", "--no-edit")
        GitOperation.CHERRY_PICK -> run("-c", "core.editor=true", "cherry-pick", "--continue")
        GitOperation.REVERT -> run("-c", "core.editor=true", "revert", "--continue")
        null -> GitResult(GitFailure.OTHER, "没有进行中的操作")
    }

    /** 工作区里的文件内容，用来解析冲突标记。文件不存在时（一边删除了）返回 null。 */
    fun readWorkingFile(path: String): String? = workTree.resolve(path).takeIf { it.isFile }?.readText()

    /** 写入解决后的内容，并标记这个文件已解决。 */
    fun resolveFile(path: String, content: String): GitResult {
        workTree.resolve(path).writeText(content)
        return run("add", "--", literal(path))
    }

    /** 整个文件用某一边的版本。那一边已经删除这个文件时，就删除它。 */
    fun resolveWhole(path: String, ours: Boolean): GitResult {
        val stage = if (ours) 2 else 3
        return if (capture("rev-parse", "-q", "--verify", ":$stage:$path") != null) {
            val checkedOut = run("checkout", if (ours) "--ours" else "--theirs", "--", literal(path))
            if (checkedOut.ok) run("add", "--", literal(path)) else checkedOut
        } else {
            run("rm", "-q", "--", literal(path))
        }
    }

    private fun hasCommits(): Boolean = capture("rev-parse", "-q", "--verify", "HEAD") != null

    /** 按字面匹配路径，不然 [id].tsx 这样的文件名会被当成通配符，可能操作到别的文件。 */
    private fun literal(path: String) = ":(literal)$path"

    private fun capture(vararg args: String): String? {
        val (exit, output) = exec(args.toList())
        return output.trim().takeIf { exit == 0 && it.isNotEmpty() }
    }

    private fun run(vararg args: String): GitResult = toResult(exec(args.toList()))

    /** 会连接远程的命令：可能要等用户输入，超时更长，也要接上系统的密码对话框。 */
    private fun network(vararg args: String): GitResult = toResult(exec(args.toList(), NETWORK_TIMEOUT_SECONDS, Askpass.environment()))

    private fun toResult(execution: Pair<Int, String>): GitResult {
        val (exit, output) = execution
        return GitResult(if (exit == 0) null else classifyFailure(output), output.trim())
    }

    private fun exec(
        args: List<String>,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workTree)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0" // 没有终端可以回答，不要卡在终端提示上
                environment().putAll(extraEnvironment)
            }
            .start()
        val output = StringBuilder()
        // 原样读取，不能按行拼：-z 的输出用 NUL 分隔，多一个换行就成了多一个文件
        val pump = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return 1 to "git 运行超过 $timeoutSeconds 秒，已停止"
        }
        pump.join()
        return process.exitValue() to output.toString()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
        const val NETWORK_TIMEOUT_SECONDS = 600L
        const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904" // git 里空目录的固定哈希
    }
}

/** 克隆地址里的仓库名，比如 git@github.com:me/repo.git 得到 repo。 */
internal fun repoNameFromUrl(url: String): String =
    url.trim().trimEnd('/').removeSuffix(".git").substringAfterLast('/').substringAfterLast(':').ifEmpty { "repo" }

/**
 * git 和 ssh 要密码时调用的小脚本，由它弹出 macOS 系统对话框。
 * 密码由系统对话框直接交给 git，不经过 GitViz，所以应用既看不到也存不了。
 * 其他系统没有这个脚本，要密码时直接失败并提示。
 */
internal object Askpass {
    private const val Script = """#!/bin/sh
exec /usr/bin/osascript - "$1" <<'APPLESCRIPT'
on run argv
    set promptText to item 1 of argv
    activate
    if promptText contains "yes/no" then
        display dialog promptText buttons {"取消", "信任"} default button "取消" cancel button "取消" with title "GitViz" with icon caution
        return "yes"
    end if
    if promptText contains "assword" or promptText contains "assphrase" or promptText contains "PIN" then
        return text returned of (display dialog promptText default answer "" with hidden answer with title "GitViz")
    end if
    return text returned of (display dialog promptText default answer "" with title "GitViz")
end run
APPLESCRIPT
"""

    private val path: String? by lazy {
        if (!isMac) return@lazy null
        val file = File.createTempFile("gitviz-askpass", ".sh")
        file.writeText(Script)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
        file.deleteOnExit()
        file.path
    }

    fun environment(): Map<String, String> {
        val script = path ?: return emptyMap()
        return mapOf("GIT_ASKPASS" to script, "SSH_ASKPASS" to script, "SSH_ASKPASS_REQUIRE" to "force")
    }
}
