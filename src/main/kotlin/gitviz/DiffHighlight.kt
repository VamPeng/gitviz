package gitviz

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal const val HIGHLIGHT_LIMIT = 1_048_576
internal data class SourceCode(val path: String, val text: String)
internal data class DiffSources(val old: SourceCode?, val new: SourceCode?)
internal data class SourceDiff(val lines: List<DiffLine>, val sources: DiffSources)
internal enum class SyntaxKind { KEYWORD, STRING, COMMENT, NUMBER }
internal data class SyntaxToken(val start: Int, val end: Int, val kind: SyntaxKind)
internal data class SyntaxLine(val text: String, val tokens: List<SyntaxToken>)
internal data class DiffSyntax(val old: List<SyntaxLine>?, val new: List<SyntaxLine>?) {
    fun line(line: DiffLine, oldSide: Boolean): SyntaxLine? {
        val number = (if (oldSide) line.oldNo else line.newNo) ?: return null
        return (if (oldSide) old else new)?.getOrNull(number - 1)
    }
}
internal fun source(path: String, bytes: ByteArray?): SourceCode? = bytes?.let(::strictText)?.let { SourceCode(path, it) }
internal fun GitRepo.diffSources(file: FileChange): DiffSources {
    fun blob(id: ObjectId?, path: String): SourceCode? = runCatching {
        if (id == null || id == ObjectId.zeroId()) source(path, byteArrayOf()) else {
            val loader = repository.open(id, Constants.OBJ_BLOB)
            if (loader.size > HIGHLIGHT_LIMIT) null else source(path, loader.getBytes(HIGHLIGHT_LIMIT))
        }
    }.getOrNull()
    return DiffSources(blob(file.entry.oldId.toObjectId(), file.oldPath), blob(file.entry.newId.toObjectId(), file.newPath))
}
internal fun GitCli.sourceAt(revision: String, path: String): SourceCode? = runCatching {
    val id = raw(listOf("rev-parse", "--verify", "$revision:$path"))
    if (id.exitCode != 0) source(path, byteArrayOf()) else {
        val oid = id.text.trim()
        if (read("cat-file", "-s", oid).trim().toLong() > HIGHLIGHT_LIMIT) null else source(path, bytes("cat-file", "blob", oid))
    }
}.getOrNull()
internal fun GitCli.workingSources(file: WorkingFile, staged: Boolean): DiffSources {
    val index = sourceAt("", file.path)
    val old = if (staged) sourceAt("HEAD", file.oldPath ?: file.path) else index
    val new = if (staged) index else runCatching {
        val path = safeWorkingPath(file.path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) source(file.path, byteArrayOf())
        else if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > HIGHLIGHT_LIMIT) null
        else source(file.path, Files.readAllBytes(path))
    }.getOrNull()
    return DiffSources(old, new)
}
internal fun GitCli.stashSources(entry: StashEntry, file: StashFile) = DiffSources(
    if (file.untracked) source(file.path, byteArrayOf()) else sourceAt("${entry.id}^1", file.path),
    sourceAt(if (file.untracked) "${entry.id}^3" else entry.id, file.path),
)

