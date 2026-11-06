package gitviz

import kotlin.test.*

class PullTest {
    private fun withRemote(block: (RepositoryFixture, RepositoryFixture) -> Unit) = RepositoryFixture().use { remote -> RepositoryFixture().use { local ->
        remote.commit(); local.git("config", "protocol.file.allow", "always"); local.git("remote", "add", "origin", remote.root.path); local.git("fetch", "origin"); local.git("reset", "--hard", "origin/main")
        block(remote, local)
    } }
    @Test fun `fetch changes only tracking refs and pull integration is frozen preview`() = withRemote { remote, local ->
        val before = local.git("rev-parse", "HEAD"); val tip = remote.commit("new", "new\n")
        val prepared = local.cli.preparePull(PullSettings(PullStrategy.MERGE, "origin", "main", true))
        assertTrue(prepared.result.ok, prepared.result.output)
        assertEquals(before, local.git("rev-parse", "HEAD"))
        assertEquals(tip, prepared.intent!!.target)
        assertEquals("origin/main", local.git("rev-parse", "--abbrev-ref", "@{upstream}"))
        assertEquals(PullStrategy.MERGE, local.cli.pullOptions().strategy)
        val plan = local.cli.planOperation(prepared.intent)
        assertTrue(local.cli.executePlan(plan).ok)
        assertEquals(tip, local.git("rev-parse", "HEAD"))
    }
    @Test fun `ff only rejects divergence without fallback and choice persists`() = withRemote { remote, local ->
        remote.commit("remote", "remote\n"); val head = local.commit("local", "local\n")
        val prepared = local.cli.preparePull(PullSettings(PullStrategy.FF_ONLY, "origin", "main", true))
        assertTrue(prepared.result.ok, prepared.result.output)
        val plan = local.cli.planOperation(prepared.intent!!)
        assertFalse(plan.outcome.ok)
        assertFalse(local.cli.executePlan(plan).ok)
        assertEquals(head, local.git("rev-parse", "HEAD"))
        assertEquals(PullStrategy.FF_ONLY, local.cli.pullOptions().strategy)
    }
    @Test fun `explicit selected branch works outside configured fetch scope and refuses deleted remote branch`() = withRemote { remote, local ->
        remote.git("branch", "other"); local.git("config", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/main")
        val prepared = local.cli.preparePull(PullSettings(PullStrategy.MERGE, "origin", "other", true))
        assertTrue(prepared.result.ok, prepared.result.output)
        assertEquals("origin/other", local.git("rev-parse", "--abbrev-ref", "@{upstream}"))
        remote.git("branch", "-D", "other")
        val deleted = local.cli.preparePull(PullSettings(PullStrategy.MERGE, "origin", "other", false))
        assertFalse(deleted.result.ok)
        assertNull(deleted.intent)
    }
    @Test fun `custom tracking mappings and source identity are honored`() = withRemote { remote, local ->
        local.git("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/custom/*")
        val options = local.cli.pullOptions()
        val settings = PullSettings(PullStrategy.MERGE, "origin", "main", true, options.head, options.current)
        local.git("checkout", "-b", "other")
        assertFalse(local.cli.preparePull(settings).result.ok)
        local.git("switch", "main"); remote.commit("new", "new\n")
        val ready = local.cli.preparePull(settings)
        assertTrue(ready.result.ok, ready.result.output)
        assertEquals(remote.git("rev-parse", "HEAD"), local.git("rev-parse", "refs/remotes/custom/main"))
        assertTrue("main" in local.cli.pullOptions().branches["origin"].orEmpty())
        local.git("switch", "other")
        assertFails { local.cli.planOperation(ready.intent!!) }
    }
    @Test fun `rebase pull preserves local commits on fetched upstream`() = withRemote { remote, local ->
        val upstream = remote.commit("remote", "remote\n"); local.commit("local", "local\n")
        val prepared = local.cli.preparePull(PullSettings(PullStrategy.REBASE, "origin", "main", true))
        assertTrue(prepared.result.ok, prepared.result.output)
        val result = local.cli.executePlan(local.cli.planOperation(prepared.intent!!))
        assertTrue(result.ok, result.output)
        assertEquals(upstream, local.git("rev-parse", "HEAD^"))
        assertTrue(local.root.resolve("local").exists())
    }
}
