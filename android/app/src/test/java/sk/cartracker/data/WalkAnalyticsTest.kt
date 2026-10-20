package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.cartracker.data.local.TYPE_WALK
import sk.cartracker.data.local.TripEntity
import java.util.Calendar

class WalkAnalyticsTest {
    private fun ts(y: Int, m: Int, d: Int, h: Int = 8, min: Int = 0) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis
    private var id = 0L
    private fun walk(start: Long, minutes: Int, steps: Int, meters: Double = steps * 0.75) =
        TripEntity(id = ++id, uuid = "w$id", userId = 1, startedAt = start, endedAt = start + minutes * 60_000L, type = TYPE_WALK, steps = steps, distanceM = meters, finished = true)

    @Test fun paceFormat() {
        assertEquals(12.0, WalkAnalytics.pace(30 * 60_000L, 2500.0)!!, 1e-9)
        assertEquals("12:00", WalkAnalytics.paceText(12.0))
        assertEquals("11:30", WalkAnalytics.paceText(11.5))
        assertNull(WalkAnalytics.pace(60_000L, 100.0))
    }

    @Test fun hoursSplitProportionally() {
        // 7:30–8:30, 6000 krokov → 3000 v 7. a 3000 v 8. hodine
        val h = WalkAnalytics.byHour(listOf(walk(ts(2026, 10, 20, 7, 30), 60, 6000)))
        assertEquals(3000.0, h[7], 1e-6); assertEquals(3000.0, h[8], 1e-6)
    }

    @Test fun workShareAcrossStart() {
        // utorok 6:30–7:30, práca od 7:00 → polovica
        val a = ts(2026, 10, 20, 6, 30); val b = ts(2026, 10, 20, 7, 30)
        assertEquals(0.5, WalkAnalytics.workShare(a, b, setOf(1, 2, 3, 4, 5), 7 * 60, 16 * 60), 1e-9)
        assertEquals(0.0, WalkAnalytics.workShare(ts(2026, 10, 24, 9), ts(2026, 10, 24, 10), setOf(1, 2, 3, 4, 5), 7 * 60, 16 * 60), 1e-9)  // sobota
    }

    @Test fun daysWeeksMonths() {
        val w = listOf(walk(ts(2026, 10, 19, 9), 60, 9000), walk(ts(2026, 10, 19, 17), 20, 1000), walk(ts(2026, 10, 21, 12), 30, 4000))
        val days = WalkAnalytics.days(w, ts(2026, 10, 19, 0), ts(2026, 10, 26, 0), 8000) { _, _ -> 0.5 }
        assertEquals(7, days.size)
        assertEquals(10_000, days[0].steps); assertTrue(days[0].goalMet); assertEquals(5000, days[0].workSteps)
        assertEquals(0, days[1].steps)
        val weeks = WalkAnalytics.weeks(days)
        assertEquals(1, weeks.size); assertEquals("t. 43 / 2026", weeks[0].label); assertEquals(14_000, weeks[0].steps); assertEquals(1, weeks[0].goalDays)
        assertEquals(2000.0, weeks[0].avgSteps, 1e-9)
        val wd = WalkAnalytics.weekdays(days)
        assertEquals(10_000.0, wd[0].avgSteps, 1e-9); assertEquals(100.0, wd[0].goalPct, 1e-9)
        assertEquals(1, WalkAnalytics.months(days).size)
        val s = WalkAnalytics.summary(w, days, 1, 3)
        assertEquals(14_000, s.steps); assertEquals(2, s.activeDays); assertEquals(9000, s.longest!!.steps)
    }

    @Test fun bins() {
        val b = WalkAnalytics.stepBins(listOf(walk(0, 5, 300), walk(0, 5, 700), walk(0, 50, 6000)))
        assertEquals(1, b[0].count); assertEquals(1, b[1].count); assertEquals(1, b[4].count)
        assertEquals(6000 * 100.0 / 7000, b[4].stepShare, 1e-9)
    }
}
