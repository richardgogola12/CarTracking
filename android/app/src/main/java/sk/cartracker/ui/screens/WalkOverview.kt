package sk.cartracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.WalkAnalytics
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val SKW = Locale("sk", "SK")
private val W_BLUE = Color(0xFF2563EB); private val W_BLUE_L = Color(0xFF60A5FA); private val W_GREEN = Color(0xFF16A34A)
private val W_AMBER = Color(0xFFF59E0B); private val W_ROSE = Color(0xFFE11D48); private val W_VIOLET = Color(0xFF7C3AED)
private fun k0(v: Number) = String.format(SKW, "%,d", v.toLong())
private fun k1(v: Double) = String.format(SKW, "%.1f", v)
private fun kc(v: Double) = if (v >= 10_000) String.format(SKW, "%.1fk", v / 1000) else k0(v.roundToInt())

/** Údaje prehľadu chôdze pre obdobie [from, to) – počíta sa raz a zdieľa medzi súhrnom a tabuľkami. */
class WalkData(
    val from: Long, val to: Long, val goal: Int, val walks: List<TripEntity>, val days: List<WalkAnalytics.Day>, val yearDays: List<WalkAnalytics.Day>,
    val sum: WalkAnalytics.Summary, val hours: DoubleArray, val wdays: List<WalkAnalytics.Weekday>, val weeks: List<WalkAnalytics.Group>,
    val months: List<WalkAnalytics.Group>, val bins: List<WalkAnalytics.Bin>, val workFrom: Int, val workTo: Int,
)

@Composable
fun rememberWalkData(all: List<TripEntity>, from: Long, to: Long, s: AppSettings): WalkData = remember(all, from, to, s) {
    val today = WalkAnalytics.dayStart(System.currentTimeMillis())
    val tomorrow = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    val end = minOf(to, tomorrow).coerceAtLeast(from + 1)
    val yearAgo = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -364) }.timeInMillis
    val work: (Long, Long) -> Double = { a, b -> WalkAnalytics.workShare(a, b, s.workDaySet, s.workFromMin, s.workToMin) }
    val walksAll = all.filter { it.isWalk }
    val walks = walksAll.filter { it.startedAt in from until end }
    val goal = s.dailyStepGoal
    val days = WalkAnalytics.days(walks, from, end, goal, work)
    val yearDays = WalkAnalytics.days(walksAll.filter { it.startedAt >= yearAgo }, yearAgo, tomorrow, goal, work)
    val streaks = stepStreaks(dailyTotals(walksAll, walk = true), goal)
    WalkData(from, end, goal, walks, days, yearDays, WalkAnalytics.summary(walks, days, streaks.first, streaks.second), WalkAnalytics.byHour(walks),
        WalkAnalytics.weekdays(days), WalkAnalytics.weeks(days), WalkAnalytics.months(yearDays), WalkAnalytics.stepBins(walks), s.workFromMin, s.workToMin)
}

