package sk.cartracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.cartracker.data.AppSettings
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMonth
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val sk = Locale("sk")

/** Začiatok dňa pre daný čas. */
fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Súčet za deň: kroky (chôdza) alebo km (auto). */
fun dailyTotals(trips: List<TripEntity>, walk: Boolean): Map<Long, Double> =
    trips.filter { it.isWalk == walk }.groupBy { dayStart(it.startedAt) }
        .mapValues { (_, l) -> if (walk) l.sumOf { it.steps }.toDouble() else l.sumOf { it.distanceM } / 1000.0 }

/**
 * Kalendár aktivity – mesiac ako mriežka, farba dňa podľa krokov / km.
 * Pri chôdzi je deň so splneným cieľom krokov orámovaný.
 */
@Composable
fun ActivityCalendar(all: List<TripEntity>, walk: Boolean, monthTs: Long, stepGoal: Int) {
    val totals = remember(all, walk) { dailyTotals(all, walk) }
    val cal = Calendar.getInstance().apply { timeInMillis = monthTs; set(Calendar.DAY_OF_MONTH, 1) }
    val first = dayStart(cal.timeInMillis)
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // pondelok = 0
    val values = (0 until days).map { i ->
        val c = Calendar.getInstance().apply { timeInMillis = first; add(Calendar.DAY_OF_MONTH, i) }
        totals[dayStart(c.timeInMillis)] ?: 0.0
    }
    val maxV = max(values.maxOrNull() ?: 0.0, if (walk) stepGoal.toDouble() else 1.0)
    val today = dayStart(System.currentTimeMillis())
    val base = MaterialTheme.colorScheme.surfaceVariant
    val strong = if (walk) Color(0xFF1E88E5) else Color(0xFF2E7D32)
    val goalColor = Color(0xFFFFB300)
    var selected by remember(monthTs, walk) { mutableStateOf<Int?>(null) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatMonth(first).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth()) {
            listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val cells = lead + days
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (cIdx in 0 until 7) {
                    val idx = r * 7 + cIdx - lead
                    if (idx !in 0 until days) { Spacer(Modifier.weight(1f)); continue }
                    val v = values[idx]
                    val frac = if (v <= 0) 0f else (0.18f + 0.82f * min(1.0, v / maxV).toFloat())
                    val color = if (v <= 0) base else lerp(base, strong, frac)
                    val dayTs = Calendar.getInstance().apply { timeInMillis = first; add(Calendar.DAY_OF_MONTH, idx) }.timeInMillis
                    val goal = walk && v >= stepGoal
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .background(color, RoundedCornerShape(6.dp))
                            .then(if (goal) Modifier.border(2.dp, goalColor, RoundedCornerShape(6.dp))
                                  else if (dayStart(dayTs) == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                  else Modifier)
                            .clickable { selected = idx },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${idx + 1}", fontSize = 11.sp, color = if (frac > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        val sel = selected
        val month = values.sum()
        val activeDays = values.count { it > 0 }
        Text(
            if (sel != null) "${sel + 1}. deň: " + (if (walk) String.format(sk, "%,d krokov", values[sel].toInt()) else formatDistance(values[sel] * 1000))
            else (if (walk) String.format(sk, "Spolu %,d krokov · aktívnych dní %d · cieľ splnený %d×", month.toInt(), activeDays, values.count { it >= stepGoal })
                  else "Spolu ${formatDistance(month * 1000)} · dní s jazdou $activeDays"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("menej ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(0f, 0.3f, 0.55f, 0.8f, 1f).forEach { f ->
                Box(Modifier.size(12.dp).background(if (f == 0f) base else lerp(base, strong, f), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(2.dp))
            }
            Text(" viac", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (walk) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(12.dp).border(2.dp, goalColor, RoundedCornerShape(3.dp)))
                Text(" cieľ splnený", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Série dní so splneným cieľom krokov: (aktuálna, najdlhšia). */
fun stepStreaks(totals: Map<Long, Double>, goal: Int): Pair<Int, Int> {
    if (goal <= 0) return 0 to 0
    val goalDays = totals.filter { it.value >= goal }.keys.sorted()
    var best = 0; var run = 0; var prev: Long? = null
    for (d in goalDays) {
        run = if (prev != null && isNextDay(prev, d)) run + 1 else 1
        best = max(best, run); prev = d
    }
    // aktuálna séria: končí dnes, alebo včera (dnešok ešte nemusí byť splnený)
    val today = dayStart(System.currentTimeMillis())
    var cur = 0
    var day = if ((totals[today] ?: 0.0) >= goal) today else prevDay(today)
    while ((totals[day] ?: 0.0) >= goal) { cur++; day = prevDay(day) }
    return cur to best
}

private fun prevDay(d: Long) = Calendar.getInstance().apply { timeInMillis = d; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis.let(::dayStart)
private fun isNextDay(a: Long, b: Long) = Calendar.getInstance().apply { timeInMillis = a; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis.let(::dayStart) == b

/** Osobné rekordy a série. */
@Composable
fun RecordsCard(all: List<TripEntity>, walk: Boolean, s: AppSettings) {
    val totals = remember(all, walk) { dailyTotals(all, walk) }
    val list = remember(all, walk) { all.filter { it.isWalk == walk } }
    if (list.isEmpty()) return
    SectionCard(if (walk) "Rekordy a série" else "Rekordy") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (walk) {
                val (cur, best) = remember(totals, s.dailyStepGoal) { stepStreaks(totals, s.dailyStepGoal) }
                Row(Modifier.fillMaxWidth()) {
                    StatBox("Aktuálna séria", "$cur ${dni(cur)}", Modifier.weight(1f))
                    StatBox("Najdlhšia séria", "$best ${dni(best)}", Modifier.weight(1f))
                }
                Text(String.format(sk, "Séria = dni za sebou so splneným cieľom %,d krokov.", s.dailyStepGoal),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                totals.maxByOrNull { it.value }?.let { RecordRow("🏆", "Najviac krokov za deň", String.format(sk, "%,d", it.value.toInt()), formatDate(it.key)) }
                list.maxByOrNull { it.distanceM }?.let { RecordRow("📏", "Najdlhšia chôdza", formatDistance(it.distanceM), formatDate(it.startedAt)) }
                list.maxByOrNull { it.steps }?.let { RecordRow("👣", "Najviac krokov naraz", String.format(sk, "%,d", it.steps), formatDate(it.startedAt)) }
                RecordRow("📅", "Dní so splneným cieľom", "${totals.count { it.value >= s.dailyStepGoal }}", "celkovo")
            } else {
                list.maxByOrNull { it.distanceM }?.let { RecordRow("📏", "Najdlhšia jazda", formatDistance(it.distanceM), formatDate(it.startedAt)) }
                totals.maxByOrNull { it.value }?.let { RecordRow("🛣️", "Najviac km za deň", formatDistance(it.value * 1000), formatDate(it.key)) }
                list.maxByOrNull { (it.endedAt ?: it.startedAt) - it.startedAt }?.let {
                    RecordRow("⏱️", "Najdlhšie za volantom", formatDuration((it.endedAt ?: it.startedAt) - it.startedAt), formatDate(it.startedAt))
                }
                list.maxByOrNull { it.maxSpeedKmh }?.let { RecordRow("🚀", "Najvyššia rýchlosť", "${it.maxSpeedKmh.toInt()} km/h", formatDate(it.startedAt)) }
                list.filter { it.score != null && it.distanceM >= 5000 }.maxByOrNull { it.score!! }?.let {
                    RecordRow("⭐", "Najlepší štýl jazdy (od 5 km)", "${it.score}", formatDate(it.startedAt))
                }
                RecordRow("🚗", "Spolu najazdené", formatDistance(list.sumOf { it.distanceM }), "${list.size} jázd")
            }
        }
    }
}

private fun dni(n: Int) = when (n) { 1 -> "deň"; in 2..4 -> "dni"; else -> "dní" }

@Composable
private fun RecordRow(icon: String, label: String, value: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontWeight = FontWeight.Bold)
    }
}

// ---------------- pracovný výkaz chôdze ----------------

data class WorkDay(val day: Long, val steps: Int, val distanceM: Double, val ms: Long, val records: Int)

/**
 * Chôdza v pracovnom čase (dni a hodiny z Nastavenia → Chôdza). Záznam, ktorý presahuje pracovný čas,
 * sa započíta pomerne podľa času, ktorý do neho spadá.
 */
fun workWalking(trips: List<TripEntity>, s: AppSettings, from: Long, to: Long): List<WorkDay> {
    val byDay = linkedMapOf<Long, WorkDay>()
    for (t in trips.filter { it.isWalk && it.startedAt in from until to }.sortedBy { it.startedAt }) {
        val day = dayStart(t.startedAt)
        val cal = Calendar.getInstance().apply { timeInMillis = day }
        val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        if (dow !in s.workDaySet) continue
        val ws = day + s.workFromMin * 60_000L
        val we = day + s.workToMin * 60_000L
        val end = t.endedAt ?: t.startedAt
        val overlap = max(0L, min(end, we) - max(t.startedAt, ws))
        if (overlap <= 0) continue
        val dur = max(1L, end - t.startedAt)
        val f = overlap.toDouble() / dur
        val prev = byDay[day] ?: WorkDay(day, 0, 0.0, 0, 0)
        byDay[day] = prev.copy(steps = prev.steps + (t.steps * f).toInt(), distanceM = prev.distanceM + t.distanceM * f,
            ms = prev.ms + overlap, records = prev.records + 1)
    }
    return byDay.values.toList()
}
