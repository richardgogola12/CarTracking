package sk.cartracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.cartracker.container
import kotlin.math.roundToInt

/**
 * Dĺžka kroku dospelého je ~55–95 cm (približne výška × 0,415). Automatická kalibrácia z GPS ju v starších verziách
 * stiahla na ~45 cm (GPS pri chôdzi trasu skracuje) → vzdialenosť chôdze vychádzala o ~40 % menšia.
 */
object StrideFix {
    const val MIN_CM = 55
    const val MAX_CM = 95
    const val DEFAULT_CM = 75

    /** Dĺžka kroku z výšky postavy (cm). */
    fun fromHeight(heightCm: Int) = (heightCm * 0.415).roundToInt().coerceIn(MIN_CM, MAX_CM)

    /**
     * Raz po aktualizácii: nereálnu dĺžku kroku vráti na predvolenú, vymaže zlé kalibračné údaje a prepočíta
     * vzdialenosť uložených chôdzí, ktoré majú menej ako 0,55 m na krok (vypočítané so zlou dĺžkou kroku / skrátené GPS).
     */
    suspend fun runOnce(ctx: Context) = withContext(Dispatchers.IO) {
        val p = ctx.getSharedPreferences("stride_fix", Context.MODE_PRIVATE)
        if (p.getBoolean("v1", false)) return@withContext
        val c = ctx.container
        val uid = c.session.user()?.id ?: return@withContext
        var s = c.settings.current()
        if (s.strideCm < MIN_CM) {
            val old = s.strideCm
            c.settings.update { it.copy(strideCm = DEFAULT_CM, strideCalMeters = 0, strideCalSteps = 0, strideCalWalks = 0) }
            s = c.settings.current()
            DiagLog.log(ctx, DiagLog.WALK, "Dĺžka kroku $old cm bola nereálne krátka (zlá kalibrácia z GPS) → nastavená $DEFAULT_CM cm. Upravte ju v Nastavenia → Chôdza (podľa výšky).")
        }
        val n = recompute(ctx, uid, s.strideCm, onlyImplausible = true)
        if (n > 0) DiagLog.log(ctx, DiagLog.WALK, "Prepočítaná vzdialenosť $n chôdzí (${s.strideCm} cm/krok)")
        p.edit().putBoolean("v1", true).apply()
    }

    /** Prepočíta vzdialenosť chôdzí z krokov (všetky, alebo iba tie s nereálne krátkym krokom). Vráti počet zmenených. */
    suspend fun recompute(ctx: Context, uid: Int, strideCm: Int, onlyImplausible: Boolean): Int = withContext(Dispatchers.IO) {
        val c = ctx.container
        val dao = c.db.tripDao()
        var n = 0
        for (t in dao.allTrips(uid)) {
            if (!t.isWalk || !t.finished || t.steps < 20) continue
            val perStep = t.distanceM / t.steps
            if (onlyImplausible && perStep >= MIN_CM / 100.0) continue
            val dist = t.steps * strideCm / 100.0
            if (kotlin.math.abs(dist - t.distanceM) < 1) continue
            val ms = ((t.endedAt ?: t.startedAt) - t.startedAt).coerceAtLeast(1)
            dao.updateTrip(t.copy(distanceM = dist, avgSpeedKmh = dist / 1000.0 / (ms / 3_600_000.0)))
            dao.markTripUnsynced(t.id)
            n++
        }
        if (n > 0) sk.cartracker.sync.SyncWorker.enqueueNow(ctx, c.settings.current().syncWifiOnly)
        n
    }
}
