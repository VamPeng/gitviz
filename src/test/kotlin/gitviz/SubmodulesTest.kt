package gitviz

import java.nio.file.Files
import kotlin.test.*

class SubmodulesTest {
    @Test fun `uninitialized spaced path updates only recorded commit and protects dirty stale and ignored state`() = RepositoryFixture().use { parent ->
        RepositoryFixture().use { remote ->
            val first = remote.commit(); parent.commit()
            parent.git("-c", "protocol.file.allow=always", "submodule", "add", remote.root.path, "deps/space name")
            parent.git("commit", "-am", "module")
            parent.git("config", "protocol.file.allow", "always")
            val current = parent.cli.submodules().single()
            assertEquals(first, current.head); assertEquals(first, current.target)
            parent.git("submodule", "deinit", "-f", "--", current.path)
            val uninitialized = parent.cli.submodules().single(); assertNull(uninitialized.head); assertTrue(uninitialized.ready)
            remote.commit("b", "upstream moved\n")
            val result = parent.cli.updateSubmodule(uninitialized)
            assertTrue(result.ok, result.output)
            val initialized = parent.cli.submodules().single(); assertEquals(first, initialized.head)
            assertFalse(parent.cli.updateSubmodule(uninitialized).ok)
            val directory = parent.root.resolve(current.path)
            directory.resolve("a").writeText("local\n")
            assertTrue(parent.cli.submodules().single().dirty)
            assertFalse(parent.cli.updateSubmodule(initialized).ok)
            GitProcess.execute(directory, listOf("restore", "a"), isolated = true).requireSuccess()
            val child = parent.cli.atPath(directory)
            child.ownDirectory.resolve("info/exclude").apply { parentFile.mkdirs(); appendText("\nignored\n") }
            directory.resolve("ignored").writeText("local ignored")
            assertFalse(parent.cli.updateSubmodule(parent.cli.submodules().single()).ok)
            assertTrue(directory.resolve("ignored").exists())
            directory.resolve("ignored").delete()
            val updated = parent.cli.updateSubmodule(parent.cli.submodules().single())
            assertTrue(updated.ok, updated.output)
            assertTrue(child.read("for-each-ref", "refs/gitviz/submodule-before").isNotBlank())
            assertTrue(parent.cli.status().isEmpty())
        }
    }

    @Test fun `unsafe gitmodule paths and symbolic links cannot be followed`() = RepositoryFixture().use { r ->
        r.commit(); r.write(".gitmodules", "[submodule \"bad\"]\npath = ../outside\nurl = /none\n")
        val bad = r.cli.submodules().single(); assertNotNull(bad.problem); assertFalse(r.cli.updateSubmodule(bad).ok)
        r.root.resolve(".gitmodules").delete()
        Files.createSymbolicLink(r.root.resolve(".gitmodules").toPath(), r.root.resolve("a").toPath())
        assertFails { r.cli.submodules() }
        Unit
    }
}
