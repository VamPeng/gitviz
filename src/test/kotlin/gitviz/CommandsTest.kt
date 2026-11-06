package gitviz

import androidx.compose.ui.input.key.Key
import kotlin.test.*

class CommandsTest {
    @Test fun `shelved features have no regular command while core undo remains available`() {
        var calls = 0
        val hidden = listOf("ai", "replay", "forge", "reflog", "recovery")
        val registry = CommandRegistry((hidden + "undo").map { AppCommand(it, it, true) { calls++ } })
        hidden.forEach { assertFalse(registry.execute(it)) }
        assertEquals(listOf("undo"), registry.commands.map { it.id })
        assertEquals(1, registry.menu().size)
        assertEquals(0, calls)
        assertTrue(registry.execute("undo")); assertEquals(1, calls)
    }

    @Test fun `editing retains undo and escape never aborts Git`() {
        assertNull(shortcutCommand(Key.Z, true, false, true))
        assertEquals("undo", shortcutCommand(Key.Z, true, false, false))
        assertEquals("palette", shortcutCommand(Key.P, true, true, true))
        assertEquals("commit", shortcutCommand(Key.Enter, true, false, true))
        assertNull(shortcutCommand(Key.Escape, false, false, false))
        assertNull(shortcutCommand(Key.J, false, false, false))
    }
    @Test fun `menu and palette dispatch same command availability`() {
        var calls = 0
        val enabled = CommandRegistry(listOf(AppCommand("run", "Run", true) { calls++ }))
        enabled.execute("run"); (enabled.menu().single() as MenuItem).action()
        assertEquals(2, calls)
        val disabled = CommandRegistry(listOf(AppCommand("run", "Run", false) { calls++ }))
        disabled.execute("run"); (disabled.menu().single() as MenuItem).action()
        assertEquals(2, calls)
    }
}
