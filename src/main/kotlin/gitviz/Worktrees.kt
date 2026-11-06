package gitviz

import java.io.File

internal data class WorktreeInfo(val path: String, val head: String?, val branch: String?, val bare: Boolean,
                                 val locked: String?, val prunable: String?, val main: Boolean = false)

/** NUL records preserve whitespace, newlines and Git's unquoted paths. */
internal fun parseWorktrees(text: String): List<WorktreeInfo> {
    val records = mutableListOf<WorktreeInfo>()
    val fields = linkedMapOf<String, String>()
    fun finish() {
        fields["worktree"]?.let { records += WorktreeInfo(it, fields["HEAD"], fields["branch"], "bare" in fields,
            fields["locked"], fields["prunable"], records.isEmpty()) }
        fields.clear()
    }
    text.split('\u0000').forEach { field ->
        if (field.isEmpty()) finish() else fields[field.substringBefore(' ')] = field.substringAfter(' ', "")
    }
    finish()
    return records
}

internal fun GitCli.worktrees(): List<WorktreeInfo> = parseWorktrees(read("worktree", "list", "--porcelain", "-z"))

internal fun GitCli.addWorktree(path: String, branch: String, base: String = "HEAD", newBranch: Boolean = true): GitResult =
    coordinator.execute(OperationRequest(OperationKind.OTHER)) {
        check(currentOperation() == null) { t("Finish the current Git operation first", "请先完成当前 Git 操作") }
        val target = File(path)
        require(target.isAbsolute && path.isNotBlank() && '\u0000' !in path) { t("Choose an absolute directory path", "请选择绝对目录路径") }
        require(!target.exists()) { t("The destination must not exist yet", "目标目录必须尚不存在") }
        val canonical = target.canonicalFile.toPath()
        require(worktrees().none { val other = File(it.path).canonicalFile.toPath(); canonical.startsWith(other) || other.startsWith(canonical) }) {
            t("Worktrees must use separate directories", "工作树必须使用彼此独立的目录")
        }
        require(branch.isNotBlank() && !branch.startsWith('-') && raw(listOf("check-ref-format", "refs/heads/$branch")).exitCode == 0) {
            t("Enter a valid local branch name", "请输入有效的本地分支名")
        }
        val args = if (newBranch) {
            val oid = read("rev-parse", "--verify", "--end-of-options", "$base^{commit}").trim()
            listOf("worktree", "add", "-b", branch, "--", canonical.toString(), oid)
        } else {
            check(worktrees().none { it.branch == "refs/heads/$branch" }) { t("That branch is already checked out in a worktree", "此分支已在某个工作树中检出") }
            read("show-ref", "--verify", "refs/heads/$branch")
            listOf("worktree", "add", "--", canonical.toString(), branch)
        }
        raw(args, environment = mapOf("GIT_LFS_SKIP_SMUDGE" to "1"), teach = true).result()
    }

internal fun GitCli.removeWorktree(reviewed: WorktreeInfo): GitResult = coordinator.execute(OperationRequest(OperationKind.OTHER)) {
    val current = worktrees().singleOrNull { it.path == reviewed.path }
    check(current == reviewed) { t("Worktree changed; refresh and review again", "工作树已变化，请刷新后重新检查") }
    check(!current.main && !current.bare && File(current.path).canonicalFile != workTree.canonicalFile) { t("The main or current worktree cannot be removed", "不能移除主工作树或当前工作树") }
    check(current.locked == null && current.prunable == null && File(current.path).isDirectory) { t("Locked or missing worktrees cannot be removed here", "无法在此移除锁定或缺失的工作树") }
    val target = atPath(File(current.path))
    check(target.commonDirectory == commonDirectory) { "Worktree repository changed" }
    check(target.currentOperation() == null) { t("Finish the operation in that worktree first", "请先完成目标工作树中的操作") }
    // -v uses lowercase for assume-unchanged; S/s denotes skip-worktree.
    // Both Git status and native worktree remove can overlook changes hidden by these flags.
    check(target.read("ls-files", "-v", "-z").split('\u0000').none { it.isNotEmpty() && (it[0] in 'a'..'z' || it[0] == 'S') }) {
        t("Worktree has assume-unchanged or skip-worktree flags that can hide local changes. Review the files and resolve these flags before removing it.",
            "工作树含 assume-unchanged 或 skip-worktree 标志，可能隐藏本地改动。请先检查相关文件并处理这些标志，再尝试移除。")
    }
    check(target.treeStatus().isEmpty()) { t("Worktree contains changes or ignored files. Preserve them before removing it.", "工作树包含改动或被忽略的文件，请先妥善保留。") }
    // Native remove refuses nested submodules and rechecks cleanliness. No --force.
    raw(listOf("worktree", "remove", "--", current.path), teach = true).result()
}
