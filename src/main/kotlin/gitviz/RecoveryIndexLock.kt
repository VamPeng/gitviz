package gitviz

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A hard link proves ownership even across a crash; never remove somebody else's index.lock. */
internal class RecoveryIndexLock(private val directory: File, private val id: String, private val fileName: String = "index") : AutoCloseable {
    val lock = directory.resolve("$fileName.lock").toPath()
    private val prefix = "gitviz-restore-${if (fileName == "index") "" else "$fileName-"}$id"
    private val owned = directory.resolve("$prefix.lock").toPath()
    private val owner = directory.resolve("$prefix.owner")
    init {
        Files.createFile(owned)
        try {
            owner.outputStream().use { out ->
                out.write("${ProcessHandle.current().pid()}\n${ProcessHandle.current().info().startInstant().orElse(null)}".toByteArray())
                out.fd.sync()
            }
            Files.createLink(lock, owned)
        } catch (e: Exception) { Files.deleteIfExists(owned); owner.delete(); throw e }
    }
    fun commit() { Files.move(lock, directory.resolve(fileName).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    override fun close() {
        if (Files.exists(lock) && Files.exists(owned) && Files.isSameFile(lock, owned)) Files.delete(lock)
        Files.deleteIfExists(owned); owner.delete()
    }
    companion object {
        fun recover(directory: File, records: List<OperationRecord>) {
            records.filter { it.state in setOf(RecordState.RESTORING, RecordState.RUNNING, RecordState.PREPARED, RecordState.NEEDS_RECOVERY) }.forEach { record ->
                listOf("index", "config").forEach fileLoop@ { fileName ->
                val prefix = "gitviz-restore-${if (fileName == "index") "" else "$fileName-"}${record.id}"
                val owned = directory.resolve("$prefix.lock").toPath()
                val owner = directory.resolve("$prefix.owner")
                val lock = directory.resolve("$fileName.lock").toPath()
                if (!owner.isFile || !Files.exists(owned)) return@fileLoop
                val lines = owner.readLines()
                val pid = lines.firstOrNull()?.toLongOrNull() ?: return@fileLoop
                val process = ProcessHandle.of(pid).orElse(null)
                check(process == null || !process.isAlive || process.info().startInstant().orElse(null).toString() != lines.getOrNull(1)) { "The previous recovery process is still running" }
                if (Files.exists(lock) && Files.isSameFile(lock, owned)) Files.delete(lock)
                Files.deleteIfExists(owned); owner.delete()
                }
            }
        }
    }
}
