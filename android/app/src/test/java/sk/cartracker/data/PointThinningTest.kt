package sk.cartracker.data

import org.junit.Assert.assertTrue
import org.junit.Test
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity
import sk.cartracker.sync.PointThinning

class PointThinningTest {
    private fun p(i: Int, lat: Double, lng: Double, ev: String? = null) =
        PointEntity(id = i.toLong(), tripId = 1, ts = i * 1000L, lat = lat, lng = lng, speedKmh = 50f, accuracyM = 5f, bearing = null, altitudeM = null, synced = true, event = ev)

    @Test fun straightRoadKeepsAbout60m() {
        // 2 km rovno na východ, bod každých ~14 m (1 s pri 50 km/h)
        val pts = (0..140).map { p(it, 48.0, 18.0 + it * 0.00019) }
        val k = PointThinning.keep(pts)
        assertTrue(0 in k && 140 in k)
        assertTrue("ostalo ${k.size}", k.size in 25..45)
    }

    @Test fun eventsAndTurnsKept() {
        val east = (0..20).map { p(it, 48.0, 18.0 + it * 0.00019) }
        val north = (21..40).map { p(it, 48.0 + (it - 20) * 0.00013, 18.0 + 20 * 0.00019) }
        val pts = (east + north).toMutableList()
        pts[10] = p(10, 48.0, 18.0 + 10 * 0.00019, DriveEvent.BRAKE)
        val k = PointThinning.keep(pts)
        assertTrue(10 in k)
        // pri zákrute (index 20–22) ostane bod do 2 bodov od rohu
        assertTrue(k.any { it in 20..22 })
    }
}
