package gitviz

import java.io.File
import java.nio.file.Files

internal class RepositoryFixture : AutoCloseable {
    val root: File = Files.createTempDirectory("gitviz-fixture-").toFile()
    val cli = GitCli(root, isolated = true)
    init {
        git("init", "-q", "--initial-branch=main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
        git("config", "core.attributesFile", "/dev/null")
    }
    fun git(vararg args: String): String = GitProcess.execute(root, args.toList(), isolated = true).requireSuccess().decodeToString().trimEnd()
    fun write(path: String, text: String) { root.resolve(path).also { it.parentFile.mkdirs(); it.writeText(text) } }
    fun commit(path: String = "a", content: String = "first\n"): String { write(path, content); git("add", "--", path); git("commit", "-q", "-m", content.trim()); return git("rev-parse", "HEAD") }
    override fun close() { deleteTemporaryTree(root.toPath()) }
}
