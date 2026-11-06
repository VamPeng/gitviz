package gitviz

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal enum class RecordState { PREPARED, RUNNING, CONFLICTED, COMPLETED, RESTORING, NEEDS_RECOVERY, UNDONE, CHILD }
internal data class SavedPath(val kind: String, val content: String, val permissions: String)
internal class RecoveryRequiredException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private inline fun recoveryCheck(value: Boolean, message: () -> String) {
    if (!value) throw RecoveryRequiredException(message())
}

internal data class RecoverySnapshot(
    val head: String, val refs: Map<String, String>, val config: Map<String, List<String>>,
    val files: Map<String, SavedPath>, val index: Map<String, String>, val stash: List<Pair<String, String>>,
    val roots: Set<String>, val operation: String?,
)
internal data class OperationRecord(val id: String, val time: Long, val worktree: String, val label: String,
    val state: RecordState, val before: RecoverySnapshot, val after: RecoverySnapshot? = null, val detail: String = "", val requiresReview: Boolean = false)

/** All recovery data is private: original repository refs and user stashes are never used as backups. */
internal class RecoveryStore(private val cli: GitCli) {
    val directory: File get() = cli.commonDirectory.resolve("gitviz-recovery")
    private val recordsDir get() = directory.resolve("records")
    private val rawDir get() = directory.resolve("content")
    private val objectDir get() = directory.resolve("objects.git")
    private val identity get() = cli.ownDirectory.canonicalPath
    private val noFollow = LinkOption.NOFOLLOW_LINKS
    private var pendingRoots = emptySet<String>()

