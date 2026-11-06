package gitviz

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Requests carry the actual command, not localized UI strings. */
internal enum class OperationKind { INDEX, COMMIT, BRANCH, TAG, MERGE, CHERRY_PICK, REVERT, RESET, STASH, FETCH, PULL, PUSH, CLONE, FILE, RESTORE, REBASE, OTHER }
internal data class OperationRequest(val kind: OperationKind, val arguments: List<String> = emptyList(), val network: Boolean = false,
                                     val paths: Set<String> = emptySet(), val revisions: Set<String> = emptySet(), val rebaseRange: Pair<String, String>? = null) {
    companion object {
        fun command(args: List<String>, network: Boolean = false): OperationRequest {
            var i = 0
            while (args.getOrNull(i) == "-c") i += 2
            val kind = when (args.getOrNull(i)) {
                "add", "restore", "rm", "apply" -> OperationKind.INDEX
                "commit" -> OperationKind.COMMIT
                "branch", "switch", "checkout" -> OperationKind.BRANCH
                "tag" -> OperationKind.TAG
                "merge" -> OperationKind.MERGE
                "cherry-pick" -> OperationKind.CHERRY_PICK
                "revert" -> OperationKind.REVERT
                "reset" -> OperationKind.RESET
                "stash" -> OperationKind.STASH
                "fetch" -> OperationKind.FETCH
                "pull" -> OperationKind.PULL
                "push" -> OperationKind.PUSH
                "clone" -> OperationKind.CLONE
                "rebase" -> OperationKind.REBASE
                "clean" -> OperationKind.FILE
                else -> OperationKind.OTHER
            }
            return OperationRequest(kind, args, network)
        }
    }
}

/** Shared across every GitCli for a common repository, including linked worktrees. */
internal class OperationCoordinator(private val cli: GitCli) {
    val recordId: String? get() = records.get()[cli.commonDirectory.canonicalPath]
    fun execute(request: OperationRequest, action: () -> GitResult): GitResult {
        val key = if (request.kind == OperationKind.CLONE) cli.workTree.canonicalPath else cli.commonDirectory.canonicalPath
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock { withLease(key, request.kind) {
            if (GitProcess.hasUnfinishedProcess()) return@withLease GitResult(GitFailure.UNKNOWN, t("A previous Git process has not stopped. Refresh after it exits.", "先前的 Git 进程尚未结束，请等待后刷新。"))
            if (request.kind in setOf(OperationKind.OTHER, OperationKind.CLONE, OperationKind.RESTORE) || nesting.get().contains(key)) return@withLease attemptGitOperation(action)
            attemptGitOperation {
                cli.requireRecoverySupport()
                val store = RecoveryStore(cli)
                val record = store.begin(request)
                nesting.get().add(key)
                records.get()[key] = record.id
                try {
                    val result = attemptGitOperation(action)
                    try { val id = store.finish(record, result); result.copy(recoveryId = id.takeIf { result.ok }) }
                    catch (e: Exception) { GitResult(GitFailure.UNKNOWN, e.message.orEmpty(), needsRecovery = e is RecoveryRequiredException) }
                } finally { nesting.get().remove(key); records.get().remove(key) }
            }
        } }
    }
    private fun withLease(key: String, kind: OperationKind, action: () -> GitResult): GitResult {
        if (kind == OperationKind.CLONE || key in leases.get()) return action()
        return attemptGitOperation {
            java.nio.channels.FileChannel.open(cli.commonDirectory.resolve("gitviz-operation.lock").toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val lease = channel.tryLock() ?: return@attemptGitOperation GitResult(GitFailure.OTHER, t("Another GitViz process is changing this repository", "另一个 GitViz 进程正在修改此仓库"))
                lease.use {
                    leases.get().add(key)
                    try { action() } finally { leases.get().remove(key) }
                }
            }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        private val leases = ThreadLocal.withInitial { HashSet<String>() }
        private val records = ThreadLocal.withInitial { HashMap<String, String>() }
        private val nesting = ThreadLocal.withInitial { HashSet<String>() }
    }
}