/** Súhrn (10 ukazovateľov) a grafy chôdze – súčasť Štatistiky → Chôdza. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WalkSummary(d: WalkData) {
    val sum = d.sum; val goal = d.goal; val days = d.days
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        val m = Modifier.weight(1f)
        Kpi(m, "Kroky spolu", k0(sum.steps), "${sum.activeDays} z ${sum.days} dní aktívnych", W_BLUE)
        Kpi(m, "Ø krokov za deň", k0(sum.avgSteps.roundToInt()), if (goal > 0) "cieľ ${k0(goal)}" else "", W_BLUE_L)
        Kpi(m, "Cieľ splnený", "${sum.goalDays} ×", "${sum.goalPct.roundToInt()} % dní", W_GREEN)
        Kpi(m, "Séria dní s cieľom", "${sum.curStreak}", "rekord ${sum.bestStreak}", W_AMBER)
        Kpi(m, "Vzdialenosť", formatDistance(sum.meters), "${sum.walks} chôdzí", W_VIOLET)
        Kpi(m, "Čas chôdze", formatDuration(sum.ms), "Ø ${formatDuration(if (sum.days > 0) sum.ms / sum.days else 0)} za deň", W_VIOLET)
        Kpi(m, "Ø tempo", WalkAnalytics.paceText(sum.pace) + " min/km", sum.pace?.let { "≈ ${k1(60 / it)} km/h" } ?: "", W_ROSE)
        Kpi(m, "V pracovnom čase", "${sum.workPct.roundToInt()} %", "${k0(sum.workSteps)} krokov", W_GREEN)
        sum.bestDay?.let { b -> Kpi(m, "Najlepší deň", k0(b.steps), formatDate(b.start), W_AMBER) }
        sum.longest?.let { l -> Kpi(m, "Najdlhšia chôdza", k0(l.steps), "${formatDate(l.startedAt)} · ${formatDistance(l.distanceM)}", W_BLUE) }
    }
    if (days.size > 1) SectionCard("Kroky po dňoch") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Legend(listOf(W_GREEN to "cieľ splnený", W_BLUE_L to "pod cieľom", W_AMBER to "denný cieľ ${k0(goal)}"))
            DayBars(days, goal, Modifier.fillMaxWidth().height(200.dp))
        }
    }
    SectionCard("Rok v krokoch · posledných 12 mesiacov") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            YearHeat(d.yearDays, goal)
            Text("Každý štvorček je deň (stĺpec = týždeň, riadky po–ne). Čím tmavší, tým viac krokov; zelené = cieľ splnený.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (d.walks.isNotEmpty()) SectionCard(if (days.size > 1) "Kedy chodíte · Ø krokov za deň podľa hodiny" else "Kedy ste chodili · kroky podľa hodiny") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            val avg = d.hours.map { it / max(1, days.size) }
            SimpleBars(avg.mapIndexed { h, v -> (if (h % 3 == 0) "$h" else "") to v }, Modifier.fillMaxWidth().height(170.dp), W_BLUE,
                highlight = avg.indices.maxByOrNull { avg[it] })
            val peak = avg.indices.maxByOrNull { avg[it] } ?: 0
            Text("Najviac medzi $peak:00 a ${peak + 1}:00 (${k0(avg[peak].roundToInt())} krokov). Pracovný čas ${hmw(d.workFrom)}–${hmw(d.workTo)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (days.size >= 7) SectionCard("Dni v týždni · Ø krokov") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            SimpleBars(d.wdays.map { listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")[it.index] to it.avgSteps }, Modifier.fillMaxWidth().height(170.dp), W_VIOLET,
                highlight = d.wdays.indices.maxByOrNull { d.wdays[it].avgSteps }, goal = goal.toDouble(),
                top = d.wdays.map { if (it.days > 0) "${it.goalPct.roundToInt()} %" else "" })
            Text("Percentá nad stĺpcami = ako často v ten deň splníte cieľ.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (d.weeks.size >= 2) SectionCard("Vývoj po týždňoch · Ø krokov za deň a tempo") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Legend(listOf(W_BLUE to "Ø krokov / deň", W_ROSE to "tempo (min/km)"))
            TrendChart(d.weeks, Modifier.fillMaxWidth().height(190.dp))
        }
    }
    if (d.walks.isNotEmpty()) SectionCard("Dĺžka chôdzí (kroky)") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            SimpleBars(d.bins.map { it.label to it.count.toDouble() }, Modifier.fillMaxWidth().height(160.dp), W_AMBER,
                top = d.bins.map { if (it.count > 0) "${it.stepShare.roundToInt()} %" else "" }, valueLabels = true)
            Text("Stĺpec = počet chôdzí, percento = podiel na všetkých krokoch. Krátke presuny (do 500 krokov) bývajú v budove.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Triediteľné tabuľky chôdze: po dňoch, týždňoch, mesiacoch a najdlhšie chôdze. */
