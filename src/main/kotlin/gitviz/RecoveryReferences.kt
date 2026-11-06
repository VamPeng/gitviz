package gitviz

/** Native Git owns all ref locks and reflogs. Verify symbolic targets while prepare holds the locks. */
internal fun GitCli.referenceTransaction(commands: List<String>, message: String = "GitViz restore", verifyPrepared: () -> Unit = {}) {
    if (commands.isEmpty()) return
    raw(listOf("update-ref", "--no-deref", "--create-reflog", "--stdin", "-m", message),
        (listOf("start") + commands + "prepare").joinToString("\n", postfix = "\n").toByteArray(), preparedCheck = verifyPrepared).requireSuccess()
}
internal fun GitCli.directRefValue(ref: String): String? {
    val symbolic = raw(listOf("symbolic-ref", "--no-recurse", "-q", ref))
    if (symbolic.exitCode == 0) return "ref: ${symbolic.text.trim()}"
    val oid = raw(listOf("show-ref", "--verify", "--hash", ref))
    return oid.text.trim().takeIf { oid.exitCode == 0 }
}
private fun resolveSaved(value: String?, refs: Map<String, String>): String? {
    var current = value
    val seen = HashSet<String>()
    while (current?.startsWith("ref: ") == true) {
        val name = current.removePrefix("ref: "); check(seen.add(name)) { "Cyclic symbolic reference" }
        current = refs[name]
    }
    return current
}
private fun refCommand(ref: String, old: String?, next: String?, refs: Map<String, String>): String = when {
    next == null && old?.startsWith("ref: ") == true -> "symref-delete $ref ${old.removePrefix("ref: ")}"
    next == null -> "delete $ref $old"
    next.startsWith("ref: ") && old == null -> "symref-create $ref ${next.removePrefix("ref: ")}"
    next.startsWith("ref: ") -> "symref-update $ref ${next.removePrefix("ref: ")} " +
        if (old!!.startsWith("ref: ")) "ref ${old.removePrefix("ref: ")}" else "oid $old"
    old == null -> "create $ref $next"
    else -> "update $ref $next ${resolveSaved(old, refs) ?: "0".repeat(40)}"
}
internal fun GitCli.restoreReferences(current: RecoverySnapshot, target: RecoverySnapshot) {
    val changed = (current.refs.keys + target.refs.keys).filter { it != "refs/stash" && current.refs[it] != target.refs[it] }
    val headPath = workTree.resolve(read("rev-parse", "--git-path", "HEAD").trim())
    val occupied = read("worktree", "list", "--porcelain").split("\n\n").filter { !it.startsWith("worktree ${workTree.canonicalPath}\n") }
        .mapNotNull { block -> block.lines().firstOrNull { it.startsWith("branch ") }?.removePrefix("branch ") }
    check(changed.none { it in occupied }) { "A target branch is checked out in another worktree" }
    referenceTransaction(changed.map { ref -> refCommand(ref, current.refs[ref], target.refs[ref], current.refs) }) {
        check(headPath.readText().trim() == current.head) { "HEAD changed during reference restoration" }
        changed.forEach { ref -> check(directRefValue(ref) == current.refs[ref]) { "Reference changed: $ref" } }
    }
    // Git disallows explicit HEAD and its updated referent in one transaction. A second prepared
    // transaction locks HEAD; compare the literal old target as well as Git's old OID condition.
    referenceTransaction(listOf(refCommand("HEAD", current.head, target.head, target.refs))) {
        check(headPath.readText().trim() == current.head) { "HEAD changed before restoration" }
    }
}
