package sk.cartracker.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import sk.cartracker.container
import sk.cartracker.data.GeoMath
import sk.cartracker.data.local.PointEntity
import java.util.concurrent.TimeUnit

/**
 * Preriedenie bodov starých jázd (Nastavenia → Mapa → Preriediť staré trasy). V telefóne ostane bod približne každých
 * 60 m, v zákrutách hustejšie; prvý, posledný bod a udalosti (brzdenie, prekročenie…) ostanú vždy. Mažú sa iba body,
 * ktoré už sú na serveri – tam ostáva plná trasa. Kilometre, časy a kniha jázd sa nemenia.
 */
object PointThinning {
    /** Indexy bodov, ktoré ostanú (čistá funkcia – testovaná). */
    fun keep(pts: List<PointEntity>, stepM: Double = 60.0, turnDeg: Double = 25.0, maxGapMs: Long = 120_000): Set<Int> {
        if (pts.size <= 2) return pts.indices.toSet()
        val out = linkedSetOf(0)
        var last = 0; var lastBearing: Double? = null
        for (i in 1 until pts.size - 1) {
            val a = pts[last]; val p = pts[i]
            val d = GeoMath.distanceM(a.lat, a.lng, p.lat, p.lng)
            val b = if (d >= 10) GeoMath.bearing(a.lat, a.lng, p.lat, p.lng) else null
            val turn = b != null && lastBearing != null && d >= 15 && GeoMath.angleDiff(b, lastBearing) >= turnDeg
            if (p.event != null || d >= stepM || turn || p.ts - a.ts >= maxGapMs) {
                out += i
                if (b != null) lastBearing = b
                last = i
            }
        }
        out += pts.size - 1
        return out
    }

    /** @return počet zmazaných bodov */
    suspend fun run(ctx: Context): Int {
        val c = ctx.container
        val months = c.settings.current().thinAfterMonths
        if (months <= 0 || !c.session.isLoggedIn) return 0
        val prefs = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        val cutoff = System.currentTimeMillis() - months * 30L * 86_400_000
        val done = prefs.getLong("thin_done_until", 0)
        if (cutoff <= done) return 0
        val dao = c.db.tripDao()
        var removed = 0
        var until = cutoff
        for (t in c.trips.tripsBetween(done, cutoff)) {
            val pts = dao.points(t.id)
            if (pts.any { !it.synced }) { until = t.startedAt; break }   // čaká na odoslanie – pokračuje sa nabudúce
            val keep = keep(pts)
            val drop = pts.indices.filter { it !in keep }.map { pts[it].id }
            drop.chunked(500).forEach { dao.deleteSyncedPoints(it) }
            removed += drop.size
        }
        prefs.edit().putLong("thin_done_until", until).apply()
        return removed
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ThinningWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresDeviceIdle(false).setRequiresBatteryNotLow(true).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("thin_points", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class ThinningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { runCatching { PointThinning.run(applicationContext) }; return Result.success() }
}