@Composable
fun WalkTables(d: WalkData, onNavigate: (String) -> Unit) {
    val sum = d.sum; val goal = d.goal
    if (d.days.size > 1) SectionCard("Po dňoch") {
        val dn = remember { SimpleDateFormat("EE d. M.", SKW) }
        SortableTable<WalkAnalytics.Day>(d.days.reversed(), listOf(
            TCol("Deň", 92.dp, start = true, bold = true, text = { dn.format(it.start) }, key = { it.start.toDouble() }),
            TCol("Kroky", 72.dp, bold = true, text = { k0(it.steps) }, key = { it.steps.toDouble() }, color = { x -> if (x.goalMet) W_GREEN else null }),
            TCol("Cieľ", 50.dp, text = { if (goal <= 0) "" else if (it.goalMet) "✓" else "${(it.steps * 100 / goal)} %" }, key = { it.steps.toDouble() }),
            TCol("Km", 58.dp, text = { k1(it.meters / 1000) }, key = { it.meters }),
            TCol("Čas", 70.dp, text = { if (it.ms > 0) formatDuration(it.ms) else "" }, key = { it.ms.toDouble() }),
            TCol("Chôdzí", 58.dp, text = { k0(it.walks) }, key = { it.walks.toDouble() }),
            TCol("Tempo", 62.dp, text = { WalkAnalytics.paceText(it.pace) }, key = { it.pace ?: 999.0 }),
            TCol("V práci", 70.dp, text = { k0(it.workSteps) }, key = { it.workSteps.toDouble() }),
        ), initialSort = 0, initialDesc = true, limit = 14, csvName = "chodza-dni", onRowClick = { onNavigate("timeline?d=${it.start}") },
            note = "Ťuknutím na deň otvoríte časovú os dňa.",
            footer = listOf("Spolu", k0(sum.steps), "${sum.goalDays}×", k1(sum.meters / 1000), formatDuration(sum.ms), k0(sum.walks), WalkAnalytics.paceText(sum.pace), k0(sum.workSteps)))
    }
    if (d.weeks.size > 1) SectionCard("Po týždňoch") {
        SortableTable<WalkAnalytics.Group>(d.weeks, groupCols(goal), initialSort = 0, initialDesc = true, limit = 12, rowHeight = 46.dp, csvName = "chodza-tyzdne",
            footer = groupFooter(d.weeks))
    }
    SectionCard("Po mesiacoch · posledných 12 mesiacov") {
        SortableTable<WalkAnalytics.Group>(d.months, groupCols(goal), initialSort = 0, initialDesc = true, rowHeight = 46.dp, csvName = "chodza-mesiace",
            footer = groupFooter(d.months))
    }
    if (d.walks.isNotEmpty()) SectionCard("Najdlhšie chôdze") {
        val top = d.walks.sortedByDescending { it.steps }.take(50)
        SortableTable<TripEntity>(top, listOf(
            TCol("Dátum", 96.dp, start = true, bold = true, text = { formatDate(it.startedAt) + "\n" + formatTime(it.startedAt) + "–" + formatTime(it.endedAt ?: it.startedAt) }, key = { it.startedAt.toDouble() }),
            TCol("Kroky", 70.dp, bold = true, text = { k0(it.steps) }, key = { it.steps.toDouble() }),
            TCol("Km", 58.dp, text = { k1(it.distanceM / 1000) }, key = { it.distanceM }),
            TCol("Čas", 70.dp, text = { formatDuration((it.endedAt ?: it.startedAt) - it.startedAt) }, key = { ((it.endedAt ?: it.startedAt) - it.startedAt).toDouble() }),
            TCol("Tempo", 62.dp, text = { WalkAnalytics.paceText(WalkAnalytics.pace((it.endedAt ?: it.startedAt) - it.startedAt, it.distanceM)) },
                key = { WalkAnalytics.pace((it.endedAt ?: it.startedAt) - it.startedAt, it.distanceM) ?: 999.0 }),
            TCol("Trasa", 170.dp, start = true, text = { listOfNotNull(it.startPlace ?: it.startAddress?.substringBefore(","), it.endPlace ?: it.endAddress?.substringBefore(",")).joinToString(" → ") }, key = { it.startAddress ?: "" }),
        ), initialSort = 1, initialDesc = true, limit = 10, rowHeight = 46.dp, onRowClick = { onNavigate("trip/${it.id}") },
            note = "Ťuknutím otvoríte chôdzu s mapou.", csvName = "chodza-najdlhsie")
    }
}

private fun hmw(min: Int) = "${min / 60}:${(min % 60).toString().padStart(2, '0')}"

