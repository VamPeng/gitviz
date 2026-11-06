package gitviz

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal fun strictText(bytes: ByteArray): String? {
    if (bytes.size > 1_048_576 || 0.toByte() in bytes) return null
    return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
}

internal fun GitCli.safeWorkingPath(name: String): Path {
    val root = workTree.canonicalFile.toPath()
    val relative = Path.of(name)
    require(!relative.isAbsolute && relative.none { it.toString() == ".." || it.toString().equals(".git", true) }) { "Invalid worktree path" }
    val path = root.resolve(relative).normalize()
    require(path != root && path.startsWith(root))
    var current = root
    for (part in root.relativize(path.parent)) {
        current = current.resolve(part)
        check(!Files.isSymbolicLink(current)) { "Parent path is a symbolic link: $name" }
    }
    return path
}

internal data class ConflictSources(val path: String, val base: String?, val ours: String?, val theirs: String?, val working: String?,
    val signature: String, val textSupported: Boolean, val stages: String)

internal fun GitCli.conflictSources(path: String): ConflictSources {
    val entries = read("ls-files", "--unmerged", "-z", "--", ":(literal)$path")
    val digest = MessageDigest.getInstance("SHA-256")
    fun frame(bytes: ByteArray) { digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()); digest.update(bytes) }
    frame(entries.toByteArray())
    var textSupported = true
    val versions = entries.split('\u0000').filter { it.isNotEmpty() }.associate { entry ->
        val metadata = entry.substringBefore('\t').split(' ')
        val size = read("cat-file", "-s", metadata[1]).trim().toLong()
        val text = if (size <= 1_048_576 && metadata[0] in setOf("100644", "100755")) strictText(bytes("cat-file", "blob", metadata[1])) else null
        if (text == null) textSupported = false
        metadata[2].toInt() to text
    }
    val file = safeWorkingPath(path)
    val working = when {
        Files.isSymbolicLink(file) -> { frame("link".toByteArray()); textSupported = false; frame(Files.readSymbolicLink(file).toString().toByteArray()); null }
        !Files.exists(file, LinkOption.NOFOLLOW_LINKS) -> { frame("missing".toByteArray()); null }
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> {
            frame("file".toByteArray())
            frame(runCatching { Files.getPosixFilePermissions(file).sortedBy { it.name }.joinToString() }.getOrDefault("").toByteArray())
            val size = Files.size(file)
            frame(size.toString().toByteArray())
            val content = java.io.ByteArrayOutputStream()
            var withinLimit = size <= 1_048_576
            Files.newInputStream(file).use { input ->
                val b = ByteArray(65536)
                while (true) {
                    val n = input.read(b); if (n < 0) break
                    digest.update(b, 0, n)
                    if (content.size() + n > 1_048_576) withinLimit = false
                    if (withinLimit) content.write(b, 0, n)
                }
            }
            val text = if (withinLimit) strictText(content.toByteArray()) else null
            if (text == null) textSupported = false
            text
        }
        else -> { frame("special".toByteArray()); textSupported = false; null }
    }
    return ConflictSources(path, versions[1], versions[2], versions[3], working,
        digest.digest().joinToString("") { "%02x".format(it) }, textSupported, entries)
}

internal fun editedLineEndings(text: String, original: String?): String =
    if (original != null && "\r\n" in original && '\n' !in original.replace("\r\n", "")) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
