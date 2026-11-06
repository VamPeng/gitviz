package gitviz

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.ObjectWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

internal enum class PreviewKind { MERGE, REBASE, CHERRY_PICK, RESET }
internal data class OperationIntent(val kind: PreviewKind, val target: String, val mode: ResetMode = ResetMode.MIXED,
    val mainline: Int = 1, val switchBranch: String? = null, val ffOnly: Boolean = false, val expectedHead: String? = null) {
    fun command(parentCount: Int): List<String> = when (kind) {
        PreviewKind.MERGE -> listOf("merge", "--no-edit", "--no-overwrite-ignore", "--no-autostash") + (if (ffOnly) listOf("--ff-only") else emptyList()) + target
        PreviewKind.REBASE -> listOf("-c", "core.editor=true", "rebase") + rebaseFlags + target
        PreviewKind.CHERRY_PICK -> listOf("cherry-pick") + (if (parentCount > 1) listOf("-m", mainline.toString()) else emptyList()) + target
        PreviewKind.RESET -> listOf("reset", mode.flag, target)
    }
    fun request() = OperationRequest(OperationKind.valueOf(kind.name), (switchBranch?.let { listOf("switch", it, "then") }.orEmpty() + listOf(kind.name.lowercase(), target)), revisions = setOfNotNull(target, switchBranch), rebaseRange = if (kind == PreviewKind.REBASE) (switchBranch ?: "HEAD") to target else null)
}
internal data class PreviewFile(val path: String, val diff: String)
internal data class OperationPlan(val intent: OperationIntent, val fingerprint: String, val parentCount: Int,
    val before: List<CommitInfo>, val after: List<CommitInfo>, val beforeHead: String, val afterHead: String,
    val added: List<String>, val removed: List<String>, val files: List<PreviewFile>, val workingImpact: List<String>,
    val indexChanged: Boolean, val branch: String?, val outcome: GitResult, val warnings: List<String>, val paused: Boolean,
    val projected: List<String>, val repository: String, val expected: List<CommitInfo> = emptyList()) {
    val verified: Boolean get() = warnings.isEmpty() && outcome.ok && !paused
}

private val semanticConfig = setOf("core.autocrlf", "core.eol", "core.safecrlf", "core.filemode", "core.ignorecase", "core.symlinks",
    "merge.ff", "merge.renormalize", "merge.diffalgorithm", "merge.renames", "merge.renamelimit", "merge.directoryrenames", "merge.conflictstyle", "diff.renamelimit")

