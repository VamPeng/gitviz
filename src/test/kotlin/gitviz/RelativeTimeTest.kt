package gitviz

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {
    init { Settings.language = Language.EN }


    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 4, 9, 0).toEpochSecond(zone)

    private fun ago(seconds: Long) = relativeTime(now - seconds, now, zone)

    @Test
    fun `within a day counts seconds`() {
        assertEquals("just now", ago(59))
        assertEquals("just now", ago(-300)) // 时钟不准，提交在未来
        assertEquals("1m ago", ago(60))
        assertEquals("59m ago", ago(3599))
        assertEquals("1h ago", ago(3600))
        assertEquals("8h ago", ago(8 * 3600 + 59 * 60)) // 当天 00:01
    }

    @Test
    fun `older than a day counts calendar days`() {
        assertEquals("yesterday", ago(86400)) // 昨天 09:00
        assertEquals("2d ago", ago(86400 + 10 * 3600)) // 前天 23:00
        assertEquals("29d ago", ago(29 * 86400L))
        assertEquals("2026-10-05", ago(30 * 86400L))
    }

    @Test
    fun `chinese labels`() {
        Settings.language = Language.ZH
        try {
            assertEquals("刚刚", ago(59))
            assertEquals("2 天前", ago(86400 + 10 * 3600))
        } finally {
            Settings.language = Language.EN
        }
    }
}