private fun groupCols(goal: Int) = listOf<TCol<WalkAnalytics.Group>>(
    TCol("Obdobie", 112.dp, start = true, bold = true, text = { it.label + "\n" + it.sub }, key = { it.start.toDouble() }),
    TCol("Kroky", 78.dp, bold = true, text = { k0(it.steps) }, key = { it.steps.toDouble() }),
    TCol("Ø / deň", 66.dp, text = { k0(it.avgSteps.roundToInt()) }, key = { it.avgSteps }, color = { g -> if (goal > 0 && g.avgSteps >= goal) W_GREEN else null }),
    TCol("Dni s cieľom", 84.dp, text = { "${it.goalDays} / ${it.days}" }, key = { it.goalDays.toDouble() }),
    TCol("Km", 62.dp, text = { k1(it.meters / 1000) }, key = { it.meters }),
    TCol("Čas", 74.dp, text = { formatDuration(it.ms) }, key = { it.ms.toDouble() }),
    TCol("Tempo", 60.dp, text = { WalkAnalytics.paceText(it.pace) }, key = { it.pace ?: 999.0 }),
    TCol("Najlepší deň", 84.dp, text = { k0(it.bestDay) }, key = { it.bestDay.toDouble() }),
    TCol("V práci", 72.dp, text = { if (it.steps > 0) "${(it.workSteps * 100 / it.steps)} %" else "" }, key = { it.workSteps.toDouble() }),
)

private fun groupFooter(g: List<WalkAnalytics.Group>) = listOf("Spolu", k0(g.sumOf { it.steps }),
    k0((g.sumOf { it.steps }.toDouble() / max(1, g.sumOf { it.days })).roundToInt()), "${g.sumOf { it.goalDays }} / ${g.sumOf { it.days }}",
    k1(g.sumOf { it.meters } / 1000), formatDuration(g.sumOf { it.ms }), "", "", "")

@Composable
private fun Kpi(modifier: Modifier, label: String, value: String, sub: String, accent: Color) {
    Card(modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 4.dp).size(8.dp).background(accent, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- grafy ----------------

@Composable
private fun DayBars(days: List<WalkAnalytics.Day>, goal: Int, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant
    val dfmt = remember { SimpleDateFormat("d. M.", SKW) }
    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val left = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left - 4.dp.toPx(); val h = size.height - top - bottom
        val maxV = nice(max(days.maxOf { it.steps }.toDouble(), goal * 1.1))
        for (k in 0..4) {
            val y = top + h * (1 - k / 4f)
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, kc(maxV * k / 4), left - 4.dp.toPx(), y, muted, alignEnd = true)
        }
        val slot = w / days.size; val bw = (slot * 0.7f).coerceAtLeast(1f)
        days.forEachIndexed { i, d ->
            val bh = (d.steps / maxV * h).toFloat()
            if (bh > 0) drawRoundRect(if (d.goalMet) W_GREEN else W_BLUE_L, Offset(left + i * slot + (slot - bw) / 2, top + h - bh), Size(bw, bh),
                CornerRadius(minOf(bw / 2, 4.dp.toPx())))
        }
        if (goal > 0) {
            val gy = top + h - (goal / maxV * h).toFloat()
            drawLine(W_AMBER, Offset(left, gy), Offset(left + w, gy), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val step = max(1, days.size / 6)
        days.forEachIndexed { i, d -> if (i % step == 0 || i == days.lastIndex) label(tm, dfmt.format(d.start), left + i * slot + slot / 2, top + h + 9.dp.toPx(), muted, center = true, size = 8) }
    }
}

@Composable
private fun SimpleBars(data: List<Pair<String, Double>>, modifier: Modifier, color: Color, highlight: Int? = null, goal: Double? = null,
                       top: List<String>? = null, valueLabels: Boolean = false) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant; val fg = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas
        val left = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val topPad = 16.dp.toPx()
        val w = size.width - left - 4.dp.toPx(); val h = size.height - topPad - bottom
        val maxV = nice(max(data.maxOf { it.second }, goal ?: 0.0).coerceAtLeast(1.0))
        for (k in 0..4) {
            val y = topPad + h * (1 - k / 4f)
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, kc(maxV * k / 4), left - 4.dp.toPx(), y, muted, alignEnd = true)
        }
        val slot = w / data.size; val bw = (slot * 0.62f).coerceAtLeast(1f)
        data.forEachIndexed { i, (l, v) ->
            val bh = (v / maxV * h).toFloat(); val x = left + i * slot + (slot - bw) / 2
            val col = if (highlight == null || highlight == i) color else color.copy(alpha = 0.55f)
            if (bh > 0) drawRoundRect(col, Offset(x, topPad + h - bh), Size(bw, bh), CornerRadius(minOf(bw / 2, 5.dp.toPx())))
            val t = top?.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: if (valueLabels && v > 0) kc(v) else null
            if (t != null) label(tm, t, x + bw / 2, topPad + h - bh - 7.dp.toPx(), fg, center = true, size = 8)
            if (l.isNotEmpty()) label(tm, l, x + bw / 2, topPad + h + 9.dp.toPx(), muted, center = true, size = 8)
        }
        if (goal != null && goal > 0) {
            val gy = topPad + h - (goal / maxV * h).toFloat()
            drawLine(W_AMBER, Offset(left, gy), Offset(left + w, gy), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}

@Composable
private fun TrendChart(weeks: List<WalkAnalytics.Group>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val left = AXIS_W.dp.toPx(); val right = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        val maxV = nice(weeks.maxOf { it.avgSteps }.coerceAtLeast(1.0))
        val paces = weeks.mapNotNull { it.pace }
        val pMin = (paces.minOrNull() ?: 8.0).let { kotlin.math.floor(it) }; val pMax = (paces.maxOrNull() ?: 16.0).let { kotlin.math.ceil(it) }.coerceAtLeast(pMin + 1)
        for (k in 0..4) {
            val y = top + h * (1 - k / 4f)
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, kc(maxV * k / 4), left - 4.dp.toPx(), y, muted, alignEnd = true)
            label(tm, WalkAnalytics.paceText(pMin + (pMax - pMin) * k / 4), left + w + 4.dp.toPx(), y, W_ROSE)
        }
        val slot = w / weeks.size; val bw = slot * 0.6f
        val path = Path(); var started = false
        weeks.forEachIndexed { i, g ->
            val bh = (g.avgSteps / maxV * h).toFloat(); val cx = left + i * slot + slot / 2
            if (bh > 0) drawRoundRect(W_BLUE.copy(alpha = .75f), Offset(cx - bw / 2, top + h - bh), Size(bw, bh), CornerRadius(minOf(bw / 2, 4.dp.toPx())))
            g.pace?.let { p -> val y = top + h * (1 - ((p - pMin) / (pMax - pMin)).toFloat()); if (!started) { path.moveTo(cx, y); started = true } else path.lineTo(cx, y); drawCircle(W_ROSE, 2.5.dp.toPx(), Offset(cx, y)) }
            if (weeks.size <= 8 || i % (weeks.size / 6 + 1) == 0) label(tm, g.label.substringBefore(" /"), cx, top + h + 9.dp.toPx(), muted, center = true, size = 8)
        }
        drawPath(path, W_ROSE, style = Stroke(2.dp.toPx()))
    }
}

