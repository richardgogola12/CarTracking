package sk.cartracker.data

import sk.cartracker.data.local.PointEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Jeden bod rýchlostného profilu jazdy. */
data class SpeedSample(
    val ts: Long,
    /** metre od začiatku jazdy */
    val distM: Double,
    val kmh: Float,
    val event: String? = null,
)

/** Súhrn rýchlostného profilu. */
data class SpeedSummary(
    val maxKmh: Float,
    val maxAt: Int,
    /** priemer len z času v pohybe (≥ 5 km/h) */
    val movingAvgKmh: Double,
    val movingMs: Long,
    val stoppedMs: Long,
    /** podiel času v pásmach 0–50, 50–90, 90–130, 130+ km/h (z času v pohybe) */
    val bands: List<Float>,
)

/**
 * Rýchlostný profil z GPS bodov jazdy – chýbajúce rýchlosti dopočíta z polohy,
 * vyhladí šum a zredukuje počet bodov pre graf.
 */
object SpeedProfile {
    val BAND_EDGES = listOf(50f, 90f, 130f)
    val BAND_LABELS = listOf("do 50", "50–90", "90–130", "nad 130")

    fun build(points: List<PointEntity>, maxSamples: Int = 400): List<SpeedSample> {
        if (points.size < 2) return emptyList()
        val raw = ArrayList<SpeedSample>(points.size)
        var dist = 0.0
        for (i in points.indices) {
            val p = points[i]
            var kmh = p.speedKmh
            if (i > 0) {
                val prev = points[i - 1]
                val seg = haversine(prev.lat, prev.lng, p.lat, p.lng)
                dist += seg
                if (kmh == null) {
                    val dt = (p.ts - prev.ts) / 1000.0
                    kmh = if (dt > 0) (seg / dt * 3.6).toFloat() else 0f
                }
            }
            raw += SpeedSample(p.ts, dist, (kmh ?: 0f).coerceIn(0f, 300f), p.event)
        }
        // vyhladenie kĺzavým priemerom (3 body) – maximum ostane z nevyhladených dát
        val smooth = raw.mapIndexed { i, s ->
            val a = raw[max(0, i - 1)].kmh; val b = s.kmh; val c = raw[minOf(raw.lastIndex, i + 1)].kmh
            s.copy(kmh = (a + 2 * b + c) / 4f)
        }
        if (smooth.size <= maxSamples) return smooth
        // redukcia po skupinách: priemer rýchlosti, zachová udalosti
        val step = smooth.size / maxSamples.toDouble()
        return (0 until maxSamples).map { k ->
            val from = (k * step).toInt()
            val to = minOf(smooth.size, ((k + 1) * step).toInt()).coerceAtLeast(from + 1)
            val group = smooth.subList(from, to)
            val mid = group[group.size / 2]
            SpeedSample(mid.ts, mid.distM, group.map { it.kmh }.average().toFloat(), group.firstNotNullOfOrNull { it.event })
        }
    }

    fun summary(samples: List<SpeedSample>, tripMaxKmh: Double? = null): SpeedSummary {
        if (samples.size < 2) return SpeedSummary(0f, 0, 0.0, 0, 0, listOf(0f, 0f, 0f, 0f))
        var moving = 0L; var stopped = 0L; var movingDist = 0.0
        val bandMs = LongArray(4)
        for (i in 1 until samples.size) {
            val dt = samples[i].ts - samples[i - 1].ts
            if (dt <= 0 || dt > 10 * 60_000) continue
            val v = (samples[i].kmh + samples[i - 1].kmh) / 2
            if (v < 5f) { stopped += dt; continue }
            moving += dt
            movingDist += samples[i].distM - samples[i - 1].distM
            val band = BAND_EDGES.indexOfFirst { v < it }.let { if (it < 0) 3 else it }
            bandMs[band] += dt
        }
        val maxIdx = samples.indices.maxByOrNull { samples[it].kmh } ?: 0
        val maxKmh = max(samples[maxIdx].kmh, (tripMaxKmh ?: 0.0).toFloat())
        val total = bandMs.sum().coerceAtLeast(1)
        return SpeedSummary(
            maxKmh, maxIdx,
            if (moving > 0) movingDist / (moving / 1000.0) * 3.6 else 0.0,
            moving, stopped,
            bandMs.map { it / total.toFloat() },
        )
    }

    /** Pekný horný okraj osi Y a krok mriežky. */
    fun axis(maxKmh: Float): Pair<Float, Float> {
        val step = when {
            maxKmh <= 40 -> 10f
            maxKmh <= 100 -> 20f
            maxKmh <= 160 -> 30f
            else -> 50f
        }
        val top = (kotlin.math.ceil((maxKmh + 5) / step) * step).coerceAtLeast(step * 2)
        return top to step
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
