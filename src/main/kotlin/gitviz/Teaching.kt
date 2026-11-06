package gitviz

/** Display-only lesson. A null command explains an operation that has no single Git equivalent. */
data class GitLesson(val command: String?, val explanation: String)

internal object Teaching {
    private val current = ThreadLocal<MutableList<GitLesson>?>()

    /** All Git writes are synchronous within their IO task. Never keep a recorder on a pooled thread. */
    fun capture(enabled: Boolean, action: () -> GitResult): GitResult {
        if (!enabled || current.get() != null) return attemptGitOperation(action)
        val lessons = mutableListOf<GitLesson>()
        current.set(lessons)
        return try { attemptGitOperation(action).copy(lessons = lessons.toList()) }
        finally { current.remove() }
    }

    fun note(en: String, zh: String) {
        current.get()?.let { if (it.size < 128) it += GitLesson(null, t(en, zh)) }
    }

    fun command(args: List<String>, input: Boolean = false, temporaryIndex: Boolean = false) {
        val lessons = current.get() ?: return
        if (lessons.size >= 128) return
        var offset = 0
        while (args.getOrNull(offset) == "-c") offset += 2
        val explanation = when (args.getOrNull(offset)) {
            "add" -> t("Stage the selected paths.", "暂存所选路径。")
            "restore" -> if ("--staged" in args) t("Restore index entries from HEAD.", "用 HEAD 恢复暂存区条目。") else t("Restore working files from the index.", "从暂存区恢复工作区文件。")
            "commit" -> t("Create or amend a commit from the index.", "从暂存区创建或修改提交。")
            "switch", "checkout" -> t("Switch the branch, commit or selected file version.", "切换分支、提交或所选文件版本。")
            "branch" -> t("Create, rename or delete a branch reference.", "创建、重命名或删除分支引用。")
            "tag" -> t("Create a tag at the selected commit.", "在所选提交上创建标签。")
            "merge" -> t("Integrate another history, or abort the active merge.", "集成另一段历史，或中止当前合并。")
            "rebase" -> t("Replay commits, or continue, skip or abort the active rebase.", "重放提交，或继续、跳过、中止当前变基。")
            "cherry-pick" -> t("Apply the selected commit, or manage the active cherry-pick.", "应用所选提交，或处理进行中的 Cherry-pick。")
            "revert" -> t("Create a commit reversing a change, or manage the active revert.", "创建反向改动提交，或处理进行中的回滚。")
            "reset" -> t("Move HEAD; the selected mode controls index and working files.", "移动 HEAD；所选模式决定暂存区和工作区的变化。")
            "stash" -> t("Save or manage stashed changes.", "保存或管理储藏改动。")
            "fetch" -> t("Download objects and update the requested remote-tracking references.", "下载对象并更新所选远程跟踪引用。")
            "push" -> t("Send objects and update remote references.", "发送对象并更新远程引用。")
            "clone" -> t("Copy a repository into a new directory.", "将仓库克隆到新目录。")
            "apply" -> t("Apply the selected patch to the index.", "将选中的补丁应用到暂存区。")
            "rm" -> t("Remove the selected paths from the index, and working tree unless --cached.", "移除所选路径；--cached 仅移出暂存区，否则也移除工作区文件。")
            "clean" -> t("Remove the selected untracked paths.", "移除所选未跟踪路径。")
            "config" -> t("Save the explicitly selected repository setting.", "保存明确选择的仓库设置。")
            else -> t("Git command requested by this operation.", "本次操作请求执行的 Git 命令。")
        }
        val context = listOfNotNull(
            if (temporaryIndex) t("Runs against a temporary index; GitViz validates and atomically installs it.", "作用于临时暂存区；GitViz 校验后原子安装。") else null,
            if (input) t("The selected patch is supplied on stdin; copying this line alone does not supply the patch.", "选中补丁通过标准输入传入；仅复制此行不包含补丁。") else null,
        ).joinToString(" ")
        lessons += GitLesson(formatGitCommand(args), listOf(explanation, context).filter { it.isNotEmpty() }.joinToString(" "))
    }
}

internal fun formatGitCommand(args: List<String>): String {
    fun redact(value: String): String = value
        .replace(Regex("(?i)(https?://)[^/\\s@]+@"), "$1<redacted>@")
        .replace(Regex("(?i)(https?://[^\\s?#]+)[?#][^\\s]*"), "$1?<redacted>")
        .let { if (Regex("(?i)(extraheader|authorization|password|token|secret)=").containsMatchIn(it)) it.substringBefore('=') + "=<redacted>" else it }
    fun quote(value: String): String = if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "@%_+=:,./-" }) value
        else "'" + value.replace("'", "'\"'\"'") + "'"
    return (listOf("git") + args.map(::redact)).joinToString(" ", transform = ::quote)
}
