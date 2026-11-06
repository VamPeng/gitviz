@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.test.*

class BranchDragTest {
    @Test fun `real sidebar mouse drag drops on another sidebar branch`() = pointerDrag(sourceInGraph = false, targetInGraph = false)

    @Test fun `real graph chip mouse drag drops on sidebar branch`() = pointerDrag(sourceInGraph = true, targetInGraph = false)

    @Test fun `real sidebar mouse drag drops on graph chip`() = pointerDrag(sourceInGraph = false, targetInGraph = true)

    @Test fun `coalesced mouse movement still drops on sidebar branch`() = pointerDrag(sourceInGraph = false, targetInGraph = false, moves = 1)

    private fun pointerDrag(sourceInGraph: Boolean, targetInGraph: Boolean, moves: Int = 12) = RepositoryFixture().use { r ->
        r.commit(); r.git("switch", "-c", "feature/conflict"); r.commit("feature", "feature\n"); r.git("switch", "main")
        GitRepo.open(r.root).use { repo ->
            val loaded = repo.loadCommits()
            val state = RepoState.create(repo, loaded, r.git("rev-parse", "HEAD"))
            val source = loaded.refs.first { it.label.name == "feature/conflict" }.label
            val target = loaded.refs.first { it.label.name == "main" }.label
            val drops = mutableListOf<Pair<RefLabel, RefLabel>>()
            var selections = 0
            val drag = BranchDragState(true) { a, b -> drops += a to b }
            val scene = ImageComposeScene(1100, 500) {
                GitVizTheme {
                    CompositionLocalProvider(LocalBranchDrag provides drag) {
                        Row(Modifier.fillMaxSize()) {
                            RefSidebar(loaded.refs, "", null, { selections++ }, Modifier.width(240.dp).fillMaxHeight())
                            CommitList(state, loaded.commits.indices.toList(), null, rememberLazyListState(), { selections++ }, Modifier.weight(1f))
                        }
                    }
                }
            }
            try {
                var frame = 0L
                var millis = 0L
                fun draw() { repeat(3) { scene.render(++frame * 16_000_000L); Thread.sleep(5) } }
                fun position(ref: RefLabel, graph: Boolean): Offset = drag.targets.values.first {
                    it.first == ref && (it.second.left >= 240f) == graph
                }.second.center
                fun pointer(type: PointerEventType, point: Offset, pressed: Boolean) {
                    scene.sendPointerEvent(type, point, timeMillis = ++millis * 20,
                        type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = pressed),
                        button = if (type == PointerEventType.Press || type == PointerEventType.Release) PointerButton.Primary else null)
                    draw()
                }
                draw()
                val from = position(source, sourceInGraph); val to = position(target, targetInGraph)
                pointer(PointerEventType.Move, from, false)
                pointer(PointerEventType.Press, from, true)
                repeat(moves) { step -> pointer(PointerEventType.Move, from + (to - from) * ((step + 1).toFloat() / moves), true) }
                assertEquals(source, drag.source, "Mouse movement must start the actual branch modifier gesture")
                assertEquals(target, drag.over, "The registered destination bounds must match the pointer")
                pointer(PointerEventType.Release, to, false)
                assertEquals(listOf(source to target), drops)
                assertEquals(0, selections, "A completed drag must not also activate the row click")
                assertNull(drag.source)
            } finally { scene.close() }
        }
    }

    @Test fun `drop direction determines which branch is switched and modified`() {
        val a = RefLabel("a", RefKind.BRANCH); val b = RefLabel("b", RefKind.BRANCH)
        assertEquals(OperationIntent(PreviewKind.MERGE, "refs/heads/a", switchBranch = "b"), branchDropIntent(a, b, PreviewKind.MERGE))
        assertEquals(OperationIntent(PreviewKind.REBASE, "refs/heads/b", switchBranch = "a"), branchDropIntent(a, b, PreviewKind.REBASE))
    }
    @Test fun `covered branches cannot receive a drop`() {
        val a = RefLabel("a", RefKind.BRANCH); val b = RefLabel("b", RefKind.BRANCH)
        var dropped = false
        val state = BranchDragState(true) { _, _ -> dropped = true }
        state.targets["target"] = b to androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 100f)
        state.blockers["detail"] = androidx.compose.ui.geometry.Rect(0f, 50f, 100f, 100f)
        state.source = a; state.move(androidx.compose.ui.geometry.Offset(20f, 80f)); state.drop()
        assertFalse(dropped)
    }
    @Test fun `ordinary abort preserves files added during conflict`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "side"); r.commit("a", "main\n"); r.git("switch", "side"); r.commit("a", "side\n")
        assertEquals(GitFailure.CONFLICT, r.cli.merge("main").failure)
        r.write("notes.txt", "keep\n")
        assertTrue(r.cli.abortOperation().ok)
        assertEquals("keep\n", r.root.resolve("notes.txt").readText())
        assertFalse(r.cli.undo().ok)
        assertTrue("notes.txt" in r.cli.recovery.preview(r.cli.recovery.latest()!!.id).files)
    }
    @Test fun `composite merge undo restores original branch`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "b"); r.git("checkout", "-b", "a"); r.commit("new", "new\n"); r.git("switch", "main")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "a", switchBranch = "b"))
        val done = r.cli.executePlan(plan)
        assertTrue(done.ok, done.output)
        assertEquals("b", r.git("branch", "--show-current"))
        assertTrue(r.cli.undo(done.recoveryId).ok)
        assertEquals("main", r.git("branch", "--show-current"))
        assertEquals(r.git("rev-parse", "main"), r.git("rev-parse", "b"))
    }
    @Test fun `composite rebase abort restores original branch`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "a"); r.git("checkout", "-b", "b"); r.commit("a", "b\n"); r.git("switch", "a"); r.commit("a", "a\n"); r.git("switch", "main")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.REBASE, "b", switchBranch = "a"))
        assertEquals(GitFailure.CONFLICT, r.cli.executePlan(plan).failure)
        r.write("notes.txt", "keep\n")
        assertTrue(r.cli.abortOperation().ok)
        assertEquals("keep\n", r.root.resolve("notes.txt").readText())
        assertFalse(r.cli.undo().ok)
        assertTrue("notes.txt" in r.cli.recovery.preview(r.cli.recovery.latest()!!.id).files)
        assertEquals("main", r.git("branch", "--show-current"))
        assertEquals("first\n", r.root.resolve("a").readText())
    }
}
