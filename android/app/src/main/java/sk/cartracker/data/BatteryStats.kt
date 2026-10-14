package sk.cartracker.data

import android.content.Context
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Koľko batérie minulo sledovanie (GPS beží): pri každom zapnutí / vypnutí GPS sa porovná stav batérie.
 * Úseky počas nabíjania sa do spotreby nezarátajú. Ukladá sa po dňoch (posledných 14 dní).
 */
object BatteryStats {
    data class Day(val day: String, val trackedMs: Long, val dropPct: Int, val measuredMs: Long, val sessions: Int) {
        /** Spotreba v % za hodinu sledovania (iba z úsekov bez nabíjania). */
        val perHour: Double? get() = if (measuredMs >= 10 * 60_000L) dropPct / (measuredMs / 3_600_000.0) else null
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("battery_stats", Context.MODE_PRIVATE)
    private fun key(ts: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts))

    /** (percento, nabíja sa) */
    fun level(ctx: Context): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return -1 to false
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    fun record(ctx: Context, from: Long, to: Long, startPct: Int, endPct: Int, charging: Boolean) {
        val ms = (to - from).coerceAtLeast(0)
        if (ms < 1000) return
        val p = prefs(ctx)
        val k = key(to)
        val cur = parse(k, p.getString(k, null))
        val valid = !charging && startPct in 0..100 && endPct in 0..100 && endPct <= startPct
        val n = cur.copy(
            trackedMs = cur.trackedMs + ms,
            dropPct = cur.dropPct + if (valid) startPct - endPct else 0,
            measuredMs = cur.measuredMs + if (valid) ms else 0,
            sessions = cur.sessions + 1,
        )
        val e = p.edit().putString(k, "${n.trackedMs},${n.dropPct},${n.measuredMs},${n.sessions}")
        p.all.keys.sorted().dropLast(14).forEach { e.remove(it) }
        e.apply()
    }

    private fun parse(day: String, v: String?): Day {
        val a = v?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        return if (a.size == 4) Day(day, a[0], a[1].toInt(), a[2], a[3].toInt()) else Day(day, 0, 0, 0, 0)
    }

    /** Posledné dni (najnovší prvý). */
    fun days(ctx: Context): List<Day> = prefs(ctx).all.entries
        .map { parse(it.key, it.value as? String) }
        .sortedByDescending { it.day }

    fun today(ctx: Context): Day = key(System.currentTimeMillis()).let { k -> parse(k, prefs(ctx).getString(k, null)) }
}
