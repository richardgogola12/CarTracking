package sk.cartracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.cartracker.container
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TripEntity

/**
 * Po oprave limitu („Moje limity“) sa prepočítajú staršie jazdy: prekročenia v opravenom úseku, pri ktorých ste išli
 * najviac povolenou rýchlosťou + tolerancia, sa zrušia; čas prekračovania sa zníži pomerne a skóre jazdy sa prepočíta.
 */
object SpeedingFix {
    /** Smer jazdy v bode – z GPS, inak z polohy susedných bodov. */
    fun bearingAt(points: List<PointEntity>, i: Int): Float? {
        points[i].bearing?.let { return it }
        val a = points.getOrNull(i - 2) ?: points.getOrNull(i - 1) ?: return null
        val b = points.getOrNull(i + 2) ?: points.getOrNull(i + 1) ?: return null
        if (GeoMath.distanceM(a.lat, a.lng, b.lat, b.lng) < 5) return null
        return GeoMath.bearing(a.lat, a.lng, b.lat, b.lng).toFloat()
    }

    /** Indexy bodov s udalosťou „speeding“, ktoré podľa opravených limitov neboli prekročením. */
    fun falseEvents(points: List<PointEntity>, custom: List<SpeedLimits.Custom>, toleranceKmh: Int): List<Int> =
        points.indices.filter { i ->
            val p = points[i]
            if (p.event != DriveEvent.SPEEDING) return@filter false
            val lim = SpeedLimits.custom(custom, p.lat, p.lng, bearingAt(points, i)) ?: return@filter false
            (p.speedKmh ?: 0f) <= lim + toleranceKmh
        }

    /** Nový čas prekračovania (s) – pomerne podľa zrušených udalostí. */
    fun reducedSpeedingSec(sec: Int, total: Int, removed: Int): Int =
        if (total <= 0 || removed <= 0) sec else if (removed >= total) 0 else sec * (total - removed) / total

    /** Prepočíta jazdy za posledných [days] dní. @return počet upravených jázd */
    suspend fun recompute(ctx: Context, days: Int = 120): Int = withContext(Dispatchers.IO) {
        val c = ctx.container
        val uid = c.session.user()?.id ?: return@withContext 0
        val s = c.settings.current()
        val custom = SpeedLimits.parseCustom(s.customLimits)
        if (custom.isEmpty()) return@withContext 0
        val dao = c.db.tripDao()
        var changed = 0
        for (t in dao.tripsWithSpeeding(uid, System.currentTimeMillis() - days * 86_400_000L)) {
            val pts = dao.points(t.id)
            val total = pts.count { it.event == DriveEvent.SPEEDING }
            if (total == 0) continue
            val bad = falseEvents(pts, custom, s.speedToleranceKmh)
            if (bad.isEmpty()) continue
            dao.clearPointEvents(bad.map { pts[it].id })
            val sec = reducedSpeedingSec(t.speedingSec, total, bad.size)
            val dur = (t.endedAt ?: t.startedAt) - t.startedAt
            dao.updateTrip(t.copy(speedingSec = sec, synced = false,
                score = DrivingScore.compute(t.distanceM, dur, t.hardBrakes, t.hardAccels, sec)))
            changed++
        }
        if (changed > 0 && s.autoSync) sk.cartracker.sync.SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
        changed
    }
}
