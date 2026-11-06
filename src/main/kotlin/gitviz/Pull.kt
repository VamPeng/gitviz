package gitviz

internal enum class PullStrategy { MERGE, REBASE, FF_ONLY }
internal data class PullSettings(val strategy: PullStrategy, val remote: String, val branch: String, val setUpstream: Boolean, val expectedHead: String? = null, val expectedBranch: String? = null)
internal data class PullOptions(val strategy: PullStrategy, val remotes: List<String>, val current: String,
    val upstreamRemote: String?, val upstreamBranch: String?, val branches: Map<String, List<String>>, val head: String)
internal data class PreparedPull(val result: GitResult, val intent: OperationIntent?)

internal fun GitCli.pullOptions(): PullOptions {
    val current = read("symbolic-ref", "--short", "HEAD").trim()
    val remotes = read("remote").lines().filter { it.isNotBlank() }
    val remote = config("branch.$current.remote")
    val branch = config("branch.$current.merge")?.removePrefix("refs/heads/")
    val refs = read("for-each-ref", "--format=%(refname)", "refs/remotes/").lines()
    val branches = remotes.associateWith { name ->
        val specs = raw(listOf("config", "--get-all", "remote.$name.fetch")).text.lines().filter { it.isNotBlank() && !it.startsWith('^') }
            .mapNotNull { runCatching { org.eclipse.jgit.transport.RefSpec(it) }.getOrNull() }
        refs.filter { !it.endsWith("/HEAD") }.flatMap { ref -> specs.filter { it.destination != null && it.matchDestination(ref) }
            .mapNotNull { it.expandFromDestination(ref).source?.takeIf { source -> source.startsWith("refs/heads/") }?.removePrefix("refs/heads/") } }.distinct().sorted()
    }
    val strategy = runCatching { PullStrategy.valueOf(config("gitviz.pullstrategy").orEmpty()) }.getOrDefault(PullStrategy.MERGE)
    return PullOptions(strategy, remotes + listOfNotNull(remote?.takeIf { it == "." }), current, remote, branch, branches, headIdentity().also { check(it.startsWith("ref: refs/heads/$current|")) { "Current branch changed" } })
}

/** Fetch is a durable first step. Integration is a separate frozen preview; canceling it keeps fetched refs. */
internal fun GitCli.preparePull(settings: PullSettings): PreparedPull {
    var intent: OperationIntent? = null
    val result = coordinator.execute(OperationRequest(OperationKind.FETCH, listOf("fetch", settings.remote), network = true)) {
        check(currentOperation() == null) { "Finish the current Git operation first" }
        val current = read("symbolic-ref", "--short", "HEAD").trim()
        val sourceHead = headIdentity()
        check((settings.expectedHead == null || settings.expectedHead == sourceHead) && (settings.expectedBranch == null || settings.expectedBranch == current)) { "Pull dialog expired: current branch changed" }
        check(settings.remote == "." || settings.remote in read("remote").lines()) { "Unknown remote" }
        check(raw(listOf("check-ref-format", "refs/heads/${settings.branch}")).exitCode == 0) { "Invalid branch" }
        val source = "refs/heads/${settings.branch}"
        val specs = raw(listOf("config", "--get-all", "remote.${settings.remote}.fetch")).text.lines().filter { it.isNotBlank() && !it.startsWith('^') }
        val mapped = specs.mapNotNull { text -> runCatching { org.eclipse.jgit.transport.RefSpec(text) }.getOrNull() }
            .firstOrNull { it.matchSource(source) && it.destination != null }?.expandFromSource(source)?.destination
        val tracking = if (settings.remote == ".") source else mapped ?: "refs/remotes/${settings.remote}/${settings.branch}"
        check(settings.remote == "." || tracking.startsWith("refs/remotes/")) { "Pull requires a remote-tracking fetch destination" }
        val fetched = if (settings.remote == ".") GitResult(null, "Local upstream") else execute(OperationRequest.command(listOf("fetch", "--no-tags", "--", settings.remote, "+$source:$tracking"), network = true))
        if (!fetched.ok) return@execute fetched
        check(sourceHead == headIdentity()) { "Fetch completed, but current branch changed; integration was not started" }
        val oid = read("rev-parse", "--verify", "--end-of-options", "$tracking^{commit}").trim()
        // Configuration is part of this recorded fetch step, restored by keys during local recovery.
        raw(listOf("config", "--local", "gitviz.pullstrategy", settings.strategy.name), teach = true).requireSuccess()
        if (settings.setUpstream) {
            if (settings.remote != "." && mapped == null) raw(listOf("config", "--local", "--add", "remote.${settings.remote}.fetch", "+$source:$tracking"), teach = true).requireSuccess()
            raw(listOf("config", "--local", "branch.$current.remote", settings.remote), teach = true).requireSuccess()
            raw(listOf("config", "--local", "branch.$current.merge", "refs/heads/${settings.branch}"), teach = true).requireSuccess()
        }
        intent = OperationIntent(if (settings.strategy == PullStrategy.REBASE) PreviewKind.REBASE else PreviewKind.MERGE, oid, ffOnly = settings.strategy == PullStrategy.FF_ONLY, expectedHead = sourceHead)
        fetched.copy(output = fetched.output + "\n" + t("Fetch completed. Integration has not run; review its preview next.", "获取已完成，尚未集成；请查看下一步预览。"))
    }
    return PreparedPull(result, intent.takeIf { result.ok })
}

internal fun GitCli.headIdentity(): String = ownDirectory.resolve("HEAD").readText().trim() + "|" + read("rev-parse", "HEAD").trim()