internal fun GitCli.previewFingerprint(intent: OperationIntent, state: RecoverySnapshot? = null): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fun add(bytes: ByteArray) { hash.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()); hash.update(bytes) }
    add(recovery.fingerprint(state ?: recovery.previewState(intent.request())).toByteArray())
    add(bytes("config", "--null", "--list"))
    add(bytes("worktree", "list", "--porcelain"))
    val extra = listOf(ownDirectory.resolve("info/attributes"), commonDirectory.resolve("info/attributes"), commonDirectory.resolve("info/exclude")) +
        listOf("core.attributesfile", "core.excludesfile").mapNotNull { key -> config(key)?.let { File(it.replaceFirst(Regex("^~/"), System.getProperty("user.home") + "/")).let { f -> if (f.isAbsolute) f else workTree.resolve(f.path) } } } +
        File(System.getenv("XDG_CONFIG_HOME") ?: System.getProperty("user.home") + "/.config", "git/attributes")
    extra.distinct().forEach { file -> add(file.absolutePath.toByteArray()); add(if (file.isFile) file.readBytes() else byteArrayOf()) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal fun GitCli.planOperation(requested: OperationIntent): OperationPlan {
    check(requested.expectedHead == null || requested.expectedHead == headIdentity()) { "Current branch changed after fetch; fetch or choose the integration target again" }
    check(currentOperation() == null) { t("Finish or abort the current operation first", "请先完成或中止当前操作") }
    val target = read("rev-parse", "--verify", "--end-of-options", "${requested.target}^{commit}").trim()
    val intent = requested.copy(target = target)
    val parentCount = read("rev-list", "--parents", "-n", "1", target).trim().split(' ').size - 1
    require(parentCount <= 1 || intent.mainline in 1..parentCount) { "Choose a valid mainline parent" }
    if (intent.kind == PreviewKind.REBASE || intent.switchBranch != null) check(status().isEmpty()) { t("Commit or stash local changes first", "请先提交或储藏本地改动") }
    check(intent.kind != PreviewKind.REBASE || intent.switchBranch != null || raw(listOf("symbolic-ref", "-q", "HEAD")).exitCode == 0) { "Rebase requires a local branch" }
    intent.switchBranch?.let { branch ->
        read("show-ref", "--verify", "refs/heads/$branch")
        val occupied = read("worktree", "list", "--porcelain").split("\n\n").filter { !it.startsWith("worktree ${workTree.canonicalPath}\n") }
        check(occupied.none { "\nbranch refs/heads/$branch\n" in "$it\n" }) { "Branch is checked out in another worktree" }
    }
    val snapshot = recovery.previewState(intent.request())
    val fingerprint = previewFingerprint(intent, snapshot)
    val beforeHead = if (snapshot.head.startsWith("ref: ")) checkNotNull(snapshot.refs[snapshot.head.removePrefix("ref: ")]) else snapshot.head
    check(intent.expectedHead == null || intent.expectedHead == "${snapshot.head}|$beforeHead") { "Current branch changed after fetch" }
    require(beforeHead.matches(Regex("[0-9a-f]{40}"))) { "Preview requires an existing commit" }
    val root = Files.createTempDirectory("gitviz-preview-").toFile()
    try {
        fun git(vararg args: String): GitExecution = GitProcess.execute(root, listOf("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "protocol.allow=never", "-c", "core.fsmonitor=false") + args,
            environment = mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_NO_LAZY_FETCH" to "1", "GIT_ATTR_NOSYSTEM" to "1"), isolated = true)
        git("init", "-q", "--template=", "--initial-branch=preview").requireSuccess()
        git("config", "user.name", "GitViz Preview").requireSuccess(); git("config", "user.email", "preview@gitviz.invalid").requireSuccess()
        git("config", "core.attributesFile", "/dev/null").requireSuccess()
        git("config", "gc.auto", "0").requireSuccess()
        semanticConfig.forEach { key -> config(key)?.let { git("config", key, it).requireSuccess() } }
        val configText = read("config", "--null", "--list")
        val warnings = mutableListOf<String>()
        if (configText.split('\u0000').any { it.startsWith("filter.") || it.substringBefore('\n').matches(Regex("merge\\..+\\.driver")) || it.startsWith("extensions.partialclone" ) })
            warnings += t("Custom filters, merge drivers or partial-clone configuration: rehearsal cannot fully verify the result.", "存在自定义过滤器、合并驱动或部分克隆配置：演练无法完整验证结果。")
        if (config("core.attributesfile")?.let { it != "/dev/null" } == true || commonDirectory.resolve("info/attributes").isFile || File(System.getenv("XDG_CONFIG_HOME") ?: System.getProperty("user.home") + "/.config", "git/attributes").isFile)
            warnings += t("External attributes are excluded from rehearsal; the result is not fully verified.", "演练未采用外部 attributes，结果未完整验证。")
        if (configText.split('\u0000').any { it.startsWith("branch.") && ".mergeoptions\n" in it }) warnings += t("Branch merge options are excluded from rehearsal.", "演练未采用分支自定义合并参数。")
        val coveredRebase = setOf("rebase.backend", "rebase.rebasemerges", "rebase.updaterefs", "rebase.autostash", "rebase.autosquash")
        val omitted = configText.split('\u0000').map { it.substringBefore('\n') }.filter { key ->
            (key.startsWith("merge.") || key.startsWith("rebase.") || key.startsWith("rerere.")) && key !in semanticConfig && key !in coveredRebase
        }.distinct()
        if (omitted.isNotEmpty()) warnings += t("Settings not rehearsed: ", "演练未采用的设置：") + omitted.joinToString()
        if (listOf("pre-merge-commit", "pre-commit", "commit-msg", "post-commit", "pre-rebase", "post-rewrite", "post-checkout").any { commonDirectory.resolve("hooks/$it").canExecute() } || config("core.hookspath")?.let { it != "/dev/null" } == true)
            warnings += t("Repository hooks are not run in rehearsal.", "演练不会执行仓库 hooks。")
        GitRepo.open(workTree).use { source -> GitRepo.open(root).use { destination ->
            ObjectWalk(source.repository).use { walk -> destination.repository.newObjectInserter().use { insert ->
                (snapshot.roots + target).forEach { walk.markStart(walk.parseAny(ObjectId.fromString(it))) }
                fun copy(id: ObjectId) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val obj = source.repository.open(id)
                    obj.openStream().use { insert.insert(obj.type, obj.size, it) }
                }
                while (true) copy(walk.next() ?: break)
                while (true) copy(walk.nextObject() ?: break)
                insert.flush()
            } }
        } }
        snapshot.refs.filterValues { !it.startsWith("ref: ") }.forEach { (ref, oid) -> git("update-ref", ref, oid).requireSuccess() }
        if (snapshot.head.startsWith("ref: ")) git("symbolic-ref", "HEAD", snapshot.head.removePrefix("ref: ")).requireSuccess()
        else git("update-ref", "--no-deref", "HEAD", snapshot.head).requireSuccess()
        snapshot.index.keys.forEach { ownDirectory.resolve(it).copyTo(root.resolve(".git/$it"), overwrite = true) }
        snapshot.files.entries.sortedBy { it.key.count { c -> c == '/' } }.forEach { (name, saved) ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val path = root.toPath().resolve(name)
            when (saved.kind) {
                "directory" -> Files.createDirectories(path)
                "link" -> { Files.createDirectories(path.parent); Files.createSymbolicLink(path, java.nio.file.Path.of(saved.content)) }
                "file" -> { Files.createDirectories(path.parent); Files.newByteChannel(safeWorkingPath(name), setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel -> java.nio.channels.Channels.newInputStream(channel).use { input -> Files.newOutputStream(path).use { input.copyTo(it) } } } }
            }
            if (saved.permissions.isNotEmpty() && saved.kind != "missing") Files.setPosixFilePermissions(path, saved.permissions.split(',').map { PosixFilePermission.valueOf(it) }.toSet())
        }
        val sandbox = GitCli(root, isolated = true)
        val copied = sandbox.recovery.previewState(intent.request())
        check(snapshot.files == copied.files && snapshot.index == copied.index && snapshot.head == copied.head) { "Source changed while copying rehearsal inputs" }
        val before = GitRepo.open(root).use { it.loadCommits(200).commits }
        val beforeIndex = git("write-tree").requireSuccess().decodeToString().trim()
        val beforeState = sandbox.recovery.previewState(intent.request())
        val contents = root.resolve(".git/gitviz-before").also { it.mkdir() }
        beforeState.files.forEach { (path, saved) ->
            if (saved.kind == "file" && root.resolve(path).length() <= 1024 * 1024) root.resolve(path).copyTo(contents.resolve(saved.content), overwrite = true)
        }
        val switched = intent.switchBranch?.let { git("switch", it) }
        val result = if (switched != null && switched.exitCode != 0) switched else git(*intent.command(parentCount).toTypedArray())
        val paused = sandbox.currentOperation() != null
        val afterHead = git("rev-parse", "HEAD").requireSuccess().decodeToString().trim()
        val afterIndex = git("write-tree").let { if (it.exitCode == 0) it.text.trim() else null }
        val afterState = sandbox.recovery.previewState(intent.request())
        val after = GitRepo.open(root).use { it.loadCommits(200).commits }
        fun ids(vararg args: String) = git(*args).requireSuccess().decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
        val added = ids("rev-list", afterHead, "^$beforeHead")
        val removed = ids("rev-list", beforeHead, "^$afterHead")
        val workingImpact = (beforeState.files.keys + afterState.files.keys).filter { beforeState.files[it] != afterState.files[it] }
        val files = GitRepo.open(root).use { repo -> repo.changesBetween(beforeHead, afterHead).map { PreviewFile("HEAD · ${it.displayPath}", repo.diffText(it)) } }.toMutableList()
        if (afterIndex != null && beforeIndex != afterIndex) GitRepo.open(root).use { repo ->
            files += repo.changesBetween(beforeIndex, afterIndex).map { PreviewFile(t("Index · ", "暂存区 · ") + it.displayPath, repo.diffText(it)) }
        }
        workingImpact.forEach { path ->
            val old = beforeState.files[path]; val new = afterState.files[path]
            fun content(saved: SavedPath?, before: Boolean): ByteArray? = when (saved?.kind) {
                null, "missing" -> byteArrayOf()
                "link" -> saved.content.toByteArray()
                "file" -> (if (before) contents.resolve(saved.content) else root.resolve(path)).takeIf { it.isFile && it.length() <= 1024 * 1024 }?.readBytes()
                else -> null
            }
            val detail = previewContentDiff(path, content(old, true), content(new, false)) + "\n${old?.kind ?: "missing"} → ${new?.kind ?: "missing"}"
            files += PreviewFile(t("Working tree · ", "工作区 · ") + path, detail)
        }
        if (paused) sandbox.status().forEach { f -> files += PreviewFile(f.path, sandbox.diff(f, f.hasStaged && !f.hasUnstaged)) }
        val replayDirectory = root.resolve(".git/rebase-merge")
        val remaining = if (paused && intent.kind == PreviewKind.REBASE) replayDirectory.resolve("git-rebase-todo").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() && !it.startsWith('#') }.orEmpty() else emptyList()
        val stopped = if (paused && intent.kind == PreviewKind.REBASE) replayDirectory.resolve("done").takeIf { it.isFile }?.readLines()?.lastOrNull { it.startsWith("pick ") || it.startsWith("merge ") } else null
        val projected = listOfNotNull(stopped) + remaining
        check(fingerprint == previewFingerprint(intent)) { t("Repository changed during preview. Preview again.", "演练期间仓库发生变化，请重新预览。") }
        val addedSet = GitRepo.open(workTree).use { source -> added.filterNot { source.repository.objectDatabase.has(ObjectId.fromString(it)) }.toSet() }
        fun virtual(id: String) = if (id in addedSet) "preview:$id" else id
        val virtualAfter = after.map { c -> c.copy(id = virtual(c.id), parents = c.parents.map(::virtual), graphParents = c.graphParents.map(::virtual), shortMessage = (if (c.id in addedSet) t("[simulated] ", "[模拟] ") else "") + c.shortMessage) }
        val expected = if (projected.isEmpty()) emptyList() else runCatching {
            val labels = git("for-each-ref", "--format=%(refname) %(objectname)", "refs/rewritten/").requireSuccess().decodeToString().lineSequence()
                .filter { it.isNotBlank() }.associate { it.substringBefore(' ').removePrefix("refs/rewritten/") to virtual(it.substringAfter(' ')) }
            GitRepo.open(root).use { repo -> expectedTopology(projected, virtual(afterHead), labels + ("onto" to intent.target), repo::commit) }
        }.getOrElse { warnings += t("Remaining topology could not be projected: ", "无法推导剩余拓扑：") + it.message; emptyList() }
        return OperationPlan(intent, fingerprint, parentCount, before, virtualAfter, beforeHead, virtual(afterHead), added, removed, files,
            workingImpact, beforeState.index != afterState.index, afterState.head.removePrefix("ref: refs/heads/").takeIf { afterState.head.startsWith("ref: refs/heads/") },
            result.result(), warnings, paused, projected, workTree.canonicalPath, expected)
    } finally {
        // GitProcess waits for its children before returning, including interruption. Never delete beneath a live child.
        if (!GitProcess.hasUnfinishedProcess()) deleteTemporaryTree(root.toPath())
    }
}

internal fun GitCli.executePlan(plan: OperationPlan): GitResult = coordinator.execute(OperationRequest(OperationKind.OTHER)) {
    check(plan.repository == workTree.canonicalPath && plan.fingerprint == previewFingerprint(plan.intent)) { t("Preview expired. Preview the operation again.", "预览已过期，请重新预览操作。") }
    coordinator.execute(plan.intent.request()) {
        check(plan.fingerprint == previewFingerprint(plan.intent)) { "Preview expired while saving recovery point" }
        val switched = plan.intent.switchBranch?.let { raw(listOf("switch", it), teach = true).result() }
        if (switched != null && !switched.ok) switched else {
            val result = raw(plan.intent.command(plan.parentCount), teach = true)
            if (plan.intent.kind == PreviewKind.REBASE) rebaseResult(result) else result.result()
        }
    }
}

private fun previewContentDiff(path: String, before: ByteArray?, after: ByteArray?): String {
    if (before == null || after == null || strictText(before) == null || strictText(after) == null) return t("Binary, directory or large content changed: $path", "二进制、目录或大文件内容改变：$path")
    val old = org.eclipse.jgit.diff.RawText(before)
    val new = org.eclipse.jgit.diff.RawText(after)
    val edits = org.eclipse.jgit.diff.HistogramDiff().diff(org.eclipse.jgit.diff.RawTextComparator.DEFAULT, old, new)
    val output = java.io.ByteArrayOutputStream()
    org.eclipse.jgit.diff.DiffFormatter(output).use { it.format(edits, old, new) }
    return ("--- $path\n+++ $path\n" + output.toString(Charsets.UTF_8)).take(GitRepo.MAX_DIFF_CHARS)
}
