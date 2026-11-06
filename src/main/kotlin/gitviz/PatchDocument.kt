package gitviz

import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Complete patch input, independent of truncated display strings. Selections identify original diff rows. */
internal data class PatchDocument(val file: WorkingFile, val staged: Boolean, val raw: String, val lines: List<DiffLine>,
    val base: ByteArray, val exists: Boolean, val destinationExists: Boolean, val mode: String,
    val indexStamp: String, val signature: String, val unavailable: String?, val sources: DiffSources? = null) {
    val changes: Set<Int> = lines.indices.filter { lines[it].kind in setOf(DiffLineKind.ADDED, DiffLineKind.REMOVED) }.toSet()
    fun hunk(index: Int): Set<Int> = (index + 1 until (index + 1 until lines.size).firstOrNull { lines[it].kind == DiffLineKind.HUNK }.let { it ?: lines.size }).filter { it in changes }.toSet()
    fun patch(selected: Set<Int>): ByteArray {
        check(unavailable == null) { unavailable.orEmpty() }
        require(selected.isNotEmpty() && selected.all { it in changes })
        val original = rawLines(base.decodeToString())
        val output = mutableListOf<String>()
        var cursor = 0
        var inHunk = false
        for ((i, line) in lines.withIndex()) {
            if (line.kind == DiffLineKind.HUNK) {
                val header = hunkPattern.find(line.text) ?: error("Malformed hunk")
                val startIndex = if (staged) 3 else 1
                val count = header.groupValues[startIndex + 1].toIntOrNull() ?: 1
                val start = header.groupValues[startIndex].toInt() - if (count == 0) 0 else 1
                check(start >= cursor && start <= original.size)
                output += original.subList(cursor, start); cursor = start; inHunk = true
                continue
            }
            if (!inHunk || line.kind == DiffLineKind.HEADER || line.text.isEmpty() || line.text.startsWith('\\')) continue
            val kind = if (!staged) line.kind else when (line.kind) { DiffLineKind.ADDED -> DiffLineKind.REMOVED; DiffLineKind.REMOVED -> DiffLineKind.ADDED; else -> line.kind }
            val text = line.text.drop(1) + if (lines.getOrNull(i + 1)?.text?.startsWith("\\ No newline") == true) "" else "\n"
            when (kind) {
                DiffLineKind.ADDED -> if (i in selected) output += text
                DiffLineKind.REMOVED -> {
                    check(original.getOrNull(cursor) == text) { "Patch source no longer matches index" }
                    if (i !in selected) output += original[cursor]
                    cursor++
                }
                DiffLineKind.CONTEXT -> {
                    check(original.getOrNull(cursor) == text) { "Patch context no longer matches index" }
                    output += original[cursor++]
                }
                else -> Unit
            }
        }
        output += original.drop(cursor)
        check(output.dropLast(1).all { it.endsWith('\n') }) { t("Select the adjacent end-of-file change as well, or select the whole hunk.", "请同时选择相邻的文件末尾换行改动，或选择整个 hunk。") }
        val desired = output.joinToString("").toByteArray()
        if (base.contentEquals(desired)) return byteArrayOf()
        val remove = !destinationExists && selected == changes && desired.isEmpty()
        val before = RawText(base); val after = RawText(desired)
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, before, after)
        val out = ByteArrayOutputStream()
        val a = quotePatchPath("a/${file.path}"); val b = quotePatchPath("b/${file.path}")
        out.write(buildString {
            append("diff --git $a $b\n")
            if (!exists) append("new file mode $mode\n") else if (remove) append("deleted file mode $mode\n")
            append("--- ${if (exists) a else "/dev/null"}\n+++ ${if (remove) "/dev/null" else b}\n")
        }.toByteArray())
        DiffFormatter(out).use { it.format(edits, before, after) }
        return out.toByteArray()
    }
}
private val hunkPattern = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
private fun rawLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    return parts.dropLast(1).map { "$it\n" } + parts.last().takeIf { it.isNotEmpty() }.let { if (it == null) emptyList() else listOf(it) }
}
private fun quotePatchPath(path: String): String = buildString {
    append('"')
    path.toByteArray().forEach { b -> val n = b.toInt() and 255; when {
        n == 34 || n == 92 -> { append('\\'); append(n.toChar()) }
        n < 32 || n >= 127 -> { append('\\'); append(n.toString(8).padStart(3, '0')) }
        else -> append(n.toChar())
    } }
    append('"')
}
private fun digestParts(parts: List<ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { digest.update(java.nio.ByteBuffer.allocate(8).putLong(it.size.toLong()).array()); digest.update(it) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun GitCli.indexStamp(): String = digestParts((listOf(ownDirectory.resolve("index")) + ownDirectory.listFiles().orEmpty().filter { it.name.startsWith("sharedindex.") }.sortedBy { it.name })
    .flatMap { file -> listOf(file.name.toByteArray(), if (file.exists()) Files.readAllBytes(file.toPath()) else byteArrayOf()) })

internal fun GitCli.patchDocument(file: WorkingFile, staged: Boolean): PatchDocument {
    val stamp = indexStamp()
    val path = safeWorkingPath(file.path)
    val entry = read("ls-files", "--stage", "-z", "--", ":(literal)${file.path}").split('\u0000').firstOrNull { it.isNotEmpty() }
    val metadata = entry?.substringBefore('\t')?.split(' ')
    var mode = metadata?.first() ?: if (Files.isExecutable(path)) "100755" else "100644"
    fun unavailable(reason: String) = PatchDocument(file, staged, "", emptyList(), byteArrayOf(), metadata != null, true, mode,
        stamp, digestParts(listOf(stamp.toByteArray(), reason.toByteArray(), entry.orEmpty().toByteArray())), reason)
    val maximum = 1_048_576L
    if (staged) {
        val oldSize = raw(listOf("cat-file", "-s", "HEAD:${file.path}"))
        if (oldSize.exitCode == 0 && oldSize.text.trim().toLong() > maximum) return unavailable(t("Old version exceeds 1 MiB; use whole-file staging", "旧版本超过 1 MiB，请整文件暂存"))
    }
    if (metadata?.getOrNull(1)?.let { read("cat-file", "-s", it).trim().toLong() > maximum } == true ||
        (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) > maximum)) return unavailable(t("File exceeds 1 MiB; use whole-file staging", "文件超过 1 MiB，请整文件暂存"))
    if (Files.isSymbolicLink(path) || mode !in setOf("100644", "100755")) return unavailable(t("Use whole-file staging for symbolic links", "符号链接请使用整文件暂存"))
    val base = metadata?.getOrNull(1)?.let { bytes("cat-file", "blob", it) } ?: byteArrayOf()
    val working = if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.readAllBytes(path) else byteArrayOf()
    if (strictText(base) == null || strictText(working) == null) return unavailable(t("Binary or non-UTF-8 file: use whole-file staging", "二进制或非 UTF-8 文件：请整文件暂存"))
    val args = listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--full-index", "--no-renames", "--binary") +
        when { file.untracked -> listOf("--no-index", "--", "/dev/null", file.path); staged -> listOf("--cached", "--", ":(literal)${file.path}"); else -> listOf("--", ":(literal)${file.path}") }
    val diff = raw(args)
    check(!diff.uncertain && (diff.exitCode == 0 || (file.untracked && diff.exitCode == 1))) { diff.output }
    val text = strictText(diff.stdout) ?: return unavailable(t("Patch contains non-UTF-8 or exceeds 1 MiB; use whole-file staging", "补丁含非 UTF-8 内容或超过 1 MiB，请整文件暂存"))
    if (metadata == null) Regex("(?m)^(?:deleted|new) file mode (\\d+)$").find(text)?.let { mode = it.groupValues[1] }
    val lines = parseDiffLines(text)
    val attrs = read("check-attr", "-z", "filter", "working-tree-encoding", "text", "eol", "--", file.path).split('\u0000').chunked(3).filter { it.size == 3 }.associate { it[1] to it[2] }
    val unsupported = when {
        file.conflicted -> t("Resolve conflicts first", "请先解决冲突")
        file.oldPath != null -> t("Use whole-file staging for renames", "重命名请使用整文件暂存")
        mode !in setOf("100644", "100755") || Files.isSymbolicLink(path) -> t("Use whole-file staging for symbolic links", "符号链接请使用整文件暂存")
        modeChange(lines) != null -> t("Use whole-file staging for permission changes", "权限变化请使用整文件暂存")
        strictText(base) == null || strictText(working) == null || text.contains("GIT binary patch") -> t("Binary, non-UTF-8 or large file: use whole-file staging", "二进制、非 UTF-8 或大文件：请整文件暂存")
        listOf("filter", "working-tree-encoding").any { attrs[it] !in setOf(null, "unspecified", "unset") } -> t("Custom conversion: use whole-file staging", "自定义内容转换：请整文件暂存")
        file.untracked && (config("core.autocrlf") in setOf("true", "input") || attrs["text"] !in setOf("unspecified", "unset", null) || attrs["eol"] !in setOf("unspecified", "unset", null)) -> t("New file with line-ending conversion: stage the file first, then partially unstage", "新文件启用了换行转换：请先整文件暂存，再部分取消暂存")
        lines.none { it.kind == DiffLineKind.HUNK } -> t("No selectable text changes; use whole-file staging", "没有可选择的文本改动，请整文件暂存")
        else -> null
    }
    val destinationExists = if (staged) !text.lineSequence().any { it.startsWith("new file mode ") } else Files.exists(path, NOFOLLOW_LINKS)
    val signature = digestParts(listOf(stamp.toByteArray(), diff.stdout, base, working, mode.toByteArray(), bytes("config", "--null", "--list"), attrs.toString().toByteArray()))
    // Freeze highlighter inputs from the same bytes used by the patch. HEAD may move during a read;
    // the full-index header identifies the actual old blob, independently of the current HEAD.
    val oldBlob = Regex("(?m)^index ([0-9a-f]{40})\\.\\.").find(text)?.groupValues?.get(1)
    val oldBytes = if (!staged) base else when {
        oldBlob == null -> null
        oldBlob.all { it == '0' } -> byteArrayOf()
        read("cat-file", "-s", oldBlob).trim().toLong() > maximum -> null
        else -> bytes("cat-file", "blob", oldBlob)
    }
    val sources = DiffSources(source(file.oldPath ?: file.path, oldBytes), source(file.path, if (staged) base else working))
    check(stamp == indexStamp()) { "Index changed while loading patch" }
    check(!Files.isRegularFile(path, NOFOLLOW_LINKS) || working.contentEquals(Files.readAllBytes(path))) { "Worktree changed while loading patch" }
    return PatchDocument(file, staged, text, lines, base, metadata != null, destinationExists, mode, stamp, signature, unsupported, sources)
}

internal fun GitCli.applySelection(document: PatchDocument, selected: Set<Int>, beforeInstall: () -> Unit = {}): GitResult =
    coordinator.execute(OperationRequest(OperationKind.INDEX, listOf(if (document.staged) "partial-unstage" else "partial-stage", document.file.path), paths = setOf(document.file.path))) {
        check(document.signature == patchDocument(document.file, document.staged).signature) { t("Diff changed. Reload and select again.", "Diff 已改变，请重新加载并选择。") }
        val patch = document.patch(selected)
        if (patch.isEmpty()) return@execute GitResult(null, "")
        val temp = Files.createTempFile(ownDirectory.toPath(), "gitviz-index-", ".tmp").toFile()
        try {
            val index = ownDirectory.resolve("index")
            val environment = mapOf("GIT_INDEX_FILE" to temp.absolutePath)
            if (index.isFile) index.copyTo(temp, overwrite = true) else { temp.delete(); raw(listOf("read-tree", "--empty"), environment = environment).requireSuccess() }
            raw(listOf("update-index", "--no-split-index"), environment = environment).requireSuccess()
            raw(listOf("apply", "--cached", "--check", "--whitespace=nowarn", "-"), patch, environment, teach = true).requireSuccess()
            raw(listOf("apply", "--cached", "--whitespace=nowarn", "-"), patch, environment, teach = true).requireSuccess()
            beforeInstall()
            RecoveryIndexLock(ownDirectory, checkNotNull(coordinator.recordId)).use { owned ->
                check(document.indexStamp == indexStamp()) { t("Index changed concurrently. Reload and select again.", "暂存区被其他进程修改，请重新选择。") }
                check(document.signature == patchDocument(document.file, document.staged).signature) { "Patch inputs changed before installation" }
                java.io.FileOutputStream(owned.lock.toFile()).use { out -> temp.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                owned.commit()
            }
            GitResult(null, t("Selected changes updated in index; working files unchanged", "已更新选中改动的暂存状态，工作区文件保持不变"))
        } finally { temp.delete(); temp.resolveSibling(temp.name + ".lock").delete() }
    }
