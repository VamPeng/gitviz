package gitviz

import java.io.File
import java.util.concurrent.TimeUnit

enum class GitFailure { REJECTED, AUTH, CONFLICT, OTHER }

/** 一次网络操作的结果。failure 为 null 表示成功，output 是 git 的原始输出。 */
data class GitResult(val failure: GitFailure?, val output: String) {
    val ok: Boolean get() = failure == null
}

/** 从 git 的输出判断失败原因，界面据此决定显示什么、提供什么操作。 */
internal fun classifyFailure(output: String): GitFailure = when {
    "CONFLICT" in output || "Automatic merge failed" in output -> GitFailure.CONFLICT
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
        val reader = process.inputStream.bufferedReader()
        val output = StringBuilder()
        val pump = Thread { reader.forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return 1 to "git 运行超过 $TIMEOUT_SECONDS 秒，已停止"
        }
        pump.join()
        return process.exitValue() to output.toString()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
    }
}
