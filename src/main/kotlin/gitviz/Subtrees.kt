package gitviz

import java.nio.file.Files

internal data class SubtreeRequest(val prefix: String, val repository: String, val ref: String, val add: Boolean,
                                   val squash: Boolean, val expectedHead: String)
internal fun GitCli.subtreeAvailable(): Boolean = raw(listOf("subtree", "-h")).let { !it.uncertain && "git subtree" in it.output && "--prefix" in it.output }

internal fun GitCli.applySubtree(request: SubtreeRequest): GitResult = coordinator.execute(OperationRequest(OperationKind.OTHER)) {
    check(subtreeAvailable()) { t("The installed Git does not include git-subtree", "已安装的 Git 不包含 git-subtree") }
    check(currentOperation() == null && status().isEmpty()) { t("Commit or stash changes and finish the current operation first", "请先提交或储藏改动并完成当前操作") }
    check(request.expectedHead == headIdentity() && request.expectedHead.startsWith("ref: refs/heads/")) { t("Current branch changed; review again", "当前分支已变化，请重新检查") }
    require(request.prefix.isNotBlank() && !request.prefix.startsWith('-') && request.prefix.split('/').none { it.isEmpty() || it == "." }) { "Enter a normal relative subdirectory" }
    val directory = safeWorkingPath(request.prefix)
    check(!Files.isSymbolicLink(directory)) { "Subtree prefix is a symbolic link" }
    if (request.add) check(!Files.exists(directory)) { t("The subtree directory already exists", "子树目录已经存在") }
    else check(Files.isDirectory(directory) && read("cat-file", "-t", "HEAD:${request.prefix}").trim() == "tree") { t("Choose an existing tracked directory", "请选择已有的已跟踪目录") }
    require(request.repository.isNotBlank() && !request.repository.startsWith('-') && request.repository.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Enter a repository URL or path" }
    require(request.ref.isNotBlank() && !request.ref.startsWith('-') && raw(listOf("check-ref-format", "--allow-onelevel", request.ref)).exitCode == 0) { t("Enter one branch, tag or commit ID; refspecs are not accepted", "请输入单个分支、标签或提交 ID，不支持 refspec") }
    // Capture before fetching; imported gitlinks are rejected before changing files or HEAD.
    coordinator.execute(OperationRequest(OperationKind.MERGE, listOf("subtree", if (request.add) "add" else "pull", "--prefix", request.prefix), network = true, paths = setOf(request.prefix))) {
        val fetch = raw(listOf("fetch", "--no-tags", "--no-recurse-submodules", "--refmap=", "--", request.repository, request.ref), environment = Askpass.environment(), timeout = 600, teach = true).result()
        if (!fetch.ok) return@execute fetch
        check(headIdentity() == request.expectedHead && status().isEmpty()) { t("Fetch finished, but local state changed; integration was not started", "获取已完成，但本地状态发生变化，未开始集成") }
        val oid = read("rev-parse", "--verify", "FETCH_HEAD^{commit}").trim()
        check(read("ls-tree", "-r", "-z", oid).split('\u0000').none { it.startsWith("160000 ") }) { t("The imported tree contains submodules; full subtree recovery cannot preserve them", "导入树含子模块，完整子树恢复无法保留其状态") }
        raw(listOf("-c", "core.editor=true", "-c", "merge.autoStash=false", "-c", "submodule.recurse=false", "subtree", if (request.add) "add" else "merge", "--prefix=${request.prefix}") +
            (if (request.squash) listOf("--squash") else emptyList()) + oid,
            environment = mapOf("GIT_LFS_SKIP_SMUDGE" to "1"), teach = true).result()
    }
}
