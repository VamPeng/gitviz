package gitviz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal data class AppCommand(val id: String, val title: String, val enabled: Boolean, val hint: String = "", val action: () -> Unit)
/** Temporarily shelved product entry points; implementations remain available internally. */
internal object FeatureVisibility {
    private val hidden = setOf("ai", "replay", "forge", "reflog", "recovery")
    fun visible(id: String): Boolean = id !in hidden
}
internal class CommandRegistry(commands: List<AppCommand>) {
    val commands = commands.filter { FeatureVisibility.visible(it.id) }
    fun execute(id: String): Boolean { val c = commands.firstOrNull { it.id == id } ?: return false; if (c.enabled) c.action(); return true }
    fun menu(): List<MenuEntry> = commands.map { c -> MenuItem(c.title, c.enabled, detail = c.hint.ifBlank { null }) { execute(c.id) } }
}
internal class CommitDraft { var message by mutableStateOf(""); var amend by mutableStateOf(false) }
internal class TextFocus { val active = mutableStateMapOf<Any, Boolean>(); val editing get() = active.values.any { it } }
internal val LocalTextFocus = staticCompositionLocalOf<TextFocus?> { null }
internal val LocalCommands = staticCompositionLocalOf<CommandRegistry?> { null }
@Composable internal fun Modifier.textInputFocus(): Modifier {
    val tracker = LocalTextFocus.current ?: return this
    val id = remember { Any() }
    DisposableEffect(tracker, id) { onDispose { tracker.active.remove(id) } }
    return onFocusChanged { tracker.active[id] = it.isFocused }
}
internal fun shortcutCommand(key: Key, primary: Boolean, shift: Boolean, editing: Boolean): String? {
    if (!primary) return null
    return when {
        key == Key.P && shift -> "palette"
        key == Key.F && !shift -> "search"
        key == Key.R && !shift -> "refresh"
        key == Key.Enter && !shift -> "commit"
        key == Key.Z && !shift && !editing -> "undo"
        else -> null
    }
}

@Composable internal fun CommandPalette(registry: CommandRegistry, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var index by remember(query) { mutableStateOf(0) }
    val shown = registry.commands.filter { it.id != "palette" && (it.title.contains(query, true) || it.id.contains(query, true)) }
    val focus = remember { FocusRequester() }
    fun choose(c: AppCommand) { if (c.enabled) { onDismiss(); registry.execute(c.id) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Commands", "命令面板")) }, text = {
        Column(Modifier.width(560.dp)) {
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).textInputFocus().onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                        Key.DirectionDown -> { index = (index + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); true }
                        Key.Enter -> { shown.getOrNull(index)?.let(::choose); true }
                        else -> false
                    }
                }.padding(10.dp))
            LazyColumn(Modifier.heightIn(max = 390.dp)) {
                items(shown) { c -> Row(Modifier.fillMaxWidth().rowHighlight(shown.getOrNull(index) == c).clickable(enabled = c.enabled) { choose(c) }.padding(10.dp)) {
                    Text(c.title, color = if (c.enabled) Palette.Ink else Palette.Faint, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
                    Text(c.hint, color = Palette.Muted, fontSize = TypeScale.Small)
                } }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(t("Close", "关闭")) } })
    LaunchedEffect(Unit) { focus.requestFocus() }
}
