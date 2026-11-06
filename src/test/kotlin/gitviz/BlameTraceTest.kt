package gitviz

import kotlin.test.*

class BlameTraceTest {
    @Test fun `renamed file preserves attribution path and added line stops at introduction`() = RepositoryFixture().use { r ->
        val first = r.commit("old", "one\ntwo\nthree\nfour\n")
        r.git("mv", "old", "new"); r.write("new", "one\nTWO\nthree\nfour\nadded\n"); r.git("add", "new"); r.git("commit", "-qm", "rename edit")
        val tip = r.git("rev-parse", "HEAD")
        GitRepo.open(r.root).use { repo ->
            val lines = repo.blame(tip, "new")
            assertEquals("old", lines.first().sourcePath)
            assertEquals(1, lines.first().sourceLine)
            val changed = repo.tracePrevious(lines[1])
            assertEquals(TraceLocation(first, "old", 2, 2), changed.location)
            assertNull(repo.tracePrevious(lines.last()).location)
        }
    }
    @Test fun `replacement maps a parent interval without inventing a source line`() = RepositoryFixture().use { r ->
        val first = r.commit("a", "start\nold one\nold two\nend\n"); val tip = r.commit("a", "start\nreplacement\nend\n")
        GitRepo.open(r.root).use { repo ->
            val line = repo.blame(tip, "a")[1]
            assertEquals(TraceLocation(first, "a", 2, 3), repo.tracePrevious(line).location)
        }
    }
    @Test fun `deletion follows selected merge parent even when blobs are identical`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "side"); val a = r.commit("a", "same\n")
        r.git("switch", "side"); r.commit("a", "different\n"); val b = r.commit("a", "same\n")
        val tree = r.cli.raw(listOf("mktree"), byteArrayOf()).requireSuccess().decodeToString().trim()
        val merge = r.cli.raw(listOf("commit-tree", tree, "-p", a, "-p", b), "delete merge\n".toByteArray()).requireSuccess().decodeToString().trim()
        GitRepo.open(r.root).use { repo ->
            val commit = repo.commit(merge); val file = repo.changes(merge, 1).single()
            val selected = repo.beforeDeletion(commit, file, 1)
            assertEquals(b, selected.commit)
            assertEquals(b, repo.blame(selected.commit, selected.path).single().commitId)
        }
    }
    @Test fun `merge parent choice uses real parent trees and deletion opens previous version`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "side"); val main = r.commit("a", "main\n"); r.git("switch", "side"); val side = r.commit("a", "side\n")
        r.cli.raw(listOf("merge", "main")); r.write("a", "resolved\n"); r.git("add", "a"); r.git("commit", "-qm", "merge")
        val merged = r.git("rev-parse", "HEAD")
        GitRepo.open(r.root).use { repo ->
            val line = repo.blame(merged, "a").single()
            assertEquals(merged, line.commitId)
            assertEquals(side, repo.tracePrevious(line, 0).location?.commit)
            assertEquals(main, repo.tracePrevious(line, 1).location?.commit)
        }
        r.git("rm", "a"); r.git("commit", "-qm", "delete")
        GitRepo.open(r.root).use { repo ->
            val commit = repo.commit(r.git("rev-parse", "HEAD")); val file = repo.changes(commit.id).single()
            assertEquals(TraceLocation(merged, "a"), repo.beforeDeletion(commit, file))
        }
    }
}
