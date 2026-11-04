package gitviz

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {

    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 4, 9, 0).toEpochSecond(zone)

    private fun ago(seconds: Long) = relativeTime(now - seconds, now, zone)

    @Test
    fun `within a day counts seconds`() {
        assertEquals("刚刚", ago(59))
        assertEquals("刚刚", ago(-300)) // 时钟不准，提交在未来
        assertEquals("1 分钟前", ago(60))
        assertEquals("59 分钟前", ago(3599))
        assertEquals("1 小时前", ago(3600))
        assertEquals("8 小时前", ago(8 * 3600 + 59 * 60)) // 当天 00:01
    }

    @Test
    fun `older than a day counts calendar days`() {
        assertEquals("昨天", ago(86400)) // 昨天 09:00
        assertEquals("2 天前", ago(86400 + 10 * 3600)) // 前天 23:00
        assertEquals("29 天前", ago(29 * 86400L))
        assertEquals("2026-10-05", ago(30 * 86400L))
    }
}
