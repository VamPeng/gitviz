package gitviz

import java.nio.file.Files

internal enum class RebaseAction { PICK, REWORD, SQUASH, FIXUP, DROP }
internal data class RebaseEdit(val id: String, val action: RebaseAction = RebaseAction.PICK, val message: String = "")
internal data class InteractiveRebasePlan(val base: String, val head: String, val commits: List<CommitInfo>, val edits: List<RebaseEdit>) {
    fun validate() {
        require(commits.isNotEmpty() && commits.size <= 500) { t("Choose between 1 and 500 commits", "请选择 1 至 500 个提交") }
        require(edits.size == commits.size && edits.map { it.id }.toSet() == commits.map { it.id }.toSet()) { "Every original commit must appear exactly once" }
        require(commits.all { it.id.matches(Regex("[0-9a-f]{40}")) && it.parents.size <= 1 }) { "Only linear commit ranges are supported" }
        var preceding = false
        edits.forEach { edit ->
            if (edit.action in setOf(RebaseAction.SQUASH, RebaseAction.FIXUP)) require(preceding) { t("Squash and fixup need a preceding kept commit", "合并和修补之前必须有保留的提交") }
            if (edit.action == RebaseAction.REWORD) require(edit.message.isNotBlank() && '\u0000' !in edit.message) { t("Enter a nonempty commit message without NUL characters", "请输入不含 NUL 字符的非空提交说明") }
            if (edit.action != RebaseAction.DROP) preceding = true
        }
    }

    /** Only fixed commands and octal-encoded message bytes enter the native todo. No user-authored shell. */
    fun todo(): String {
        validate()
        val byId = commits.associateBy { it.id }
        var message = ""
        val text = buildString {
            edits.forEach { edit ->
                val original = byId.getValue(edit.id)
                when (edit.action) {
                    RebaseAction.DROP -> append("drop ${edit.id}\n")
                    RebaseAction.PICK, RebaseAction.REWORD -> {
                        append("pick ${edit.id}\n")
                        message = if (edit.action == RebaseAction.REWORD) edit.message.trimEnd() else original.fullMessage.trimEnd()
                        if (edit.action == RebaseAction.REWORD) append(messageCommand(message))
                    }
                    RebaseAction.FIXUP -> append("fixup ${edit.id}\n")
                    RebaseAction.SQUASH -> {
                        append("fixup ${edit.id}\n")
                        message += "\n\n" + original.fullMessage.trimEnd()
                        append(messageCommand(message))
                    }
                }
            }
        }
        require(text.toByteArray().size <= 2 * 1024 * 1024) { t("Edited plan is too large. Shorten messages or select fewer commits.", "编辑计划过大，请缩短说明或减少提交数量。") }
        return text
    }

    private fun messageCommand(message: String): String {
        require('\u0000' !in message) { "Commit message contains a NUL character" }
        val bytes = (message + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024) { t("Combined commit message exceeds 16 KiB", "合并后的提交说明超过 16 KiB") }
        val octal = bytes.joinToString("") { "\\0" + (it.toInt() and 255).toString(8).padStart(3, '0') }
        return "exec printf '%b' '$octal' | git -c core.editor=true commit --amend --allow-empty --cleanup=verbatim -F -\n"
    }
}

internal fun GitCli.interactiveRange(baseInput: String): InteractiveRebasePlan {
    check(currentOperation() == null) { t("Finish the current operation first", "请先完成当前操作") }
    check(status().isEmpty()) { t("Commit or stash local changes first", "请先提交或储藏本地改动") }
    val head = headIdentity()
    check(head.startsWith("ref: refs/heads/")) { t("Choose a local branch first", "请先切换到本地分支") }
    val base = read("rev-parse", "--verify", "--end-of-options", "$baseInput^{commit}").trim()
    val commits = GitRepo.open(workTree).use { repo ->
        val result = mutableListOf<CommitInfo>()
        var id = head.substringAfter('|')
        while (id != base) {
            check(result.size < 500) { t("Select a base within 500 commits", "请选择 500 个提交以内的基准") }
            val commit = repo.commit(id)
            check(commit.parents.size == 1) { t("This range crosses a merge or the root. Choose a later base; ordinary rebase remains available.", "这个区间跨越合并或根提交，请选择更近的基准；仍可使用普通变基。") }
            result += commit; id = commit.parents.single()
        }
        result.asReversed().toList()
    }
    check(head == headIdentity()) { "Branch changed while loading the edit range" }
    return InteractiveRebasePlan(base, head, commits, commits.map { RebaseEdit(it.id, message = it.fullMessage.trimEnd()) }).also { it.validate() }
}

internal val interactiveRebaseFlags = listOf("--interactive", "--merge", "--no-rebase-merges", "--force-rebase", "--reapply-cherry-picks",
    "--no-fork-point", "--no-update-refs", "--no-autostash", "--no-autosquash", "--keep-empty", "--empty=stop", "--reschedule-failed-exec")

internal fun GitCli.checkInteractivePlan(plan: InteractiveRebasePlan) {
    plan.todo()
    val current = interactiveRange(plan.base)
    check(plan.head == current.head && plan.commits.map { it.id } == current.commits.map { it.id }) { t("Edit plan expired. Reload the range.", "编辑计划已过期，请重新加载区间。") }
}

internal fun GitCli.runInteractiveCommands(plan: InteractiveRebasePlan, teach: Boolean = false): GitExecution {
    val todo = plan.todo()
    val temporary = Files.createTempDirectory("gitviz-sequence-")
    try {
        val todoFile = temporary.resolve("todo")
        val editor = temporary.resolve("editor.sh")
        fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
        Files.writeString(todoFile, todo)
        Files.writeString(editor, "#!/bin/sh\nset -eu\ncat " + quote(todoFile.toString()) + " > \"\$1\"\n")
        if (teach) Teaching.note("GitViz supplies the reviewed todo; message edits are encoded data in fixed amend steps. Native Git resumes this plan after conflicts or restart.", "GitViz 提供已预览的编辑列表；说明文字编码为固定修改步骤的数据。冲突或重启后由原生 Git 继续这个计划。")
        val result = raw(listOf("-c", "core.editor=true", "rebase") + interactiveRebaseFlags + plan.base,
            environment = mapOf("GIT_SEQUENCE_EDITOR" to ("sh " + quote(editor.toString()))), teach = teach)
        return readableRebaseExecution(result)
    } finally { if (!GitProcess.hasUnfinishedProcess()) deleteTemporaryTree(temporary) }
}

internal fun readableRebaseExecution(result: GitExecution): GitExecution {
    val command = Regex(Regex.escape("printf '%b' '") + "(?:\\\\0[0-7]{3})+" + Regex.escape("' | git -c core.editor=true commit --amend --allow-empty --cleanup=verbatim -F -"))
    fun readable(bytes: ByteArray) = command.replace(bytes.decodeToString(), t("[GitViz: apply reviewed commit message]", "[GitViz：应用已预览的提交说明]")).toByteArray()
    return result.copy(stdout = readable(result.stdout), stderr = readable(result.stderr))
}
