package gitviz

import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowSizeTest {
    private fun withStore(test: (Preferences, WindowSizeStore) -> Unit) {
        val prefs = Preferences.userRoot().node("gitviz-tests/window-size-${UUID.randomUUID()}")
        try {
            test(prefs, WindowSizeStore(prefs))
        } finally {
            prefs.removeNode()
            Preferences.userRoot().flush()
        }
    }

    @Test
    fun `last resize survives reopening preferences`() = withStore { prefs, store ->
        assertEquals(WindowSize.Default, store.read())
        store.save(WindowSize(1000, 700))
        store.save(WindowSize(1200, 800))
        val reopened = Preferences.userRoot().node(prefs.absolutePath())
        reopened.sync()
        assertEquals(WindowSize(1200, 800), WindowSizeStore(reopened).read())
        store.save(WindowSize(0, 0))
        assertEquals(WindowSize(1200, 800), store.read())
    }

    @Test
    fun `incomplete or corrupt dimensions use default`() = withStore { prefs, store ->
        for (value in listOf("", "1200", "1200x", "-1x800", "0x800", "NaNx800", "999999999999x800", "1200x800x1")) {
            prefs.put("windowSize", value)
            assertEquals(WindowSize.Default, store.read(), value)
        }
    }

    @Test
    fun `resize waits one second since the latest change and saves only the final size`() {
        val saved = LinkedBlockingQueue<WindowSize>()
        WindowSizeSaver { saved.add(it) }.use { saver ->
            saver.resize(WindowSize(1000, 700))
            assertNull(saved.poll(600, TimeUnit.MILLISECONDS))
            val lastChange = System.nanoTime()
            saver.resize(WindowSize(1200, 800))
            assertEquals(WindowSize(1200, 800), saved.poll(3, TimeUnit.SECONDS))
            assertTrue(System.nanoTime() - lastChange >= TimeUnit.MILLISECONDS.toNanos(1000))
            assertNull(saved.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `closing cancels pending save without saving immediately`() {
        val saved = LinkedBlockingQueue<WindowSize>()
        val saver = WindowSizeSaver { saved.add(it) }
        saver.resize(WindowSize(1000, 700))
        saver.close()
        saver.resize(WindowSize(1200, 800))
        assertNull(saved.poll(1200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `save exception does not prevent a later resize from saving`() {
        val attempted = LinkedBlockingQueue<WindowSize>()
        var fail = true
        WindowSizeSaver {
            attempted.add(it)
            if (fail) { fail = false; throw IllegalStateException("unavailable") }
        }.use { saver ->
            saver.resize(WindowSize(1000, 700))
            assertEquals(WindowSize(1000, 700), attempted.poll(3, TimeUnit.SECONDS))
            saver.resize(WindowSize(1200, 800))
            assertEquals(WindowSize(1200, 800), attempted.poll(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `unavailable preferences fall back on read and ignore write or flush failures`() {
        val prefs = object : AbstractPreferences(null, "") {
            var failPut = true
            override fun getSpi(key: String): String? = throw SecurityException("denied")
            override fun putSpi(key: String, value: String) {
                if (failPut) throw SecurityException("denied")
            }
            override fun flushSpi() { throw BackingStoreException("unavailable") }
            override fun syncSpi() = Unit
            override fun removeSpi(key: String) = Unit
            override fun removeNodeSpi() = Unit
            override fun keysSpi() = emptyArray<String>()
            override fun childrenNamesSpi() = emptyArray<String>()
            override fun childSpi(name: String): AbstractPreferences = error("unused")
        }
        val store = WindowSizeStore(prefs)
        assertEquals(WindowSize.Default, store.read())
        store.save(WindowSize(1200, 800))
        prefs.failPut = false
        store.save(WindowSize(1200, 800))
    }

    @Test
    fun `restore fits smaller display without enlarging saved window`() {
        assertEquals(WindowSize(1024, 720), WindowSize(1600, 1000).fitWithin(1024, 720))
        assertEquals(WindowSize(900, 600), WindowSize(900, 600).fitWithin(1920, 1080))
    }
}
