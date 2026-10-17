package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import java.util.Calendar

class TripClassifierTest {
    private fun trip(h: Int, dow: Int = Calendar.TUESDAY, rev: Boolean = false): TripEntity {
        val c = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 1, h, 0, 0); while (get(Calendar.DAY_OF_WEEK) != dow) add(Calendar.DAY_OF_MONTH, 1) }
        val a = 47.9862 to 18.1617; val b = 47.8919 to 18.0476
        val (s, e) = if (rev) b to a else a to b
        return TripEntity(uuid = "u$h$dow$rev", userId = 1, startedAt = c.timeInMillis, startLat = s.first, startLng = s.second, endLat = e.first, endLng = e.second)
    }

    @Test fun learnsAfterTwoDecisionsBothDirections() {
        var s = AppSettings()
        assertNull(TripClassifier.learned(trip(8), s))
        s = TripClassifier.learn(s, trip(8), CATEGORY_BUSINESS)
        assertNull(TripClassifier.learned(trip(8), s))              // jedno rozhodnutie nestačí
        s = TripClassifier.learn(s, trip(17, rev = true), CATEGORY_BUSINESS)
        assertEquals(CATEGORY_BUSINESS, TripClassifier.learned(trip(9), s)?.category)
        assertEquals(CATEGORY_BUSINESS, TripClassifier.learned(trip(9, rev = true), s)?.category)
    }

    @Test fun workTimeRule() {
        val s = AppSettings(ruleWorkTime = true, ruleWeekendPrivate = true, defaultCategory = "private")
        assertEquals(CATEGORY_BUSINESS, TripClassifier.decide(trip(9), emptyList(), { _, _ -> null }, s).category)
        assertEquals("private", TripClassifier.decide(trip(19), emptyList(), { _, _ -> null }, s).category)
        assertEquals("private", TripClassifier.decide(trip(9, Calendar.SATURDAY), emptyList(), { _, _ -> null }, s).category)
    }
}
