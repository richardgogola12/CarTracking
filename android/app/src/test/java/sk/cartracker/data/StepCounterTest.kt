package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import sk.cartracker.data.local.TYPE_WALK
import sk.cartracker.data.local.TripEntity
import java.util.Calendar

class StepCounterTest {
    private fun ts(d: Int, h: Int, min: Int = 0) = Calendar.getInstance().apply { clear(); set(2026, 9, d, h, min) }.timeInMillis

    @Test fun splitAcrossHours() {
        // 7:45–8:15, 600 krokov → 300 + 300, súčet sedí presne
        val r = StepCounter.split(600, ts(20, 7, 45), ts(20, 8, 15))
        assertEquals(listOf(ts(20, 7) to 300, ts(20, 8) to 300), r)
        val odd = StepCounter.split(1001, ts(20, 7, 50), ts(20, 9, 10))
        assertEquals(1001, odd.sumOf { it.second })
        assertEquals(3, odd.size)
    }

    @Test fun splitAtMidnightGoesToBothDays() {
        val r = StepCounter.split(100, ts(20, 23, 50), ts(21, 0, 10))
        assertEquals(50, StepCounter.byDay(r.map { sk.cartracker.data.local.StepHourEntity(1, it.first, it.second) })[StepCounter.dayStart(ts(20, 12))])
    }

    @Test fun dailyCounterBeatsRecordedWalks() {
        // zaznamenaná chôdza 3000 krokov / 2,5 km, krokomer za deň 7000 → 7000 krokov a +4000 × 0,75 m
        val w = TripEntity(id = 1, uuid = "w", userId = 1, startedAt = ts(20, 9), endedAt = ts(20, 9, 30), type = TYPE_WALK, steps = 3000, distanceM = 2500.0, finished = true)
        val hourly = mapOf(ts(20, 9) to 3500, ts(20, 14) to 3500)
        val d = WalkAnalytics.days(listOf(w), ts(20, 0), ts(21, 0), 6000, hourly, 0.75) { a, _ -> if (Calendar.getInstance().apply { timeInMillis = a }.get(Calendar.HOUR_OF_DAY) in 7..15) 1.0 else 0.0 }
        assertEquals(7000, d.single().steps)
        assertEquals(5500.0, d.single().meters, 1e-6)
        assertEquals(7000, d.single().workSteps)
        assertEquals(true, d.single().goalMet)
        // bez krokomera platia chôdze
        assertEquals(3000, WalkAnalytics.days(listOf(w), ts(20, 0), ts(21, 0), 6000) { _, _ -> 0.0 }.single().steps)
        // hodiny dňa z krokomera
        val h = WalkAnalytics.byHour(listOf(w), hourly)
        assertEquals(3500.0, h[14], 1e-6)
    }

    @Test fun mergeTakesLarger() {
        val m = StepCounter.merge(mapOf(1L to 5000.0, 2L to 100.0), mapOf(2L to 4000, 3L to 10))
        assertEquals(mapOf(1L to 5000.0, 2L to 4000.0, 3L to 10.0), m)
    }
}
