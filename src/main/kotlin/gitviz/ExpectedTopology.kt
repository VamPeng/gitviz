package gitviz

/** Projection only: replay todo topology assumes each remaining commit is kept and conflicts resolved. */
internal fun expectedTopology(commands: List<String>, start: String, initialLabels: Map<String, String>, resolve: (String) -> CommitInfo): List<CommitInfo> {
    val labels = initialLabels.toMutableMap()
    val result = mutableListOf<CommitInfo>()
    var cursor: String? = start
    commands.forEachIndexed { index, line ->
        val fields = line.substringBefore(" #").trim().split(Regex("\\s+"))
        when (fields.firstOrNull()) {
            "label", "l" -> fields.getOrNull(1)?.let { label -> cursor?.let { labels[label] = it } }
            "reset", "t" -> cursor = if (fields.drop(1).joinToString(" ") == "[new root]") null else labels[fields.getOrNull(1)] ?: error("Unknown rebase label: $line")
            "pick", "p", "merge", "m" -> {
                val merge = fields[0] in setOf("merge", "m")
                val oidIndex = if (merge && fields.getOrNull(1) in setOf("-C", "-c")) 2 else 1
                val original = resolve(fields.getOrNull(oidIndex) ?: error("Missing replay commit"))
                val others = if (merge) fields.drop(oidIndex + 1).map { label -> labels[label] ?: error("Unknown rebase label: $label") } else emptyList()
                val parents = (listOfNotNull(cursor) + others).distinct()
                val id = "expected:$index:${original.id}"
                result += original.copy(id = id, parents = parents, graphParents = parents, refs = emptyList(),
                    shortMessage = t("[expected, unverified] ", "[预计，未验证] ") + original.shortMessage)
                cursor = id
            }
            else -> error("Unsupported projected step: $line")
        }
    }
    return result.asReversed()
}
