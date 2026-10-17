package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity

class SpeedingFixTest {
    private fun p(i: Int, lng: Double, speed: Float, ev: String? = null) =
        PointEntity(id = i.toLong(), tripId = 1, ts = i * 1000L, lat = 48.0, lng = lng, speedKmh = speed, accuracyM = 5f, bearing = null, altitudeM = null, event = ev)

    @Test fun removesOnlyEventsInsideFixedZone() {
        // jazda na východ; oprava 70 km/h pri 18.000 (platí do 200 m, smer 90°)
        val pts = listOf(p(0, 17.998, 60f), p(1, 17.999, 62f), p(2, 18.000, 68f, DriveEvent.SPEEDING),
            p(3, 18.001, 66f), p(4, 18.010, 80f, DriveEvent.SPEEDING), p(5, 18.011, 80f), p(6, 18.012, 80f))
        val custom = listOf(SpeedLimits.Custom(48.0, 18.0, 70, 90))
        assertEquals(listOf(2), SpeedingFix.falseEvents(pts, custom, 5))
        // opačný smer → nič
        assertEquals(emptyList<Int>(), SpeedingFix.falseEvents(pts, listOf(SpeedLimits.Custom(48.0, 18.0, 70, 270)), 5))
        // rýchlosť nad limit + toleranciu ostáva prekročením
        assertEquals(emptyList<Int>(), SpeedingFix.falseEvents(pts, listOf(SpeedLimits.Custom(48.0, 18.0, 60, 90)), 5))
    }

    @Test fun bearingFromNeighbours() {
        val pts = listOf(p(0, 18.0, 50f), p(1, 18.001, 50f), p(2, 18.002, 50f))
        assertEquals(90.0, SpeedingFix.bearingAt(pts, 1)!!.toDouble(), 0.5)
    }

    @Test fun speedingSecProportional() {
        assertEquals(60, SpeedingFix.reducedSpeedingSec(120, 4, 2))
        assertEquals(0, SpeedingFix.reducedSpeedingSec(120, 2, 2))
        assertEquals(120, SpeedingFix.reducedSpeedingSec(120, 2, 0))
    }
}
