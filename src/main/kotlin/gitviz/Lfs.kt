package gitviz

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal data class LfsPointer(val oid: String, val size: Long)

internal fun parseLfsPointer(bytes: ByteArray): LfsPointer? {
    if (bytes.size !in 1..1023) return null
    val text = strictText(bytes) ?: return null
    if (!text.endsWith('\n') || '\r' in text) return null
    val lines = text.dropLast(1).split('\n')
    if (lines.firstOrNull() !in setOf("version https://git-lfs.github.com/spec/v1", "version https://hawser.github.com/spec/v1")) return null
    val pairs = lines.map { it.substringBefore(' ') to it.substringAfter(' ', "") }
    if (pairs.any { !it.first.matches(Regex("[a-z0-9.-]+")) || it.second.isEmpty() || it.second.startsWith(' ') } || pairs.map { it.first }.distinct().size != pairs.size) return null
    if (pairs.drop(1).map { it.first } != pairs.drop(1).map { it.first }.sorted()) return null
    val fields = pairs.toMap()
    val oid = fields["oid"]?.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) }?.removePrefix("sha256:") ?: return null
    val size = fields["size"]?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull() ?: return null
    return LfsPointer(oid, size)
}

internal data class LfsFile(val path: String, val pointer: LfsPointer, val cached: Boolean?, val working: String)
internal data class LfsSnapshot(val version: String?, val diagnostic: String, val files: List<LfsFile>,
                                val attributes: List<Pair<String, String>>, val endpoints: List<String>, val remotes: List<String>, val stamp: String)
internal typealias LfsRunner = (List<String>, Boolean) -> GitExecution

internal class LfsClient(private val cli: GitCli, private val run: LfsRunner = { args, write ->
    cli.raw(args, environment = if (write) Askpass.environment() else emptyMap(), timeout = if (write) 600 else 120, teach = write)
}) {
    private fun stamp(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(cli.indexStamp(), cli.raw(listOf("rev-parse", "HEAD")).text, cli.read("config", "--null", "--list")).forEach { digest.update(it.toByteArray()); digest.update(0) }
        val local = cli.safeWorkingPath(".lfsconfig")
        if (Files.exists(local, NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(local, NOFOLLOW_LINKS) && Files.size(local) <= 1_048_576) { "Invalid .lfsconfig" }
            digest.update(Files.readAllBytes(local))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun inspect(): LfsSnapshot {
        val before = stamp()
        val version = run(listOf("lfs", "version"), false)
        val available = version.exitCode == 0 && !version.uncertain
        val environment = if (available) run(listOf("lfs", "env"), false).requireSuccess().decodeToString() else ""
        val media = environment.lineSequence().firstOrNull { it.startsWith("LocalMediaDir=") }?.substringAfter('=')?.let(::File)
            ?: if (cli.config("lfs.storage") == null) cli.commonDirectory.resolve("lfs/objects") else null
        val endpoints = environment.lines().filter { it.startsWith("Endpoint") }.map { formatGitCommand(listOf(it)).removePrefix("git ") }
        val files = mutableListOf<LfsFile>(); val attributes = mutableListOf<Pair<String, String>>()
        GitRepo.open(cli.workTree).use { repo ->
            val index = repo.repository.readDirCache()
            repo.repository.newObjectReader().use { reader ->
                for (i in 0 until index.entryCount) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    val entry = index.getEntry(i)
                    if (entry.stage != 0 || entry.rawMode !in setOf(33188, 33261)) continue
                    val loader = reader.open(entry.objectId)
                    if (entry.pathString.substringAfterLast('/') == ".gitattributes") {
                        check(attributes.size < 100 && attributes.sumOf { it.second.length } < 1_048_576) { t("Too many attribute files to display", "属性文件过多，无法完整显示") }
                        attributes += entry.pathString to if (loader.size <= 65536) loader.getBytes(65536).decodeToString() else t("Attribute file exceeds 64 KiB", "属性文件超过 64 KiB")
                    }
                    if (loader.size >= 1024) continue
                    val pointer = parseLfsPointer(loader.getBytes(1024)) ?: continue
                    check(files.size < 100_000) { t("More than 100,000 LFS pointers; narrow the checkout", "LFS 指针超过 100,000 个，请缩小检出范围") }
                    val cache = media?.resolve("${pointer.oid.take(2)}/${pointer.oid.substring(2, 4)}/${pointer.oid}")
                    val cached = cache?.let { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.length() == pointer.size }
                    val state = try {
                        val path = cli.safeWorkingPath(entry.pathString)
                        when {
                            !Files.exists(path, NOFOLLOW_LINKS) -> t("Missing working file", "工作区文件缺失")
                            !Files.isRegularFile(path, NOFOLLOW_LINKS) -> t("Not a regular file", "不是普通文件")
                            Files.size(path) < 1024 && parseLfsPointer(Files.readAllBytes(path)) != null -> t("Pointer only", "仅有指针")
                            else -> t("Working content present (not hash-verified)", "工作区已有内容（未核对哈希）")
                        }
                    } catch (e: Exception) { e.message.orEmpty() }
                    files += LfsFile(entry.pathString, pointer, cached, state)
                }
            }
        }
        check(before == stamp()) { t("Repository changed while inspecting LFS. Refresh.", "检查 LFS 期间仓库已变化，请刷新。") }
        return LfsSnapshot(if (available) version.text.trim() else null, if (available) "" else version.output, files,
            attributes, endpoints, cli.read("remote").lines().filter { it.isNotBlank() }, before)
    }

    fun pull(reviewed: LfsSnapshot, remote: String): GitResult = cli.coordinator.execute(OperationRequest(OperationKind.OTHER)) {
        check(reviewed.version != null && run(listOf("lfs", "version"), false).exitCode == 0) { t("Install Git LFS before downloading objects", "请先安装 Git LFS，再下载对象") }
        require(remote.isNotBlank() && !remote.startsWith('-') && remote in cli.read("remote").lines()) { "Choose an existing remote" }
        check(reviewed.stamp == stamp()) { t("LFS state changed; refresh and review again", "LFS 状态已变化，请刷新后重新检查") }
        check(cli.currentOperation() == null && cli.status().isEmpty()) { t("Commit or stash changes before downloading LFS content", "请先提交或储藏改动，再下载 LFS 内容") }
        // Native LFS refuses to overwrite modified content. Download is explicit and limited to the current ref.
        run(listOf("-c", "lfs.fetchrecentalways=false", "lfs", "pull", "--include=", "--exclude=", remote), true).result()
    }
}
