package gitviz

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Binary-safe transport. stdout is never mixed with diagnostics or truncated. */
internal data class GitExecution(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray, val uncertain: Boolean = false) {
    val text: String get() = stdout.toString(Charsets.UTF_8)
    val output: String get() = listOf(text.trimEnd(), stderr.toString(Charsets.UTF_8).trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
    fun result() = GitResult(when { uncertain -> GitFailure.UNKNOWN; exitCode == 0 -> null; else -> classifyFailure(output) }, output)
    fun requireSuccess(): ByteArray {
        check(exitCode == 0 && !uncertain) { output.ifBlank { "git exited with $exitCode" } }
        return stdout
    }
}

internal object GitProcess {
    fun execute(directory: File, args: List<String>, input: ByteArray? = null, environment: Map<String, String> = emptyMap(),
                timeoutSeconds: Long = 120, isolated: Boolean = false, preparedCheck: (() -> Unit)? = null): GitExecution {
        // Git diff may refresh stat entries even with GIT_OPTIONAL_LOCKS=0 (Apple Git 2.50).
        // Inspection must not silently change the index bytes recorded by recovery.
        val process = ProcessBuilder(listOf("git", "-c", "diff.autoRefreshIndex=false") + args).directory(directory).apply {
            // Never let the launching shell redirect a command to another repository/index.
            environment().keys.removeAll(setOf("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES"))
            if (isolated) {
                environment().keys.removeAll { it.startsWith("GIT_") }
                environment()["GIT_ATTR_NOSYSTEM"] = "1"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                environment()["GIT_NO_LAZY_FETCH"] = "1"
            }
            // Background inspection must not rewrite index stat data and expire a recovery point.
            environment()["GIT_OPTIONAL_LOCKS"] = "0"
            environment()["GIT_TERMINAL_PROMPT"] = "0"
            environment()["LC_ALL"] = "C"
            environment().putAll(environment)
        }.start()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>()
        fun worker(body: () -> Unit) = Thread { try { body() } catch (e: Throwable) { failure.compareAndSet(null, e) } }
            .apply { isDaemon = true; start() }
        val replies = java.util.concurrent.LinkedBlockingQueue<String>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val decision = Any()
        fun stopSafely() {
            if (preparedCheck != null && process.isAlive) {
                cancelled.set(true)
                synchronized(decision) {
                    runCatching { process.outputStream.write("abort\n".toByteArray()); process.outputStream.flush() }
                    runCatching { process.outputStream.close() }
                }
                // Native Git releases prepared ref locks on abort/EOF. Kill only if it cannot exit.
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }
            if (process.isAlive) stop(process)
        }
        val reader = worker {
            if (preparedCheck == null) process.inputStream.use { it.copyTo(out) }
            else try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> out.write((line + "\n").toByteArray()); replies.put(line) } } }
            finally { replies.offer("EOF") }
        }
        val errors = worker { process.errorStream.use { it.copyTo(err) } }
        val writer = worker { process.outputStream.use { stream ->
            if (input != null) { stream.write(input); stream.flush() }
            if (preparedCheck != null) {
                while (true) {
                    val reply = replies.poll(timeoutSeconds, TimeUnit.SECONDS) ?: error("Git reference prepare timed out")
                    if (reply == "EOF") error("Git reference transaction ended before prepare")
                    if (reply == "prepare: ok") break
                }
                try {
                    preparedCheck()
                    synchronized(decision) { if (!cancelled.get()) { stream.write("commit\n".toByteArray()); stream.flush() } }
                } catch (e: Throwable) {
                    synchronized(decision) { if (!cancelled.getAndSet(true)) { stream.write("abort\n".toByteArray()); stream.flush() } }
                    throw e
                }
            }
        } }
        var uncertain = false
        var interrupted = false
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { uncertain = true; stopSafely() }
            listOf(reader, errors, writer).forEach { it.join(2000) }
        } catch (_: InterruptedException) {
            interrupted = true
            uncertain = true
            stopSafely()
        } finally {
            if (process.isAlive || reader.isAlive || errors.isAlive || writer.isAlive) {
                uncertain = true
                stopSafely()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                listOf(reader, errors, writer).forEach { runCatching { it.join(1000) } }
            }
        }
        val exit = if (process.isAlive) -1 else process.exitValue()
        if (failure.get() != null && exit == 0) uncertain = true
        val diagnostics = err.toByteArray() + failure.get()?.message?.let { "\n$it".toByteArray() }.orEmptyBytes() + if (uncertain) "\nGit execution stopped or could not be confirmed. Inspect the repository before retrying.".toByteArray() else byteArrayOf()
        if (interrupted) Thread.currentThread().interrupt()
        return GitExecution(exit, out.toByteArray(), diagnostics, uncertain)
    }

    private val unfinished = java.util.concurrent.CopyOnWriteArrayList<ProcessHandle>()
    fun hasUnfinishedProcess(): Boolean {
        unfinished.removeIf { !it.isAlive }
        return unfinished.isNotEmpty()
    }

    private fun stop(process: Process) {
        val children = process.toHandle().descendants().use { it.toList() }
        val handles = children.asReversed() + process.toHandle()
        handles.forEach { runCatching { it.destroyForcibly() } }
        handles.forEach { handle ->
            runCatching { handle.onExit().get(2, TimeUnit.SECONDS) }
            if (handle.isAlive) unfinished.addIfAbsent(handle)
        }
    }
}

private fun ByteArray?.orEmptyBytes(): ByteArray = this ?: byteArrayOf()