    var retentionDays: Int
        get() = options().getProperty("days", "30").toInt().coerceAtLeast(1)
        set(value) { setOption("days", value.coerceAtLeast(1).toString()) }
    var quotaBytes: Long
        get() = options().getProperty("bytes", (2L * 1024 * 1024 * 1024).toString()).toLong().coerceAtLeast(1024 * 1024)
        set(value) { setOption("bytes", value.coerceAtLeast(1024 * 1024).toString()) }
    private fun options() = java.util.Properties().apply { directory.resolve("settings").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
    private fun setOption(key: String, value: String) { initialize(); val p = options(); p.setProperty(key, value); atomic(directory.resolve("settings")) { p.store(it, null) } }
    fun size(): Long = if (!directory.exists()) 0 else directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun initialize() {
        recordsDir.mkdirs(); rawDir.mkdirs()
        if (!objectDir.isDirectory) FileRepositoryBuilder().setGitDir(objectDir).setBare().build().use { it.create(true) }
    }
    fun records(allWorktrees: Boolean = false): List<OperationRecord> = recordsDir.listFiles().orEmpty()
        .filter { it.name.endsWith(".record") }.map { readRecord(it) }
        .filter { allWorktrees || it.worktree == identity }.sortedByDescending { it.time }
    fun latest(): OperationRecord? = records().firstOrNull { it.state == RecordState.COMPLETED && it.after != it.before && it.before.operation == null }
    fun pending(): OperationRecord? = records(allWorktrees = true).firstOrNull { it.state in setOf(RecordState.PREPARED, RecordState.RUNNING, RecordState.RESTORING, RecordState.NEEDS_RECOVERY) }

    fun begin(request: OperationRequest): OperationRecord {
        initialize()
        pending()?.let { throw RecoveryRequiredException(t("An interrupted operation needs inspection in Recovery: ${it.worktree}", "有未完成的操作，请先到该工作区的恢复记录中核对：${it.worktree}")) }
        cleanup()
        val before = capture(targetPaths(request), store = true)
        val record = OperationRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), identity,
            request.arguments.joinToString(" ").ifBlank { request.kind.name }, RecordState.PREPARED, before, requiresReview = request.network)
        pendingRoots = before.roots
        try {
            copyObjects(before.roots)
            save(record)
            requireCapacity()
            check(capture(before.files.keys, store = false) == before) { t("Repository changed while saving the recovery point. Retry.", "保存恢复点期间仓库发生变化，请重试。") }
            return record.copy(state = RecordState.RUNNING).also(::save)
        } catch (e: Exception) {
            recordsDir.resolve("${record.id}.record").delete()
            throw e
        } finally { pendingRoots = emptySet() }
    }

    fun finish(record: OperationRecord, result: GitResult): String? {
        try {
            val after = capture(record.before.files.keys, store = true)
            copyObjects(after.roots)
            val state = when {
                result.failure == GitFailure.UNKNOWN -> RecordState.NEEDS_RECOVERY
                after.operation != null && record.before.operation == null -> RecordState.CONFLICTED
                record.before.operation != null -> RecordState.CHILD
                else -> RecordState.COMPLETED
            }
            save(record.copy(state = state, after = after, detail = result.output, requiresReview = record.requiresReview || state == RecordState.CONFLICTED))
            var undoId = record.id.takeIf { state == RecordState.COMPLETED && !record.requiresReview && !sameState(record.before, after) }
            if (record.before.operation != null && after.operation == null) {
                records().firstOrNull { it.state == RecordState.CONFLICTED && it.after?.operation == record.before.operation }?.let { parent ->
                    val final = capture(parent.before.files.keys + after.files.keys, true)
                    save(parent.copy(state = RecordState.COMPLETED, after = final, detail = result.output, requiresReview = true))
                    undoId = null // Paused transactions may contain external edits; require explicit impact review.
                }
            }
            requireCapacity()
            return undoId
        } catch (e: Exception) {
            save(record.copy(state = RecordState.NEEDS_RECOVERY, detail = "${result.output}\n${e.message}"))
            throw RecoveryRequiredException(t("Operation finished but recovery recording needs attention: ${e.message}", "操作已执行，但恢复记录需要处理：${e.message}"), e)
        }
    }

    /** Inspect an interrupted command without rerunning it. Explicit user action only. */
    fun inspect(id: String): GitResult = attemptGitOperation {
        RecoveryIndexLock.recover(cli.ownDirectory, records())
        if (cli.commonDirectory != cli.ownDirectory) RecoveryIndexLock.recover(cli.commonDirectory, records(true))
        val record = records().first { it.id == id }
        check(record.state != RecordState.RESTORING) { t("Restore was interrupted. Restore this point again to finish.", "恢复被中断，请再次恢复此点以完成。") }
        val after = capture(record.before.files.keys, true)
        copyObjects(after.roots)
        save(record.copy(after = after, state = if (after.operation != null) RecordState.CONFLICTED else RecordState.COMPLETED, requiresReview = true))
        GitResult(null, t("Repository state recorded. No Git command was retried.", "已记录当前仓库状态，没有重试 Git 写操作。"))
    }

    fun restore(id: String, historical: Boolean = false, expectedFingerprint: String? = null): GitResult = attemptGitOperation {
        cli.requireRecoverySupport()
        initialize()
        RecoveryIndexLock.recover(cli.ownDirectory, records())
        if (cli.commonDirectory != cli.ownDirectory) RecoveryIndexLock.recover(cli.commonDirectory, records(true))
        val entry = records().first { it.id == id }
        check(entry.before.operation == null) { t("Restore the parent operation, not a paused intermediate step.", "请恢复完整操作，不恢复暂停中的中间步骤。") }
        check(cli.currentOperation() == null) { t("Abort the current Git operation before restoring.", "请先中止当前 Git 操作，再恢复。") }
        val target = if (entry.state == RecordState.RESTORING) checkNotNull(entry.after) else entry.before
        val current = capture(target.files.keys + entry.after?.files.orEmpty().keys, true)
        if (expectedFingerprint != null) check(fingerprint(current) == expectedFingerprint) { t("Recovery preview expired. Review the current state again.", "恢复预览已过期，请重新核对当前状态。") }
        if (!historical) {
            recoveryCheck(!entry.requiresReview) { t("This operation involved a network transfer, pause or interruption. Review its recovery impact in Recovery first.", "此操作涉及网络、暂停或中断，请先到恢复记录查看恢复影响。") }
            val expected = entry.after ?: throw RecoveryRequiredException(t("No final state; review recovery first", "缺少最终状态，请先核对恢复记录"))
            recoveryCheck(sameState(current, expected)) { t("Repository changed since this operation. Use explicit recovery to review overwriting it.", "此操作后仓库已改变，请使用显式恢复并核对覆盖范围。") }
        }
        validateContent(target)
        val recovery = OperationRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), identity, "Restore ${entry.label}", RecordState.RESTORING, current, target)
        copyObjects(current.roots)
        save(recovery)
        requireCapacity()
        check(capture(current.files.keys, false) == current) { "Repository changed before restoration" }
        try {
            // Import only from our private object database. It remains usable after original reflog expiry/GC.
            copyObjects(target.roots, reverse = true)
            cli.restoreReferences(current, target)
            save(recovery.copy(detail = "refs restored"))
            restoreConfig(current.config, target.config, recovery.id)
            save(recovery.copy(detail = "config restored"))
            restoreStash(current.stash, target.stash, recovery)
            restoreFilesAndIndex(current, target, recovery.id)
            val after = capture(current.files.keys + target.files.keys, true)
            // Replacing a symlink parent creates a structural directory which old snapshots may
            // represent only through their child paths. It is not an extra user file.
            val comparable = after.copy(files = after.files.filterNot { (path, saved) ->
                path !in target.files && saved.kind == "directory" && target.files.any { (child, value) -> child.startsWith("$path/") && value.kind != "missing" }
            })
            check(sameState(comparable, target)) { "Repository changed before restoration completed" }
            save(recovery.copy(state = RecordState.COMPLETED, after = after, detail = ""))
            save(entry.copy(state = RecordState.UNDONE))
            GitResult(null, t("Local state restored", "本地状态已恢复"))
        } catch (e: Exception) {
            save(recovery.copy(state = RecordState.RESTORING, detail = e.message.orEmpty()))
            GitResult(GitFailure.UNKNOWN, t("Restore is incomplete. Recovery data was kept: ${e.message}", "恢复未完成，恢复数据已保留：${e.message}"), needsRecovery = true)
        }
    }

    fun preview(id: String): RecoveryPreview {
        val record = records().first { it.id == id }
        val target = if (record.state == RecordState.RESTORING) checkNotNull(record.after) else record.before
        val current = capture(target.files.keys + record.after?.files.orEmpty().keys, false)
        return RecoveryPreview(id, fingerprint(current), (current.files.keys + target.files.keys).filter { current.files[it] != target.files[it] },
            (current.refs.keys + target.refs.keys).filter { current.refs[it] != target.refs[it] }, current.head, target.head,
            current.index != target.index, (current.config.keys + target.config.keys).filter { current.config[it] != target.config[it] },
            current.stash, target.stash)
    }
    internal fun previewState(request: OperationRequest): RecoverySnapshot = capture(targetPaths(request), false)

    internal fun fingerprint(snapshot: RecoverySnapshot): String {
        val bytes = ByteArrayOutputStream().also { writeSnapshot(DataOutputStream(it), snapshot) }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sameState(a: RecoverySnapshot, b: RecoverySnapshot): Boolean =
        a.copy(files = a.files.filterValues { it.kind != "missing" }) == b.copy(files = b.files.filterValues { it.kind != "missing" })

    private fun capture(extra: Set<String>, store: Boolean): RecoverySnapshot {
        val tracked = cli.read("ls-files", "--stage", "-z").split('\u0000').filter { it.isNotEmpty() }
        check(tracked.none { it.startsWith("160000 ") }) { t("Recovery for submodules is not supported.", "暂不支持含子模块的完整恢复。") }
        check(cli.config("core.sparseCheckout") != "true") { "Recovery for sparse checkouts is not supported" }
        val refLines = cli.read("for-each-ref", "--format=%(refname) %(objectname) %(symref)").lineSequence().filter { it.isNotBlank() }.map { it.split(' ', limit = 3) }.toList()
        val refs = refLines.associate { fields -> fields[0] to (fields.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { "ref: ${cli.read("symbolic-ref", "--no-recurse", fields[0]).trim()}" } ?: fields[1]) }
        val head = cli.read("rev-parse", "--git-path", "HEAD").trim().let { cli.workTree.resolve(it).readText().trim() }
        val stash = cli.read("stash", "list", "--format=%H%x00%gs").lineSequence().filter { it.isNotEmpty() }.map { it.substringBefore('\u0000') to it.substringAfter('\u0000') }.toList()
        val paths = (tracked.map { it.substringAfter('\t') } + cli.read("ls-files", "--others", "--exclude-standard", "-z").split('\u0000').filter { it.isNotEmpty() } + extra).toSortedSet()
        val files = sortedMapOf<String, SavedPath>()
        fun visit(name: String) {
            val path = safePath(name)
            // A symlink/file in a parent is itself the object which would be overwritten.
            blockingParent(path)?.let { parent -> visit(cli.workTree.canonicalFile.toPath().relativize(parent).toString()); return }
            if (name in files) return
            val attrs = if (Files.exists(path, noFollow)) Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, noFollow) else null
            val permissions = if (attrs != null && !attrs.isSymbolicLink) runCatching { Files.getPosixFilePermissions(path, noFollow).sortedBy { it.name }.joinToString(",") { it.name } }.getOrDefault("") else ""
            files[name] = when {
                attrs == null -> SavedPath("missing", "", "")
                attrs.isSymbolicLink -> SavedPath("link", Files.readSymbolicLink(path).toString(), "")
                attrs.isRegularFile -> SavedPath("file", hashFile(path.toFile(), store), permissions)
                attrs.isDirectory -> {
                    Files.newDirectoryStream(path).use { entries -> entries.forEach { child -> visit(cli.workTree.canonicalFile.toPath().relativize(child).toString()) } }
                    SavedPath("directory", "", permissions)
                }
                else -> error("Unsupported special file: $name")
            }
        }
        paths.forEach(::visit)
        val indexes = (listOf(cli.ownDirectory.resolve("index")) + cli.ownDirectory.listFiles().orEmpty().filter { it.name.startsWith("sharedindex.") })
            .filter { it.isFile }.associate { it.name to hashFile(it, store) }
        val roots = (refLines.map { it[1] } + stash.map { it.first } + tracked.map { it.substringBefore('\t').split(' ')[1] } + listOfNotNull(head.takeUnless { it.startsWith("ref:") })).filter { it.any { c -> c != '0' } }.toSet()
        return RecoverySnapshot(head, refs, config(), files, indexes, stash, roots, cli.operationIdentity())
    }

    private fun config(): Map<String, List<String>> = cli.read("config", "--local", "--null", "--list").split('\u0000').filter { it.isNotEmpty() }
        .groupBy({ it.substringBefore('\n') }, { it.substringAfter('\n', "") })

    private fun targetPaths(request: OperationRequest): Set<String> {
        val paths = request.paths.toMutableSet()
        request.rebaseRange?.let { paths += cli.rebasePaths(it) }
        // Continuing a paused replay must protect the same transient paths after an application restart.
        if (request.kind == OperationKind.REBASE && request.rebaseRange == null) {
            val control = cli.ownDirectory.resolve("rebase-merge").takeIf { it.isDirectory } ?: cli.ownDirectory.resolve("rebase-apply")
            val original = control.resolve("orig-head"); val onto = control.resolve("onto")
            if (original.isFile && onto.isFile) paths += cli.rebasePaths(original.readText().trim() to onto.readText().trim())
        }
        if (request.kind in setOf(OperationKind.BRANCH, OperationKind.MERGE, OperationKind.REBASE, OperationKind.RESET, OperationKind.CHERRY_PICK, OperationKind.REVERT, OperationKind.STASH, OperationKind.PULL)) {
            val candidates = request.arguments.filter { !it.startsWith('-') } + request.revisions + if (request.kind == OperationKind.STASH) listOf("refs/stash", "refs/stash^3") else emptyList()
            for (arg in candidates) {
                val resolved = cli.raw(listOf("rev-parse", "--verify", "--end-of-options", "$arg^{tree}"))
                if (resolved.exitCode == 0) paths += cli.read("ls-tree", "-r", "--name-only", "-z", resolved.text.trim()).split('\u0000').filter { it.isNotEmpty() }
                if (resolved.exitCode == 0 && request.kind in setOf(OperationKind.REVERT, OperationKind.CHERRY_PICK)) {
                    // Reverting a deletion can overwrite an ignored file absent from the target tree.
                    val commit = cli.raw(listOf("rev-parse", "--verify", "--end-of-options", "$arg^{commit}"))
                    if (commit.exitCode == 0) cli.read("rev-list", "--parents", "-n", "1", commit.text.trim()).trim().split(' ').drop(1).forEach { parent ->
                        paths += cli.read("ls-tree", "-r", "--name-only", "-z", parent).split('\u0000').filter { it.isNotEmpty() }
                    }
                }
            }
        }
        return paths
    }

    /** Walk from the root so even inspecting a nested parent never follows an earlier symlink. */
    private fun blockingParent(path: Path): Path? {
        val root = cli.workTree.canonicalFile.toPath()
        var parent = root
        for (part in root.relativize(path.parent)) {
            parent = parent.resolve(part)
            if (Files.isSymbolicLink(parent) || (Files.exists(parent, noFollow) && !Files.isDirectory(parent, noFollow))) return parent
        }
        return null
    }

    private fun safePath(name: String): Path {
        val root = cli.workTree.canonicalFile.toPath()
        val path = root.resolve(name).normalize()
        require(name.isNotEmpty() && !Path.of(name).isAbsolute && path != root && path.startsWith(root)) { "Unsafe recovery path: $name" }
        require(path.none { it.toString().equals(".git", ignoreCase = true) }) { "Git metadata is not a worktree file" }
        require(!path.startsWith(cli.ownDirectory.toPath()) && !path.startsWith(cli.commonDirectory.toPath())) { "Cannot restore Git metadata as a file" }
        return path
    }

    private fun hashFile(file: File, store: Boolean): String {
        fun digest(input: InputStream, output: OutputStream = OutputStream.nullOutputStream()): String {
            val hash = MessageDigest.getInstance("SHA-256")
            input.use {
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = it.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); output.write(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val id = digest(file.inputStream())
        if (!store || rawDir.resolve(id).exists()) return id
        val remaining = quotaBytes - size()
        val temp = File.createTempFile("saving-", ".tmp", rawDir)
        try {
            val limited = object : FilterOutputStream(temp.outputStream()) {
                var written = 0L
                override fun write(b: Int) { recoveryCheck(++written <= remaining) { "Recovery quota exceeded" }; out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { written += len; recoveryCheck(written <= remaining) { "Recovery quota exceeded" }; out.write(b, off, len) }
            }
            val actual = GZIPOutputStream(limited).use { digest(file.inputStream(), it) }
            check(actual == id) { "File changed while saving: ${file.name}" }
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), rawDir.resolve(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return id
        } finally { temp.delete() }
    }

    private fun validateContent(snapshot: RecoverySnapshot) {
        val hashes = snapshot.index.values + snapshot.files.values.filter { it.kind == "file" }.map { it.content }
        hashes.distinct().forEach { id ->
            require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid recovery content ID" }
            val digest = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(rawDir.resolve(id).inputStream()).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == id) { "Recovery content is damaged: $id" }
        }
        snapshot.files.keys.forEach(::safePath)
    }

    private fun copyObjects(roots: Set<String>, reverse: Boolean = false) {
        val src = if (reverse) FileRepositoryBuilder().setGitDir(objectDir).build() else GitRepo.open(cli.workTree).repository
        val dst = if (reverse) GitRepo.open(cli.workTree).repository else FileRepositoryBuilder().setGitDir(objectDir).build()
        var used = if (reverse) 0L else size()
        src.use { source -> dst.use { destination ->
            ObjectWalk(source).use { walk -> destination.newObjectInserter().use { insert ->
                roots.forEach { walk.markStart(walk.parseAny(ObjectId.fromString(it))) }
                fun copy(id: ObjectId) {
                    if (destination.objectDatabase.has(id)) return
                    val loader = source.open(id)
                    if (reverse) loader.openStream().use { insert.insert(loader.type, loader.size, it) }
                    else {
                        // Stream the actual compressed object through a byte budget. A raw-size estimate
                        // rejects compressible files and is not a valid deflate upper bound for others.
                        val file = objectDir.resolve("objects/${id.name.take(2)}/${id.name.drop(2)}")
                        file.parentFile.mkdirs()
                        val temp = File.createTempFile("saving-", ".tmp", file.parentFile)
                        try {
                            val budget = quotaBytes - used
                            FileOutputStream(temp).use { output ->
                                val limited = object : FilterOutputStream(output) {
                                    var written = 0L
                                    override fun write(b: Int) { recoveryCheck(++written <= budget) { "Recovery quota exceeded while copying Git objects" }; out.write(b) }
                                    override fun write(b: ByteArray, off: Int, len: Int) { written += len; recoveryCheck(written <= budget) { "Recovery quota exceeded while copying Git objects" }; out.write(b, off, len) }
                                }
                                val digest = MessageDigest.getInstance("SHA-1")
                                java.util.zip.DeflaterOutputStream(limited).use { compressed ->
                                    java.security.DigestOutputStream(compressed, digest).use { content ->
                                        content.write("${org.eclipse.jgit.lib.Constants.typeString(loader.type)} ${loader.size}\u0000".toByteArray())
                                        loader.openStream().use { it.copyTo(content) }
                                    }
                                }
                                check(ObjectId.fromRaw(digest.digest()) == id) { "Git object changed while saving" }
                            }
                            FileOutputStream(temp, true).use { it.fd.sync() }
                            val length = temp.length()
                            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            used += length
                        } finally { temp.delete() }
                    }
                }
                while (true) { val commit = walk.next() ?: break; copy(commit) }
                while (true) { val obj = walk.nextObject() ?: break; copy(obj) }
                insert.flush()
            } }
        } }
        if (!reverse) requireCapacity()
    }

    private fun restoreConfig(current: Map<String, List<String>>, target: Map<String, List<String>>, id: String) {
        val changed = (current.keys + target.keys).filter { current[it] != target[it] }
        if (changed.isEmpty()) return
        RecoveryIndexLock(cli.commonDirectory, id, "config").use { owned ->
            val latest = config()
            changed.forEach { key -> check(latest[key] == current[key]) { "Configuration changed: $key" } }
            val temporary = File.createTempFile("gitviz-config-", ".tmp", cli.commonDirectory)
            try {
                cli.commonDirectory.resolve("config").takeIf { it.isFile }?.copyTo(temporary, overwrite = true)
                changed.forEach { key ->
                    val removed = cli.raw(listOf("config", "--file", temporary.absolutePath, "--unset-all", key))
                    check(removed.exitCode in setOf(0, 5)) { removed.output }
                    target[key].orEmpty().forEach { value -> cli.raw(listOf("config", "--file", temporary.absolutePath, "--add", key, value)).requireSuccess() }
                }
                FileOutputStream(owned.lock.toFile()).use { out -> temporary.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                owned.commit()
            } finally { temporary.delete(); File(temporary.path + ".lock").delete() }
        }
    }

    private fun restoreStash(current: List<Pair<String, String>>, target: List<Pair<String, String>>, record: OperationRecord) {
        if (current == target) return
        fun list() = cli.read("stash", "list", "--format=%H%x00%gs").lineSequence().filter { it.isNotEmpty() }.map { it.substringBefore('\u0000') to it.substringAfter('\u0000') }.toList()
        check(list() == current) { "Stash list changed" }
        save(record.copy(detail = "restoring stash: clear"))
        if (current.isNotEmpty()) cli.referenceTransaction(listOf("delete refs/stash ${current.first().first}")) {
            check(list() == current) { "Stash list changed before clearing" }
        }
        var expected = emptyList<Pair<String, String>>()
        target.asReversed().forEachIndexed { i, entry ->
            save(record.copy(detail = "restoring stash: $i"))
            val command = if (expected.isEmpty()) "create refs/stash ${entry.first}" else "update refs/stash ${entry.first} ${expected.first().first}"
            cli.referenceTransaction(listOf(command), entry.second) { check(list() == expected) { "Stash list changed during restoration" } }
            expected = listOf(entry) + expected
        }
    }

    private fun restoreFilesAndIndex(current: RecoverySnapshot, target: RecoverySnapshot, recoveryId: String) {
        RecoveryIndexLock(cli.ownDirectory, recoveryId).use { ownedLock ->
            val lock = ownedLock.lock
            val nowIndex = cli.ownDirectory.resolve("index").takeIf { it.isFile }?.let { hashFile(it, false) }
            check(nowIndex == current.index["index"]) { "Index changed before restoration" }
            // Verify all paths immediately before changing any files; do not follow symlink parents.
            val observed = capture(current.files.keys + target.files.keys, false)
            check(observed.refs == target.refs && observed.head == target.head && observed.config == target.config && observed.stash == target.stash && observed.operation == null) { "Repository metadata changed during restoration" }
            check(sameState(observed.copy(refs = current.refs, head = current.head, config = current.config, stash = current.stash, roots = current.roots), current)) { "Worktree changed before restoration" }
            val changed = (current.files.keys + target.files.keys).filter { current.files[it] != target.files[it] }
            changed.sortedByDescending { it.count { c -> c == '/' } }.forEach { name ->
                val path = safePath(name)
                // The blocker itself is removed later; its children do not exist in this worktree.
                if (blockingParent(path) != null) return@forEach
                if (Files.exists(path, noFollow)) {
                    if (Files.isDirectory(path, noFollow)) {
                        // delete() refuses nonempty directories; never recursively discard unrelated files.
                        if (target.files[name]?.kind != "directory") Files.delete(path)
                    } else Files.delete(path)
                }
            }
            changed.sortedBy { it.count { c -> c == '/' } }.forEach { name ->
                val saved = target.files[name] ?: return@forEach
                val path = safePath(name)
                if (saved.kind == "missing") return@forEach
                check(blockingParent(path) == null) { "Blocked recovery path: $name" }
                Files.createDirectories(path.parent)
                check(blockingParent(path) == null) { "Recovery path changed: $name" }
                when (saved.kind) {
                    "directory" -> Files.createDirectories(path)
                    "link" -> Files.createSymbolicLink(path, Path.of(saved.content))
                    "file" -> GZIPInputStream(rawDir.resolve(saved.content).inputStream()).use { input -> Files.newOutputStream(path).use { input.copyTo(it) } }
                }
                if (saved.permissions.isNotEmpty()) Files.setPosixFilePermissions(path, saved.permissions.split(',').map { PosixFilePermission.valueOf(it) }.toSet())
            }
            target.index.filterKeys { it != "index" }.forEach { (name, hash) ->
                require(name.startsWith("sharedindex.") && '/' !in name)
                GZIPInputStream(rawDir.resolve(hash).inputStream()).use { input -> cli.ownDirectory.resolve(name).outputStream().use { input.copyTo(it) } }
            }
            target.index["index"]?.let { hash ->
                GZIPInputStream(rawDir.resolve(hash).inputStream()).use { input -> Files.newOutputStream(lock).use { input.copyTo(it) } }
                ownedLock.commit()
            } ?: Files.deleteIfExists(cli.ownDirectory.resolve("index").toPath())
        }
    }

    fun cleanup(clearCompleted: Boolean = false) {
        initialize()
        val expired = System.currentTimeMillis() - retentionDays * 86_400_000L
        val removable = records(true).filter { it.state in setOf(RecordState.COMPLETED, RecordState.UNDONE, RecordState.CHILD) }.sortedBy { it.time }
        removable.filter { clearCompleted || it.time < expired }.forEach { recordsDir.resolve("${it.id}.record").delete() }
        prune()
        for (record in removable) {
            if (size() <= quotaBytes) break
            recordsDir.resolve("${record.id}.record").delete()
            prune()
        }
    }
    private fun prune() {
        // No Git auto-GC runs in this store. Reachability comes from durable manifests, including all active transactions.
        val remaining = records(true)
        val snapshots = remaining.flatMap { listOfNotNull(it.before, it.after) }
        val hashes = snapshots.flatMap { it.files.values.filter { f -> f.kind == "file" }.map { f -> f.content } + it.index.values }.toSet()
        rawDir.listFiles().orEmpty().filter { it.name !in hashes }.forEach { it.delete() }
        val live = HashSet<String>()
        FileRepositoryBuilder().setGitDir(objectDir).build().use { repo -> ObjectWalk(repo).use { walk ->
            (snapshots.flatMap { it.roots } + pendingRoots).distinct().forEach { walk.markStart(walk.parseAny(ObjectId.fromString(it))) }
            while (true) { val c = walk.next() ?: break; live += c.name }
            while (true) { val o = walk.nextObject() ?: break; live += o.name }
        } }
        objectDir.resolve("objects").listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{2}")) }.forEach { folder ->
            folder.listFiles().orEmpty().filter { folder.name + it.name !in live }.forEach { it.delete() }
        }
    }
    private fun requireCapacity() { recoveryCheck(size() <= quotaBytes) { t("Recovery storage is full. Increase its quota or clear old points in Recovery.", "恢复存储空间不足，请在恢复页调整配额或清理旧恢复点。") } }

    private fun atomic(file: File, write: (OutputStream) -> Unit) {
        file.parentFile.mkdirs()
        val temp = File.createTempFile("saving-", ".tmp", file.parentFile)
        try {
            val budget = if (file.name == "settings") Long.MAX_VALUE else quotaBytes - size()
            FileOutputStream(temp).use { output ->
                val limited = object : FilterOutputStream(output) {
                    var written = 0L
                    override fun write(b: Int) { recoveryCheck(++written <= budget) { "Recovery quota exceeded" }; out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { written += len; recoveryCheck(written <= budget) { "Recovery quota exceeded" }; out.write(b, off, len) }
                }
                write(limited); limited.flush(); output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun save(record: OperationRecord) = atomic(recordsDir.resolve("${record.id}.record")) { stream ->
        val out = DataOutputStream(stream)
        out.writeInt(2); out.writeUTF(record.id); out.writeLong(record.time); out.writeUTF(record.worktree); out.writeUTF(record.label.take(16000)); out.writeUTF(record.state.name); out.writeUTF(record.detail.take(16000))
        writeSnapshot(out, record.before); out.writeBoolean(record.after != null); record.after?.let { writeSnapshot(out, it) }; out.writeBoolean(record.requiresReview)
    }
    private fun writeSnapshot(out: DataOutputStream, s: RecoverySnapshot) {
        fun strings(values: Collection<String>) { out.writeInt(values.size); values.forEach(out::writeUTF) }
        fun map(values: Map<String, String>) { out.writeInt(values.size); values.toSortedMap().forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) } }
        out.writeUTF(s.head); map(s.refs); out.writeUTF(s.operation.orEmpty()); map(s.index); strings(s.roots.sorted())
        out.writeInt(s.config.size); s.config.toSortedMap().forEach { (k, v) -> out.writeUTF(k); strings(v) }
        out.writeInt(s.stash.size); s.stash.forEach { (id, message) -> out.writeUTF(id); out.writeUTF(message) }
        out.writeInt(s.files.size); s.files.toSortedMap().forEach { (name, f) -> out.writeUTF(name); out.writeUTF(f.kind); out.writeUTF(f.content); out.writeUTF(f.permissions) }
    }
    private fun readRecord(file: File): OperationRecord = DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
        val version = input.readInt(); check(version in 1..2) { "Unsupported recovery format" }
        val id = input.readUTF(); require(id.matches(Regex("[0-9a-f-]{36}")))
        val time = input.readLong(); val worktree = input.readUTF(); val label = input.readUTF(); val state = RecordState.valueOf(input.readUTF()); val detail = input.readUTF()
        val before = readSnapshot(input); val after = if (input.readBoolean()) readSnapshot(input) else null
        OperationRecord(id, time, worktree, label, state, before, after, detail, if (version >= 2) input.readBoolean() else true)
    }
    private fun readSnapshot(input: DataInputStream): RecoverySnapshot {
        fun count() = input.readInt().also { require(it in 0..2_000_000) }
        fun strings() = List(count()) { input.readUTF() }
        fun map() = buildMap { repeat(count()) { put(input.readUTF(), input.readUTF()) } }
        val head = input.readUTF(); val refs = map(); val operation = input.readUTF().ifEmpty { null }; val index = map(); val roots = strings().toSet()
        val config = buildMap { repeat(count()) { put(input.readUTF(), strings()) } }
        val stash = List(count()) { input.readUTF() to input.readUTF() }
        val files = buildMap { repeat(count()) { put(input.readUTF(), SavedPath(input.readUTF(), input.readUTF(), input.readUTF())) } }
        return RecoverySnapshot(head, refs, config, files, index, stash, roots, operation)
    }
}

internal data class RecoveryPreview(val id: String, val fingerprint: String, val files: List<String>, val refs: List<String>, val oldHead: String, val newHead: String,
    val indexChanged: Boolean, val configKeys: List<String>, val oldStash: List<Pair<String, String>>, val newStash: List<Pair<String, String>>)
