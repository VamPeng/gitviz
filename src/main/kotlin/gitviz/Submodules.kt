package gitviz

import java.nio.file.Files
import java.util.UUID

internal data class SubmoduleInfo(val name: String, val path: String, val url: String, val target: String?,
                                  val head: String?, val dirty: Boolean, val problem: String? = null) {
    val ready: Boolean get() = problem == null && target != null && !dirty
}

internal fun GitCli.submodules(): List<SubmoduleInfo> {
    val modules = safeWorkingPath(".gitmodules")
    if (!Files.exists(modules)) return emptyList()
    check(!Files.isSymbolicLink(modules) && Files.size(modules) <= 1_048_576) { "Invalid .gitmodules file" }
    val config = raw(listOf("config", "--no-includes", "--null", "--file", modules.toString(), "--get-regexp", "^submodule\\..*\\.(path|url)$"))
    check(config.exitCode in 0..1 && !config.uncertain) { config.output }
    val values = config.text.split('\u0000').filter { '\n' in it }.associate { it.substringBefore('\n') to it.substringAfter('\n') }
    val links = read("ls-files", "--stage", "-z").split('\u0000').filter { it.startsWith("160000 ") }.groupBy { it.substringAfter('\t') }
    return values.filterKeys { it.endsWith(".path") }.map { (key, path) ->
        val name = key.removePrefix("submodule.").removeSuffix(".path")
        val url = config("submodule.$name.url") ?: values["submodule.$name.url"].orEmpty()
        val entries = links[path].orEmpty().map { it.substringBefore('\t').split(' ') }
        val target = entries.singleOrNull()?.takeIf { it[2] == "0" }?.get(1)
        try {
            val directory = safeWorkingPath(path)
            check(!Files.isSymbolicLink(directory)) { "Submodule directory is a symbolic link" }
            val initialized = Files.exists(directory.resolve(".git"))
            val child = atPath(directory.toFile())
            val head = if (initialized) {
                check(child.read("rev-parse", "--show-toplevel").trim() == directory.toFile().canonicalPath) { "Submodule points to another worktree" }
                check(child.commonDirectory != commonDirectory) { "Submodule points to the parent repository" }
                child.read("rev-parse", "--verify", "HEAD").trim()
            } else null
            val dirty = if (initialized) child.status().isNotEmpty() || child.currentOperation() != null else
                Files.exists(directory) && (directory.toFile().list()?.isNotEmpty() != false)
            SubmoduleInfo(name, path, url, target, head, dirty,
                if (target == null) t("Missing or conflicted gitlink in index", "暂存区中缺少 gitlink 或存在冲突") else null)
        } catch (e: Exception) { SubmoduleInfo(name, path, url, target, null, false, e.message) }
    }
}

internal fun GitCli.updateSubmodule(reviewed: SubmoduleInfo): GitResult = coordinator.execute(OperationRequest(OperationKind.OTHER)) {
    check(currentOperation() == null) { t("Finish the current Git operation first", "请先完成当前 Git 操作") }
    val item = submodules().singleOrNull { it.path == reviewed.path }
    check(item == reviewed && item.ready) { t("Submodule changed or is not safe to update. Refresh first.", "子模块已变化或无法安全更新，请先刷新。") }
    val directory = safeWorkingPath(item.path).toFile()
    val child = atPath(directory)
    fun update(): GitResult {
        // No force, recursive, remote, merge, rebase or configured custom update command.
        var saved: String? = null
        if (item.head != null) {
            check(child.treeStatus().isEmpty()) { t("Preserve modified, untracked and ignored files in the submodule first", "请先保留子模块中的改动、未跟踪及被忽略文件") }
            saved = "refs/gitviz/submodule-before/${UUID.randomUUID()}"
            child.raw(listOf("update-ref", saved, item.head, "0".repeat(item.head.length))).requireSuccess()
        }
        val result = raw(listOf("-c", "submodule.recurse=false", "submodule", "update", "--init", "--checkout", "--", item.path),
            environment = Askpass.environment() + ("GIT_LFS_SKIP_SMUDGE" to "1"), timeout = 600, teach = true).result()
        val note = saved?.let { t("Previous submodule HEAD retained at $it", "子模块原 HEAD 已保留在 $it") }.orEmpty()
        return result.copy(output = listOf(result.output, note).filter { it.isNotBlank() }.joinToString("\n"))
    }
    if (item.head == null) update() else child.coordinator.execute(OperationRequest(OperationKind.OTHER)) { update() }
}
