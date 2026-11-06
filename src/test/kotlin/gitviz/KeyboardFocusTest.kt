@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import kotlin.test.*

class KeyboardFocusTest {
    @Test fun `palette search escape returns focus to real commit list while text undo stays local`() = RepositoryFixture().use { r ->
        r.commit(); r.commit(content = "second\n"); r.commit(content = "third\n")
        GitRepo.open(r.root).use { repo ->
            val loaded = repo.loadCommits(); val state = RepoState.create(repo, loaded, loaded.commits.first().id)
            var selected by mutableStateOf<CommitInfo?>(loaded.commits.first())
            var query by mutableStateOf(""); var open by mutableStateOf(false); var undoCalls = 0
            val scene = ImageComposeScene(900, 600) {
                GitVizTheme {
                    val root = remember { FocusRequester() }; val search = remember { FocusRequester() }; val history = remember { FocusRequester() }
                    val scope = rememberCoroutineScope(); val text = remember { TextFocus() }
                    val registry = CommandRegistry(listOf(
                        AppCommand("palette", "Commands", true) { open = true },
                        AppCommand("search", "Search history", true) { scope.launch { withFrameNanos { }; search.requestFocus() } },
                        AppCommand("undo", "Undo", true) { undoCalls++ },
                    ))
                    CompositionLocalProvider(LocalTextFocus provides text) {
                        Column(Modifier.fillMaxSize().focusRequester(root).onPreviewKeyEvent { e ->
                            e.type == KeyEventType.KeyDown && (shortcutCommand(e.key, e.isMetaPressed || e.isCtrlPressed, e.isShiftPressed, text.editing)?.let(registry::execute) ?: false)
                        }.focusable()) {
                            SearchField(query, { query = it }, search) { query = ""; scope.launch { withFrameNanos { }; history.requestFocus() } }
                            CommitList(state, loaded.commits.indices.toList(), selected, rememberLazyListState(), { selected = it }, Modifier.weight(1f), focusRequester = history)
                        }
                    }
                    if (open) CommandPalette(registry) { open = false }
                    LaunchedEffect(Unit) { history.requestFocus() }
                }
            }
            try {
                var frame = 0L
                fun draw() { repeat(4) { scene.render(++frame * 16_000_000L); Thread.sleep(10) } }
                fun key(k: Key, primary: Boolean = false, shift: Boolean = false) {
                    scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown, isMetaPressed = primary, isCtrlPressed = primary, isShiftPressed = shift))
                    scene.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp, isMetaPressed = primary, isCtrlPressed = primary, isShiftPressed = shift)); draw()
                }
                draw(); key(Key.J); assertEquals(loaded.commits[1].id, selected!!.id)
                key(Key.P, primary = true, shift = true); assertTrue(open)
                key(Key.Enter); assertFalse(open)
                scene.sendKeyEvent(KeyEvent(Key.J, KeyEventType.Unknown, codePoint = 'j'.code, nativeEvent = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, 'j'))); draw()
                assertEquals("j", query)
                key(Key.Z, primary = true); assertEquals(0, undoCalls)
                key(Key.Escape); assertEquals("", query)
                key(Key.J); assertEquals(loaded.commits[2].id, selected!!.id)
                key(Key.Z, primary = true); assertEquals(1, undoCalls)
            } finally { scene.close() }
        }
    }
}
