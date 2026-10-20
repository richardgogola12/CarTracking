package sk.cartracker.data

import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Výpočty pre obrazovku Analýzy (rovnaké ako na webe): mesiace, dĺžky jázd, teplotná mapa deň × hodina,
 * ciele, dni v týždni a štýl jazdy po týždňoch. Čisté funkcie – overené testami.
 */
object Analytics {
    private val SK = Locale("sk", "SK")
    val DOW = listOf("Pondelok", "Utorok", "Streda", "Štvrtok", "Piatok", "Sobota", "Nedeľa")

    private fun TripEntity.ms() = ((endedAt ?: startedAt) - startedAt).coerceAtLeast(0)
    private fun cal(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
    /** 0 = pondelok … 6 = nedeľa */
    fun dowOf(ts: Long) = (cal(ts).get(Calendar.DAY_OF_WEEK) + 5) % 7
    private fun dayStart(ts: Long) = cal(ts).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    /** Skóre vážené kilometrami. */
    fun scoreAvg(list: List<TripEntity>): Int? {
        val sc = list.filter { it.score != null }
        val w = sc.sumOf { it.distanceM.coerceAtLeast(1.0) }
        return if (w <= 0) null else (sc.sumOf { it.score!! * it.distanceM.coerceAtLeast(1.0) } / w).roundToInt()
    }

    // ---------------- mesiace ----------------
    data class Month(
        val start: Long, val label: String, val longLabel: String, val trips: Int, val km: Double, val bizKm: Double,
        val ms: Long, val liters: Double, val cost: Double, val score: Int?, val steps: Int, val days: Int,
    ) {
        val privKm get() = km - bizKm
        val avgSpeed get() = if (ms > 0) km / (ms / 3_600_000.0) else 0.0
        val perTrip get() = if (trips > 0) km / trips else 0.0
    }

    /** Začiatky 12 mesiacov končiacich mesiacom s [endTs] (vrátane). */
    fun monthStarts(endTs: Long, count: Int = 12): List<Long> {
        val c = cal(endTs).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        c.add(Calendar.MONTH, -(count - 1))
        return List(count + 1) { i -> (c.clone() as Calendar).apply { add(Calendar.MONTH, i) }.timeInMillis }
    }

    fun months(cars: List<TripEntity>, walks: List<TripEntity>, endTs: Long, cost: (TripEntity) -> Double, liters: (TripEntity) -> Double): List<Month> {
        val st = monthStarts(endTs)
        val short = SimpleDateFormat("LLL yy", SK); val long = SimpleDateFormat("LLLL yyyy", SK)
        return (0 until st.size - 1).map { i ->
            val a = st[i]; val z = st[i + 1]
            val c = cars.filter { it.startedAt in a until z }; val w = walks.filter { it.startedAt in a until z }
            Month(a, short.format(a).replace(".", ""), long.format(a), c.size, c.sumOf { it.distanceM } / 1000,
                c.filter { it.category == CATEGORY_BUSINESS }.sumOf { it.distanceM } / 1000, c.sumOf { it.ms() },
                c.sumOf(liters), c.sumOf(cost), scoreAvg(c), w.sumOf { it.steps }, c.map { dayStart(it.startedAt) }.toSet().size)
        }
    }

    // ---------------- dĺžky jázd ----------------
    data class Bin(val label: String, val count: Int, val kmShare: Double)
    private val BINS = listOf(0.0 to 2.0, 2.0 to 5.0, 5.0 to 10.0, 10.0 to 20.0, 20.0 to 50.0, 50.0 to 100.0, 100.0 to Double.MAX_VALUE)
    private val BIN_LABELS = listOf("do 2", "2–5", "5–10", "10–20", "20–50", "50–100", "100+")

    fun lengthBins(cars: List<TripEntity>): List<Bin> {
        val total = cars.sumOf { it.distanceM }.coerceAtLeast(1.0)
        return BINS.mapIndexed { i, (a, z) ->
            val g = cars.filter { it.distanceM / 1000 >= a && it.distanceM / 1000 < z }
            Bin(BIN_LABELS[i], g.size, g.sumOf { it.distanceM } / total * 100)
        }
    }

    // ---------------- teplotná mapa ----------------
    class Heat(val km: Array<DoubleArray>, val count: Array<IntArray>) {
        val max get() = km.maxOf { r -> r.maxOrNull() ?: 0.0 }
        /** (deň, hodina) s najviac km */
        fun peak(): Pair<Int, Int> { var b = 0 to 0; for (d in 0..6) for (h in 0..23) if (km[d][h] > km[b.first][b.second]) b = d to h; return b }
    }

    fun heat(cars: List<TripEntity>): Heat {
        val km = Array(7) { DoubleArray(24) }; val n = Array(7) { IntArray(24) }
        cars.forEach { t -> val d = dowOf(t.startedAt); val h = cal(t.startedAt).get(Calendar.HOUR_OF_DAY); km[d][h] += t.distanceM / 1000; n[d][h]++ }
        return Heat(km, n)
    }

    // ---------------- ciele ----------------
    data class Dest(
        val name: String, val isPlace: Boolean, val visits: Int, val km: Double, val avgMs: Long, val arrivalMin: Int,
        val bizPct: Double, val cost: Double, val last: Long, val lastTripId: Long,
    ) { val avgKm get() = if (visits > 0) km / visits else 0.0 }

    fun destName(t: TripEntity): String? = t.endPlace ?: t.endAddress?.split(",")?.take(2)?.joinToString(",")?.trim()?.ifBlank { null }

    fun destinations(cars: List<TripEntity>, cost: (TripEntity) -> Double): List<Dest> =
        cars.mapNotNull { t -> destName(t)?.let { it to t } }.groupBy({ it.first }, { it.second }).map { (name, l) ->
            val last = l.maxBy { it.startedAt }
            val arr = l.map { val c = cal(it.endedAt ?: it.startedAt); c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }
            Dest(name, l.first().endPlace != null, l.size, l.sumOf { it.distanceM } / 1000, l.sumOf { it.ms() } / l.size,
                arr.average().roundToInt(), l.count { it.category == CATEGORY_BUSINESS } * 100.0 / l.size, l.sumOf(cost), last.startedAt, last.id)
        }.sortedByDescending { it.visits }

    // ---------------- dni v týždni ----------------
    data class Weekday(
        val index: Int, val name: String, val trips: Int, val km: Double, val ms: Long, val bizKm: Double,
        val firstDeparture: Int?, val score: Int?, val steps: Int, val perDayKm: Double,
    ) { val perTrip get() = if (trips > 0) km / trips else 0.0 }

    fun weekdays(cars: List<TripEntity>, walks: List<TripEntity>, from: Long, to: Long): List<Weekday> {
        val weeks = ((to - from) / (7 * 86_400_000.0)).roundToInt().coerceAtLeast(1)
        return DOW.mapIndexed { i, name ->
            val c = cars.filter { dowOf(it.startedAt) == i }; val w = walks.filter { dowOf(it.startedAt) == i }
            val firsts = c.groupBy { dayStart(it.startedAt) }.values.map { l -> val x = cal(l.minOf { it.startedAt }); x.get(Calendar.HOUR_OF_DAY) * 60 + x.get(Calendar.MINUTE) }
            val km = c.sumOf { it.distanceM } / 1000
            Weekday(i, name, c.size, km, c.sumOf { it.ms() }, c.filter { it.category == CATEGORY_BUSINESS }.sumOf { it.distanceM } / 1000,
                firsts.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), scoreAvg(c), w.sumOf { it.steps }, km / weeks)
        }
    }

