package sk.cartracker.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Čisté výpočty bez Android API – dajú sa overiť automatickými testami. */
object GeoMath {
    private const val R = 6_371_008.8

    /** Vzdialenosť dvoch bodov v metroch (haversine; na vzdialenosti do stoviek km odchýlka < 0,5 %). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * R * asin(sqrt(min(1.0, a)))
    }

    /** Smer z bodu 1 do bodu 2 v stupňoch (0 = sever, 90 = východ). */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
    }

    /** Rozdiel dvoch smerov v stupňoch (0–180). */
    fun angleDiff(a: Double, b: Double): Double { val d = kotlin.math.abs(a - b) % 360; return if (d > 180) 360 - d else d }
}

/**
 * Filter úsekov trasy auta (pozri TrackingService.carStep). Výsledok: dĺžka úseku v m alebo null = bod sa nepočíta.
 * [jump] je true, keď išlo o skok GPS (pre diagnostiku).
 */
object DistanceFilter {
    data class Step(val meters: Double?, val jump: Boolean = false)

    fun carStep(d: Double, dtSec: Double, speedKmh: Double, doppler: Boolean, accPrev: Float, accNow: Float): Step {
        val dt = dtSec.coerceAtLeast(1.0)
        if (!doppler) return Step(if (d < 5 && speedKmh < 5) null else d)
        if (speedKmh < 3 && d < max(25.0, 2.0 * max(accNow, accPrev))) return Step(null)
        if (d > 50 && dt < 30 && d / dt * 3.6 > max(speedKmh * 2 + 30, 40.0)) return Step(null, jump = true)
        return Step(if (dt <= 15 && speedKmh >= 3) min(d, speedKmh / 3.6 * dt * 1.1 + 3) else d)
    }
}