/** Kalendár roka ako mriežka (stĺpce = týždne, riadky = po–ne), posúvateľný, na konci dnešok. */
@Composable
private fun YearHeat(days: List<WalkAnalytics.Day>, goal: Int) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val empty = MaterialTheme.colorScheme.surfaceVariant
    if (days.isEmpty()) return
    val lead = WalkAnalytics.dow(days.first().start)
    val cols = (lead + days.size + 6) / 7
    val cell = 13.dp; val gap = 3.dp
    val maxV = max(days.maxOf { it.steps }, max(goal, 1)).toDouble()
    val mfmt = remember { SimpleDateFormat("LLL", SKW) }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll)) {
        Canvas(Modifier.width((cell + gap) * cols + 22.dp).height((cell + gap) * 7 + 16.dp)) {
            val c = cell.toPx(); val g = gap.toPx(); val left = 22.dp.toPx(); val topPad = 14.dp.toPx()
            listOf(0 to "Po", 2 to "St", 4 to "Pi", 6 to "Ne").forEach { (r, l) -> label(tm, l, 0f, topPad + r * (c + g) + c / 2, muted, size = 8) }
            var lastMonth = -1
            days.forEachIndexed { i, d ->
                val idx = lead + i; val col = idx / 7; val row = idx % 7
                val x = left + col * (c + g); val y = topPad + row * (c + g)
                val color = when {
                    d.steps <= 0 -> empty
                    d.goalMet -> W_GREEN.copy(alpha = (0.55 + 0.45 * (d.steps / maxV)).toFloat().coerceAtMost(1f))
                    else -> W_BLUE.copy(alpha = (0.15 + 0.7 * (d.steps / maxV)).toFloat())
                }
                drawRoundRect(color, Offset(x, y), Size(c, c), CornerRadius(3.dp.toPx()))
                val m = Calendar.getInstance().apply { timeInMillis = d.start }.get(Calendar.MONTH)
                if (m != lastMonth && row == 0) { label(tm, mfmt.format(d.start), x, 5.dp.toPx(), muted, size = 8); lastMonth = m }
            }
        }
    }
}
