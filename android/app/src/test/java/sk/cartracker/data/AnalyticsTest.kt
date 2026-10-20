package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import java.util.Calendar

class AnalyticsTest {
    private fun ts(y: Int, m: Int, d: Int, h: Int = 8, min: Int = 0) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis
    private var id = 0L
    private fun car(start: Long, km: Double, minutes: Int = 20, biz: Boolean = false, score: Int? = 80, dest: String? = "Práca") =
        TripEntity(id = ++id, uuid = "t$id", userId = 1, startedAt = start, endedAt = start + minutes * 60_000L, distanceM = km * 1000,
            category = if (biz) CATEGORY_BUSINESS else "private", score = score, endPlace = dest, finished = true)

    @Test fun isoWeeks() {
        assertEquals(43, Analytics.isoWeek(ts(2026, 10, 20)).second)
        assertEquals(1, Analytics.isoWeek(ts(2025, 12, 29)).second)
        assertEquals(2026, Analytics.isoWeek(ts(2025, 12, 29)).third)
        assertEquals(53, Analytics.isoWeek(ts(2026, 12, 31)).second)
        assertEquals(ts(2026, 10, 19, 0), Analytics.isoWeek(ts(2026, 10, 25, 23)).first)   // nedeľa → pondelok toho týždňa
    }

    @Test fun lengthBinsShare() {
        val b = Analytics.lengthBins(listOf(car(ts(2026, 10, 1), 1.0), car(ts(2026, 10, 2), 1.5), car(ts(2026, 10, 3), 12.5)))
        assertEquals(2, b[0].count); assertEquals(1, b[3].count)
        assertEquals(2.5 / 15 * 100, b[0].kmShare, 1e-9)
    }

    @Test fun heatAndPeak() {
        val h = Analytics.heat(listOf(car(ts(2026, 10, 20, 7), 13.0), car(ts(2026, 10, 20, 7, 30), 2.0), car(ts(2026, 10, 24, 15), 5.0)))
        assertEquals(15.0, h.km[1][7], 1e-9)          // utorok 7:00
        assertEquals(2, h.count[1][7])
        assertEquals(1 to 7, h.peak())
    }

    @Test fun monthsTwelveAndTotals() {
        val trips = listOf(car(ts(2026, 10, 5), 10.0, biz = true), car(ts(2026, 10, 6), 5.0), car(ts(2025, 11, 2), 7.0), car(ts(2025, 10, 31), 99.0))
        val m = Analytics.months(trips, emptyList(), ts(2026, 10, 20), { it.distanceM / 1000 * 0.1 }, { 0.0 })
        assertEquals(12, m.size)
        assertEquals(ts(2025, 11, 1, 0), m.first().start)
        assertEquals(7.0, m.first().km, 1e-9)                // október 2025 do prehľadu nepatrí
        assertEquals(15.0, m.last().km, 1e-9); assertEquals(10.0, m.last().bizKm, 1e-9)
        assertEquals(1.5, m.last().cost, 1e-9); assertEquals(2, m.last().days)
    }

    @Test fun destinationsAndScore() {
        val d = Analytics.destinations(listOf(car(ts(2026, 10, 5), 10.0, score = 90), car(ts(2026, 10, 6), 20.0, biz = true, score = 60),
            car(ts(2026, 10, 7), 3.0, dest = "Domov"), car(ts(2026, 10, 8), 3.0, dest = null)), { 1.0 })
        assertEquals(listOf("Práca", "Domov"), d.map { it.name })
        assertEquals(2, d[0].visits); assertEquals(15.0, d[0].avgKm, 1e-9); assertEquals(50.0, d[0].bizPct, 1e-9)
        assertEquals(70, Analytics.scoreAvg(listOf(car(0, 10.0, score = 90), car(0, 20.0, score = 60))))
        assertNull(Analytics.scoreAvg(listOf(car(0, 10.0, score = null))))
    }

    @Test fun weekdaysFirstDeparture() {
        val w = Analytics.weekdays(listOf(car(ts(2026, 10, 19, 6, 0), 13.0), car(ts(2026, 10, 19, 15, 0), 13.0), car(ts(2026, 10, 26, 7, 0), 13.0)),
            emptyList(), ts(2026, 10, 1, 0), ts(2026, 10, 29, 0))
        assertEquals(3, w[0].trips); assertEquals(390, w[0].firstDeparture)   // Ø zo 6:00 a 7:00
        assertEquals(39.0 / 4, w[0].perDayKm, 1e-9)
    }
}
