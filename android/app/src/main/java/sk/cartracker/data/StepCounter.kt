package sk.cartracker.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sk.cartracker.container
import sk.cartracker.data.local.AppDatabase
import sk.cartracker.data.local.StepHourEntity
import sk.cartracker.data.remote.ApiProvider
import sk.cartracker.data.remote.StepHourDto
import sk.cartracker.data.remote.StepSyncRequest
import sk.cartracker.security.SessionStore
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Celodenné kroky z krokomera telefónu (TYPE_STEP_COUNTER – počíta ho hardvér stále, aj keď aplikácia nebeží).
 * Hodnota je súčet krokov od zapnutia telefónu; aplikácia si ju pravidelne (každých ~15 min, pri chôdzi a jazde
 * priebežne) prečíta a rozdiel od minulého čítania pripíše do hodín, v ktorých sa kroky urobili.
 */
object StepCounter {
    private const val PREFS = "step_counter"
    private val mutex = Mutex()
    private const val HOUR = 3_600_000L

    /** Kedy sa krokomer naposledy prečítal / kroky odoslali na server (0 = nikdy). */
    fun lastRead(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("ts", 0L)
    fun lastSync(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("synced_at", 0L)
    internal fun setLastSync(ctx: Context, at: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("synced_at", at).apply()

    /** Je čas odoslať kroky na server (podľa nastaveného intervalu, automatickej synchronizácie a Wi-Fi)? */
    fun syncDue(ctx: Context, s: AppSettings, now: Long = System.currentTimeMillis()): Boolean {
        if (!s.autoSync || !s.allDaySteps) return false
        if (now - lastSync(ctx) < s.stepSyncMin.coerceIn(1, 1440) * 60_000L - 30_000L) return false
        if (s.syncWifiOnly) {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
            if (cm.activeNetwork == null || cm.isActiveNetworkMetered) return false
        }
        return true
    }

    fun available(ctx: Context): Boolean {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return false
        if (sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    /** Krátko sa prihlási na krokomer, prečíta aktuálnu hodnotu a zapíše kroky. */
    suspend fun sample(ctx: Context) {
        if (!available(ctx)) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val ev = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Pair<Float, Long>> { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        // čas udalosti = čas posledného kroku (elapsedRealtime v ns) → prepočet na čas hodín
                        val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
                        if (cont.isActive) cont.resume(e.values[0] to at)
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        } ?: return
        feed(ctx, ev.first, ev.second, log = true)
    }

    /** Nová hodnota krokomera (aj z bežiaceho sledovania chôdze / jazdy). [lastStepAt] = čas posledného kroku. */
    suspend fun feed(ctx: Context, counter: Float, lastStepAt: Long = System.currentTimeMillis(), log: Boolean = false) = mutex.withLock {
        val c = ctx.container
        val uid = c.session.user()?.id ?: return@withLock
        if (!c.settings.current().allDaySteps) return@withLock
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val boot = now - SystemClock.elapsedRealtime()
        val last = p.getFloat("counter", -1f)
        val lastTs = p.getLong("ts", 0L)
        val lastBoot = p.getLong("boot", 0L)
        val lastUid = p.getInt("uid", -1)
        val save = { p.edit().putFloat("counter", counter).putLong("ts", now).putLong("boot", boot).putInt("uid", uid).apply() }
        if (last < 0 || lastUid != uid) { save(); DiagLog.log(ctx, DiagLog.WALK, "Krokomer: prvé čítanie (${counter.toInt()}) – kroky sa počítajú od teraz"); return@withLock }   // prvé čítanie = východisko
        val rebooted = abs(boot - lastBoot) > 120_000L || counter < last
        val delta = if (rebooted) counter else counter - last
        val from = if (rebooted) max(lastTs, boot) else lastTs
        val to = min(now, max(from, lastStepAt))
        if (delta >= 1f && delta < 150_000f) {
            val split = split(delta.roundToInt(), from, to)
            val dao = c.db.stepDao()
            withContext(Dispatchers.IO) {
                dao.upsert(split.map { (h, n) -> StepHourEntity(uid, h, (dao.get(uid, h)?.steps ?: 0) + n, synced = false) })
                if (log) {
                    val today = dayStart(now)
                    val total = dao.between(uid, today, today + 86_400_000L).sumOf { it.steps }
                    DiagLog.log(ctx, DiagLog.WALK, "Krokomer: +${delta.toInt()} krokov" + (if (rebooted) " (po reštarte telefónu)" else "") + " · dnes spolu $total")
                }
            }
        }
        if (delta >= 1f || rebooted) save() else p.edit().putLong("ts", now).putLong("boot", boot).apply()
    }

    /** Rozdelí [steps] do hodín intervalu [from, to] pomerne podľa času (bez zaokrúhľovacej straty). */
    fun split(steps: Int, from: Long, to: Long): List<Pair<Long, Int>> {
        if (steps <= 0) return emptyList()
        if (to <= from) return listOf(hourStart(to) to steps)
        val parts = ArrayList<Pair<Long, Double>>()
        var x = from
        while (x < to) {
            val h = hourStart(x); val e = min(to, h + HOUR)
            parts += h to (e - x).toDouble() / (to - from)
            x = e
        }
        var left = steps
        return parts.mapIndexed { i, (h, f) -> val n = if (i == parts.lastIndex) left else (steps * f).roundToInt().coerceAtMost(left); left -= n; h to n }
            .filter { it.second > 0 }
    }

    fun hourStart(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun dayStart(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    /** Kroky po dňoch (začiatok dňa → kroky). */
    fun byDay(hours: List<StepHourEntity>): Map<Long, Int> = hours.groupBy { dayStart(it.hour) }.mapValues { (_, l) -> l.sumOf { it.steps } }

    /** Vzdialenosť dňa: zaznamenané chôdze + kroky mimo nich × dĺžka kroku. */
    fun dayMeters(walkSteps: Int, walkMeters: Double, allSteps: Int, strideCm: Int): Double =
        walkMeters + max(0, allSteps - walkSteps) * strideCm / 100.0

    /** Denné kroky: väčšie z (zaznamenané chôdze, krokomer celý deň) – krokomer obsahuje aj kroky chôdzí. */
    fun merge(walkTotals: Map<Long, Double>, daily: Map<Long, Int>): Map<Long, Double> =
        (walkTotals.keys + daily.keys).associateWith { d -> max(walkTotals[d] ?: 0.0, (daily[d] ?: 0).toDouble()) }
}

/** Uložené kroky po hodinách a ich synchronizácia so serverom. */
class StepRepository(private val ctx: Context, private val db: AppDatabase, private val api: ApiProvider, private val session: SessionStore) {
    private val dao = db.stepDao()

    fun hours(): Flow<List<StepHourEntity>> = session.user()?.let { dao.observe(it.id) } ?: emptyFlow()
    fun daily(): Flow<Map<Long, Int>> = hours().map { StepCounter.byDay(it) }
    suspend fun between(from: Long, to: Long): List<StepHourEntity> = session.user()?.id?.let { dao.between(it, from, to) } ?: emptyList()
    suspend fun dailyBetween(from: Long, to: Long): Map<Long, Int> = StepCounter.byDay(between(from, to))

    suspend fun sync() = withContext(Dispatchers.IO) {
        val u = session.user()?.id ?: return@withContext
        repeat(10) {
            val rows = dao.unsynced(u)
            if (rows.isEmpty()) { StepCounter.setLastSync(ctx, System.currentTimeMillis()); return@withContext }
            for (chunk in rows.chunked(500)) {
                api.get().syncSteps(StepSyncRequest(chunk.map { StepHourDto(it.hour, it.steps) }))
                chunk.forEach { dao.markSynced(u, it.hour, it.steps) }
            }
        }
    }

    /** Obnova zo servera (nový telefón): doplní hodiny, ktoré v telefóne nie sú alebo majú menej krokov. */
    suspend fun restore(from: Long) = withContext(Dispatchers.IO) {
        val u = session.user()?.id ?: return@withContext 0
        val remote = runCatching { api.get().steps(from, System.currentTimeMillis() + 86_400_000L).hours }.getOrNull() ?: return@withContext 0
        val local = dao.between(u, from, Long.MAX_VALUE).associateBy { it.hour }
        val upd = remote.filter { (local[it.hour]?.steps ?: -1) < it.steps }.map { StepHourEntity(u, it.hour, it.steps, synced = true) }
        if (upd.isNotEmpty()) dao.upsert(upd)
        upd.size
    }
}
