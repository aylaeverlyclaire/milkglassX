package com.milkglass.app

import com.milkglass.app.core.time.MilkTime
import com.milkglass.app.core.time.jsToFixed
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class MilkTimeTest {
    private val tr = TestSupport.translator
    private val wib = TimeZone.getTimeZone("Asia/Jakarta")
    private fun time(now: Long = 0L) = MilkTime(tr, { now }, wib)

    @Test fun toFixedMatchesJs() {
        assertEquals("1.00", jsToFixed(1.005, 2))
        assertEquals("3", jsToFixed(2.5, 0))
        assertEquals("0.1", jsToFixed(0.15, 1))
        assertEquals("48.2", jsToFixed(48.21, 1))
        assertEquals("30.0", jsToFixed(29.96, 1))
        assertEquals("9.1", jsToFixed(9.05, 1))
    }

    @Test fun clockAndWeekday() {
        val t = time()
        assertEquals("07:00", t.formatClock(0L))       
        assertEquals(4, t.weekdayOf(0L))               
        assertEquals(1, t.dayOfMonthOf(0L))
        assertEquals(-7 * 3_600_000L, t.startOfToday())
    }

    @Test fun formats() {
        val t = time()
        assertEquals("1:02:05", t.formatDuration(3725.0))
        assertEquals("0:09", t.formatDuration(9.9))
        assertEquals("24:00", t.formatDuration(1440.0))
        assertEquals("9,1", t.formatRating(9.1))
        assertEquals("48,2 rb", t.formatCompactCount(48210))
        assertEquals("950", t.formatCompactCount(950))
        assertEquals("Sudah tayang", t.formatCountdown(0))
        assertEquals("1 menit", t.formatCountdown(1))
        assertEquals("1 jam 30 menit", t.formatCountdown(90 * 60_000L))
        assertEquals("2 hari 3 jam", t.formatCountdown((51 * 60) * 60_000L))
        assertEquals("Rp1.500.000", t.formatRupiah(1_500_000))
    }

    @Test fun timeAgo() {
        val now = 10L * 86_400_000L
        val t = time(now)
        assertEquals("Baru saja", t.formatTimeAgo(now - 30_000))
        assertEquals("5 menit lalu", t.formatTimeAgo(now - 5 * 60_000L))
        assertEquals("3 jam lalu", t.formatTimeAgo(now - 3 * 3_600_000L))
        assertEquals("Kemarin", t.formatTimeAgo(now - 30 * 3_600_000L))
        assertEquals("4 hari lalu", t.formatTimeAgo(now - 4 * 86_400_000L))
    }
}
