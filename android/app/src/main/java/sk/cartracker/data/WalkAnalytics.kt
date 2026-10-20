package sk.cartracker.data

import sk.cartracker.data.local.TripEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Podrobný prehľad chôdze: dni, týždne, mesiace, hodiny dňa, dni v týždni, tempo, pracovný čas, dĺžky chôdzí, rekordy.
 * Čisté funkcie (bez Androidu) – overené testami.
 */
object WalkAnalytics {
    private val SK = Locale("sk", "SK")
    private const val DAY = 86_400_000L

    private fun cal(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
    fun dayStart(ts: Long) = cal(ts).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun nextDay(d: Long) = cal(d).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    private fun TripEntity.ms() = ((endedAt ?: startedAt) - startedAt).coerceAtLeast(0)
    /** 0 = pondelok … 6 = nedeľa */
    fun dow(ts: Long) = (cal(ts).get(Calendar.DAY_OF_WEEK) + 5) % 7

    /** Tempo v min/km (iba pri aspoň 200 m). */
    fun pace(ms: Long, meters: Double): Double? = if (meters >= 200 && ms > 0) ms / 60_000.0 / (meters / 1000) else null
    fun paceText(p: Double?): String = p?.let { val m = it.toInt(); val s = ((it - m) * 60).roundToInt().coerceAtMost(59); "$m:${s.toString().padStart(2, '0')}" } ?: "–"

    // ---------------- dni ----------------
    data class Day(
        val start: Long, val steps: Int, val meters: Double, val ms: Long, val walks: Int, val workSteps: Int, val goalMet: Boolean,
    ) { val pace get() = pace(ms, meters) }

    /** Všetky dni v [from, to) – aj tie bez chôdze (0 krokov). */
    fun days(walks: List<TripEntity>, from: Long, to: Long, goal: Int, work: (Long, Long) -> Double): List<Day> {
        val byDay = walks.groupBy { dayStart(it.startedAt) }
        val out = ArrayList<Day>()
        var d = dayStart(from)
        while (d < to) {
            val l = byDay[d].orEmpty()
            val steps = l.sumOf { it.steps }
            val workSteps = l.sumOf { (it.steps * work(it.startedAt, it.endedAt ?: it.startedAt)).roundToInt() }
            out += Day(d, steps, l.sumOf { it.distanceM }, l.sumOf { it.ms() }, l.size, workSteps, goal in 1..steps)
            d = nextDay(d)
        }
        return out
    }

    /**
     * Podiel času chôdze [a, b) v pracovnom čase (pracovné dni 1 = po … 7 = ne, od–do v minútach).
     * Chôdza cez začiatok alebo koniec práce sa započíta pomerne.
     */
    fun workShare(a: Long, b: Long, workDays: Set<Int>, fromMin: Int, toMin: Int): Double {
        if (b <= a) {
            val c = cal(a); val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            return if (dow(a) + 1 in workDays && m in fromMin until toMin) 1.0 else 0.0
        }
        var inside = 0L
        var d = dayStart(a)
        while (d < b) {
            if (dow(d) + 1 in workDays) {
                val ws = cal(d).apply { add(Calendar.MINUTE, fromMin) }.timeInMillis
                val we = cal(d).apply { add(Calendar.MINUTE, toMin) }.timeInMillis
                inside += max(0L, min(b, we) - max(a, ws))
            }
            d = nextDay(d)
        }
        return inside.toDouble() / (b - a)
    }

    // ---------------- hodiny dňa ----------------
    /** Kroky po hodinách 0–23; kroky chôdze sa rozdelia pomerne podľa času, ktorý strávila v každej hodine. */
    fun byHour(walks: List<TripEntity>): DoubleArray {
        val h = DoubleArray(24)
        for (t in walks) {
            val a = t.startedAt; val b = t.endedAt ?: a
            if (b <= a) { h[cal(a).get(Calendar.HOUR_OF_DAY)] += t.steps.toDouble(); continue }
            var x = a
            while (x < b) {
                val c = cal(x); val hour = c.get(Calendar.HOUR_OF_DAY)
                val end = min(b, cal(x).apply { set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.HOUR_OF_DAY, 1) }.timeInMillis)
                h[hour] += t.steps * (end - x).toDouble() / (b - a)
                x = end
            }
        }
        return h
    }

