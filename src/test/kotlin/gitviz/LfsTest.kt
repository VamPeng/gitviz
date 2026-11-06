package gitviz

import kotlin.test.*

class LfsTest {
    private val oid = "a".repeat(64)
    private fun pointer(size: String = "12345") = "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize $size\n"
    @Test fun `valid pointers require canonical encoding and bounded size`() {
        assertEquals(LfsPointer(oid, 12345), parseLfsPointer(pointer().toByteArray()))
        assertNotNull(parseLfsPointer(pointer().replace("oid ", "ext-0-test abc\noid ").toByteArray()))
        listOf(pointer("-1"), pointer("01"), pointer("999999999999999999999"), pointer().replace("a", "A"), pointer().replace("\n", "\r\n"), pointer().trimEnd(), pointer() + "size 1\n", "x".repeat(1024)).forEach { assertNull(parseLfsPointer(it.toByteArray()), it) }
    }

    @Test fun `inspection works without tool and never reads large object content`() = RepositoryFixture().use { r ->
        r.commit("asset.bin", pointer()); r.commit(".gitattributes", "*.bin filter=lfs diff=lfs merge=lfs -text\n")
        r.root.resolve("asset.bin").outputStream().use { stream -> repeat(32) { stream.write(ByteArray(1024 * 1024)) } }
        val client = LfsClient(r.cli) { _, _ -> GitExecution(1, byteArrayOf(), "not installed".toByteArray()) }
        val state = client.inspect()
        assertNull(state.version); assertEquals(1, state.files.size); assertEquals(1, state.attributes.size)
        assertEquals(false, state.files.single().cached)
        assertFalse(client.pull(state, "origin").ok)
    }

    @Test fun `explicit pull preserves errors and rejects dirty or expired snapshots`() = RepositoryFixture().use { r ->
        r.commit("asset.bin", pointer()); r.git("remote", "add", "origin", "https://example.invalid/repo.git")
        val calls = mutableListOf<List<String>>()
        val client = LfsClient(r.cli) { args, write ->
            calls += args
            if (write) GitExecution(1, byteArrayOf(), "Authentication failed".toByteArray())
            else GitExecution(0, (if (args.last() == "version") "git-lfs/3.test" else "Endpoint=https://user:secret@example.invalid/lfs\n").toByteArray(), byteArrayOf())
        }
        val state = client.inspect(); assertFalse(state.endpoints.joinToString().contains("secret"))
        val failed = client.pull(state, "origin"); assertEquals(GitFailure.AUTH, failed.failure)
        assertEquals(listOf("-c", "lfs.fetchrecentalways=false", "lfs", "pull", "--include=", "--exclude=", "origin"), calls.last())
        r.write("untracked", "preserve"); assertFalse(client.pull(state, "origin").ok)
        r.git("config", "lfs.url", "https://different.invalid/lfs")
        assertFalse(client.pull(state, "origin").ok)
    }
}