    // ---------------- týždne ----------------
    data class Week(
        val start: Long, val label: String, val range: String, val trips: Int, val km: Double, val brakes: Int, val accels: Int,
        val speedingSec: Int, val ms: Long, val maxSpeed: Double, val score: Int?,
    ) {
        val per100 get() = if (km > 0) (brakes + accels) / km * 100 else 0.0
        val speedingPct get() = if (ms > 0) speedingSec * 1000.0 / ms * 100 else 0.0
    }

    /** ISO týždeň: (pondelok, číslo týždňa, rok týždňa). */
    fun isoWeek(ts: Long): Triple<Long, Int, Int> {
        val c = Calendar.getInstance(SK).apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; timeInMillis = dayStart(ts) }
        val mon = cal(dayStart(ts)).apply { add(Calendar.DAY_OF_MONTH, -dowOf(ts)) }.timeInMillis
        return Triple(mon, c.get(Calendar.WEEK_OF_YEAR), c.weekYear)
    }

    fun weeks(cars: List<TripEntity>): List<Week> {
        val f = SimpleDateFormat("d. M.", SK)
        return cars.groupBy { isoWeek(it.startedAt) }.map { (k, l) ->
            Week(k.first, "t. ${k.second} / ${k.third}", f.format(k.first) + "–" + f.format(k.first + 6 * 86_400_000L), l.size,
                l.sumOf { it.distanceM } / 1000, l.sumOf { it.hardBrakes }, l.sumOf { it.hardAccels }, l.sumOf { it.speedingSec },
                l.sumOf { it.ms() }, l.maxOf { it.maxSpeedKmh }, scoreAvg(l))
        }.sortedBy { it.start }
    }
}