    // ---------------- dni v týždni ----------------
    data class Weekday(val index: Int, val avgSteps: Double, val avgMeters: Double, val goalPct: Double, val days: Int)

    fun weekdays(days: List<Day>): List<Weekday> = (0..6).map { i ->
        val l = days.filter { dow(it.start) == i }
        Weekday(i, l.map { it.steps }.average().takeIf { !it.isNaN() } ?: 0.0, l.map { it.meters }.average().takeIf { !it.isNaN() } ?: 0.0,
            if (l.isEmpty()) 0.0 else l.count { it.goalMet } * 100.0 / l.size, l.size)
    }

    // ---------------- týždne a mesiace ----------------
    data class Group(
        val start: Long, val label: String, val sub: String, val days: Int, val steps: Int, val meters: Double, val ms: Long,
        val walks: Int, val goalDays: Int, val bestDay: Int, val workSteps: Int,
    ) {
        val avgSteps get() = if (days > 0) steps.toDouble() / days else 0.0
        val pace get() = pace(ms, meters)
    }

    private fun group(key: Long, label: String, sub: String, l: List<Day>) = Group(key, label, sub, l.size, l.sumOf { it.steps }, l.sumOf { it.meters },
        l.sumOf { it.ms }, l.sumOf { it.walks }, l.count { it.goalMet }, l.maxOfOrNull { it.steps } ?: 0, l.sumOf { it.workSteps })

    fun weeks(days: List<Day>): List<Group> {
        val f = SimpleDateFormat("d. M.", SK)
        return days.groupBy { cal(it.start).apply { add(Calendar.DAY_OF_MONTH, -dow(it.start)) }.timeInMillis }.map { (mon, l) ->
            val c = Calendar.getInstance(SK).apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; timeInMillis = mon }
            group(mon, "t. ${c.get(Calendar.WEEK_OF_YEAR)} / ${c.weekYear}", f.format(mon) + "–" + f.format(mon + 6 * DAY), l)
        }.sortedBy { it.start }
    }

    fun months(days: List<Day>): List<Group> {
        val f = SimpleDateFormat("LLLL yyyy", SK)
        return days.groupBy { cal(it.start).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }.map { (m, l) ->
            group(m, f.format(m), "${l.size} dní", l)
        }.sortedBy { it.start }
    }

    // ---------------- dĺžky chôdzí ----------------
    data class Bin(val label: String, val count: Int, val stepShare: Double)
    private val BINS = listOf(0 to 500, 500 to 1000, 1000 to 2000, 2000 to 5000, 5000 to 10_000, 10_000 to Int.MAX_VALUE)
    private val LABELS = listOf("do 500", "500–1k", "1–2k", "2–5k", "5–10k", "10k+")

    fun stepBins(walks: List<TripEntity>): List<Bin> {
        val total = max(1, walks.sumOf { it.steps })
        return BINS.mapIndexed { i, (a, z) -> val g = walks.filter { it.steps in a until z }; Bin(LABELS[i], g.size, g.sumOf { it.steps } * 100.0 / total) }
    }

    // ---------------- súhrn ----------------
    data class Summary(
        val steps: Int, val meters: Double, val ms: Long, val walks: Int, val days: Int, val activeDays: Int, val goalDays: Int,
        val avgSteps: Double, val bestDay: Day?, val longest: TripEntity?, val workSteps: Int, val curStreak: Int, val bestStreak: Int,
    ) {
        val pace get() = pace(ms, meters)
        val goalPct get() = if (days > 0) goalDays * 100.0 / days else 0.0
        val workPct get() = if (steps > 0) workSteps * 100.0 / steps else 0.0
    }

    fun summary(walks: List<TripEntity>, days: List<Day>, curStreak: Int, bestStreak: Int) = Summary(
        days.sumOf { it.steps }, days.sumOf { it.meters }, days.sumOf { it.ms }, walks.size, days.size, days.count { it.steps > 0 },
        days.count { it.goalMet }, if (days.isEmpty()) 0.0 else days.sumOf { it.steps }.toDouble() / days.size,
        days.filter { it.steps > 0 }.maxByOrNull { it.steps }, walks.maxByOrNull { it.steps }, days.sumOf { it.workSteps }, curStreak, bestStreak,
    )
}