/** Color-independent bounded LRU. Lex entire sources off the UI thread, then map original line numbers. */
internal class SyntaxCache(private val budget: Long = 32L * 1024 * 1024) {
    private data class Entry(val lines: List<SyntaxLine>, val bytes: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    var usedBytes: Long = 0; private set
    fun highlight(source: SourceCode?): List<SyntaxLine>? {
        source ?: return null
        val language = when (source.path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"; "java" -> "java"; "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"; "json" -> "json"; "py" -> "python"; else -> return null
        }
        if (source.text.length > HIGHLIGHT_LIMIT || source.text.toByteArray().size > HIGHLIGHT_LIMIT || source.text.count { it == '\n' } + (if (source.text.endsWith('\n')) 0 else 1) > 20_000) return null
        val key = language + MessageDigest.getInstance("SHA-256").digest(source.text.toByteArray()).joinToString("") { "%02x".format(it) }
        synchronized(this) { entries[key]?.let { return it.lines } }
        val lines = lex(source.text, language)
        val weight = source.text.length * 2L + lines.size * 96L + lines.sumOf { it.tokens.size * 64L } + 256
        synchronized(this) {
            entries[key]?.let { return it.lines }
            if (weight <= budget) {
                while (usedBytes + weight > budget && entries.isNotEmpty()) { val first = entries.entries.iterator(); usedBytes -= first.next().value.bytes; first.remove() }
                entries[key] = Entry(lines, weight); usedBytes += weight
            }
        }
        return lines
    }
    fun highlight(sources: DiffSources) = DiffSyntax(highlight(sources.old), highlight(sources.new))
}
internal val diffSyntaxCache = SyntaxCache()

private val commonKeywords = "abstract as assert async await break case catch class const continue default do else enum export extends false final finally for from function if implements import in instanceof interface is let native new null of package private protected public return static super switch synchronized this throw throws true try typeof var void volatile while with yield".split(' ').toSet()
private val kotlinKeywords = "actual annotation by companion constructor crossinline data delegate dynamic expect external field file fun get infix init inline inner internal lateinit noinline object open operator out override param property receiver reified sealed set setparam suspend tailrec typealias val vararg when where".split(' ').toSet()
private val pythonKeywords = "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield match case".split(' ').toSet()
private val typeKeywords = "boolean byte char double float int long short String number string type keyof readonly unknown never any declare namespace boolean satisfies".split(' ').toSet()

/** Finite lexical state, including multiline strings/comments. No regex backtracking on file content. */
private fun lex(text: String, language: String): List<SyntaxLine> {
    val python = language == "python"; val json = language == "json"
    val keywords = if (python) pythonKeywords else if (json) setOf("true", "false", "null") else commonKeywords + typeKeywords + if (language == "kotlin") kotlinKeywords else emptySet()
    var blockDepth = 0
    var quote: String? = null
    var escaped = false
    return text.split('\n').map { raw ->
        val line = raw.removeSuffix("\r")
        val tokens = mutableListOf<SyntaxToken>()
        var i = 0
        while (i < line.length) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val start = i
            when {
                blockDepth > 0 -> {
                    while (i < line.length && blockDepth > 0) {
                        when {
                            language == "kotlin" && line.startsWith("/*", i) -> { blockDepth++; i += 2 }
                            line.startsWith("*/", i) -> { blockDepth--; i += 2 }
                            else -> i++
                        }
                    }
                    tokens += SyntaxToken(start, i, SyntaxKind.COMMENT)
                }
                quote != null -> {
                    val delimiter = quote!!
                    while (i < line.length) {
                        if (escaped) { escaped = false; i++; continue }
                        if (line[i] == '\\' && !(language == "kotlin" && delimiter.length == 3)) { escaped = true; i++; continue }
                        if (line.startsWith(delimiter, i)) { i += delimiter.length; quote = null; break }
                        i++
                    }
                    tokens += SyntaxToken(start, i, SyntaxKind.STRING)
                }
                (!json && !python && line.startsWith("//", i)) || (python && line[i] == '#') -> { i = line.length; tokens += SyntaxToken(start, i, SyntaxKind.COMMENT) }
                !json && !python && line.startsWith("/*", i) -> { blockDepth = 1; i += 2; tokens += SyntaxToken(start, i, SyntaxKind.COMMENT) }
                language in setOf("javascript", "typescript") && line[i] == '/' -> {
                    // Consume regex character classes and escapes before they can masquerade as comments.
                    // Division with two slashes on a line is ambiguous without a parser: keep this span plain.
                    var previous = i - 1
                    while (previous >= 0 && line[previous].isWhitespace()) previous--
                    var wordStart = previous
                    while (wordStart >= 0 && line[wordStart].isLetter()) wordStart--
                    val regexPosition = previous < 0 || line[previous] in "=(:,![{;?&|+-*%^~<>" ||
                        (previous - wordStart <= 6 && line.substring(wordStart + 1, previous + 1) in setOf("return", "throw", "case", "yield", "await"))
                    var cursor = i + 1; var inClass = false; var slashEscaped = false; var end = -1
                    while (cursor < line.length) {
                        val c = line[cursor]
                        when {
                            slashEscaped -> slashEscaped = false
                            c == '\\' -> slashEscaped = true
                            c == '[' -> inClass = true
                            c == ']' -> inClass = false
                            c == '/' && !inClass -> { end = cursor + 1; break }
                        }
                        cursor++
                    }
                    if (end > 0) {
                        i = end; while (i < line.length && line[i].isLetter()) i++
                        if (regexPosition) tokens += SyntaxToken(start, i, SyntaxKind.STRING)
                    } else i = line.length
                }
                line[i] == '"' || (!json && line[i] == '\'') || (language in setOf("javascript", "typescript") && line[i] == '`') -> {
                    val triple = (python || language in setOf("kotlin", "java")) && i + 2 < line.length && line[i + 1] == line[i] && line[i + 2] == line[i]
                    quote = line[i].toString().repeat(if (triple) 3 else 1); escaped = false; i += quote!!.length
                    tokens += SyntaxToken(start, i, SyntaxKind.STRING)
                }
                line[i].isLetter() || line[i] == '_' || line[i] == '$' -> {
                    i++; while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) i++
                    if (line.substring(start, i) in keywords) tokens += SyntaxToken(start, i, SyntaxKind.KEYWORD)
                }
                line[i].isDigit() -> {
                    i++; while (i < line.length && (line[i].isLetterOrDigit() || line[i] in "._" || (line[i] in "+-" && line[i - 1] in "eE"))) i++
                    tokens += SyntaxToken(start, i, SyntaxKind.NUMBER)
                }
                else -> i++
            }
        }
        // Only template/triple strings and explicit backslash continuations span lines.
        if (quote?.length == 1 && quote != "`" && !escaped) quote = null
        escaped = false
        SyntaxLine(line, tokens)
    }
}
