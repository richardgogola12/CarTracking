package sk.cartracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.Analytics
import sk.cartracker.data.cost
import sk.cartracker.data.liters
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.ui.Reports
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

private val SKL = Locale("sk", "SK")
private val GREEN = Color(0xFF16A34A); private val VIOLET = Color(0xFF7C3AED); private val AMBER = Color(0xFFF59E0B)
private val ROSE = Color(0xFFE11D48); private val CYAN = Color(0xFF06B6D4); private val BLUE = Color(0xFF2563EB)
private fun n1(v: Double) = String.format(SKL, "%.1f", v)
private fun n0(v: Number) = String.format(SKL, "%,d", v.toLong())
private fun hm(min: Int?) = min?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: ""
private fun hours(ms: Long) = n1(ms / 3_600_000.0) + " h"

private enum class APeriod(val label: String, val months: Int) { M1("Tento mesiac", 1), M3("3 mesiace", 3), Y("12 mesiacov", 12), THIS_YEAR("Tento rok", 0) }

/** Analýzy – grafy a triediteľné tabuľky (to isté ako stránka Analýzy na webe). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(onBack: () -> Unit, onOpenTrip: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    var period by remember { mutableStateOf(APeriod.M1) }
    var vehicleFilter by remember { mutableStateOf("") }
    var vehicles by remember { mutableStateOf<List<VehicleEntity>>(emptyList()) }
    var all by remember { mutableStateOf<List<TripEntity>?>(null) }
    val now = remember { System.currentTimeMillis() }
    val months12 = remember { Analytics.monthStarts(now) }
    LaunchedEffect(Unit) {
        vehicles = c.vehicles.all()
        val from = minOf(months12.first(), Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis)
        all = c.trips.tripsBetween(from, now + 86_400_000L)
    }
    val vmap = remember(vehicles) { vehicles.associateBy { it.uuid } }
    val costOf: (TripEntity) -> Double = { it.cost(vmap[it.vehicleUuid], s) }
    val litersOf: (TripEntity) -> Double = { it.liters(vmap[it.vehicleUuid], s) }

    val (pFrom, pTo) = remember(period) {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = when (period) {
            APeriod.THIS_YEAR -> cal.apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            else -> cal.apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -(period.months - 1)) }.timeInMillis
        }
        from to now + 1
    }
    val trips = all ?: emptyList()
    val yCars = trips.filter { !it.isWalk && (vehicleFilter.isEmpty() || it.vehicleUuid == vehicleFilter) && it.startedAt >= months12.first() }
    val yWalks = trips.filter { it.isWalk && it.startedAt >= months12.first() }
    val cars = trips.filter { !it.isWalk && (vehicleFilter.isEmpty() || it.vehicleUuid == vehicleFilter) && it.startedAt in pFrom until pTo }
    val walks = trips.filter { it.isWalk && it.startedAt in pFrom until pTo }
    val months = remember(all, vehicleFilter, s) { Analytics.months(yCars, yWalks, now, costOf, litersOf) }
    val weeks = remember(all, vehicleFilter) { Analytics.weeks(yCars) }
    val bins = remember(all, vehicleFilter, period) { Analytics.lengthBins(cars) }
    val heat = remember(all, vehicleFilter, period) { Analytics.heat(cars) }
    val dests = remember(all, vehicleFilter, period, s) { Analytics.destinations(cars, costOf) }
    val dows = remember(all, vehicleFilter, period) { Analytics.weekdays(cars, walks, pFrom, pTo) }
    val yTitle = "${months.firstOrNull()?.longLabel ?: ""} – ${months.lastOrNull()?.longLabel ?: ""}"

    Scaffold(topBar = {
        TopAppBar(title = { Text("Analýzy") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        APeriod.entries.forEach { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
                    }
                    if (vehicles.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(vehicleFilter.isEmpty(), { vehicleFilter = "" }, { Text("Všetky vozidlá") })
                        vehicles.filter { !it.archived }.forEach { v -> FilterChip(vehicleFilter == v.uuid, { vehicleFilter = v.uuid }, { Text(v.label) }) }
                    }
                    if (all == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Grafy a tabuľky „12 mesiacov“ sú vždy za posledných 12 mesiacov, ostatné za zvolené obdobie (${period.label.lowercase()}). " +
                        "Tabuľky zoradíte ťuknutím na nadpis stĺpca, posúvajú sa do strany.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (all == null) return@LazyColumn

            item {
                SectionCard("Vývoj za 12 mesiacov") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Legend(listOf(VIOLET to "Služobné km", GREEN to "Súkromné km", AMBER to "Náklady €"))
                        MonthChart(months, Modifier.fillMaxWidth().height(220.dp))
                    }
                }
            }
            item {
                SectionCard("Dĺžka jázd · ${period.label.lowercase()}") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (cars.isEmpty()) EmptyNote() else {
                            Legend(listOf(CYAN to "Počet jázd", ROSE to "% najazdených km"))
                            LengthChart(bins, Modifier.fillMaxWidth().height(200.dp))
                        }
                    }
                }
            }
            item {
                SectionCard("Kedy jazdíte · km podľa dňa a hodiny") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (cars.isEmpty()) EmptyNote() else {
                            HeatMap(heat, Modifier.fillMaxWidth())
                            val (d, h) = heat.peak()
                            Text("Najviac: ${Analytics.DOW[d]} $h:00 – ${n1(heat.km[d][h])} km (${heat.count[d][h]} jázd)",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            item {
                SectionCard("Rýchlosť × vzdialenosť") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (cars.isEmpty()) EmptyNote() else {
                            Legend(listOf(VIOLET to "Služobné", GREEN to "Súkromné"))
                            ScatterChart(cars, Modifier.fillMaxWidth().height(230.dp), onOpenTrip)
                            Text("Každá bodka je jedna jazda – ťuknutím otvoríte detail.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                SectionCard("Štýl jazdy po týždňoch · 12 mesiacov") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (weeks.isEmpty()) EmptyNote() else {
                            Legend(listOf(GREEN to "Skóre", ROSE to "Udalosti / 100 km", AMBER to "% času nad limitom"))
                            StyleChart(weeks, Modifier.fillMaxWidth().height(200.dp))
                        }
                    }
                }
            }

            // ---------------- tabuľky ----------------
            item {
                SectionCard("Prehľad po mesiacoch") {
                    SortableTable<Analytics.Month>(months, listOf(
                        TCol("Mesiac", 116.dp, start = true, bold = true, text = { it.longLabel }, key = { it.start.toDouble() }),
                        TCol("Jazdy", 56.dp, text = { n0(it.trips) }, key = { it.trips.toDouble() }),
                        TCol("Km", 70.dp, bold = true, text = { n1(it.km) }, key = { it.km }),
                        TCol("Služobné", 80.dp, text = { n1(it.bizKm) + if (it.km > 0) " (${(it.bizKm / it.km * 100).roundToInt()} %)" else "" }, key = { it.bizKm }),
                        TCol("Súkromné", 74.dp, text = { n1(it.privKm) }, key = { it.privKm }),
                        TCol("Dni", 46.dp, text = { n0(it.days) }, key = { it.days.toDouble() }),
                        TCol("Za volantom", 86.dp, text = { hours(it.ms) }, key = { it.ms.toDouble() }),
                        TCol("Ø km/h", 62.dp, text = { if (it.ms > 0) n0(it.avgSpeed.roundToInt()) else "" }, key = { it.avgSpeed }),
                        TCol("Ø km/jazda", 78.dp, text = { if (it.trips > 0) n1(it.perTrip) else "" }, key = { it.perTrip }),
                        TCol("Palivo l", 66.dp, text = { n1(it.liters) }, key = { it.liters }),
                        TCol("Náklady", 84.dp, text = { formatMoney(it.cost) }, key = { it.cost }),
                        TCol("Ø skóre", 62.dp, text = { it.score?.toString() ?: "" }, key = { (it.score ?: -1).toDouble() }, color = { scoreColor(it.score) }),
                        TCol("Kroky", 74.dp, text = { n0(it.steps) }, key = { it.steps.toDouble() }),
                    ), initialSort = 0, initialDesc = true,
                        footer = listOf("Spolu", n0(months.sumOf { it.trips }), n1(months.sumOf { it.km }), n1(months.sumOf { it.bizKm }), n1(months.sumOf { it.privKm }),
                            n0(months.sumOf { it.days }), hours(months.sumOf { it.ms }), "", "", n1(months.sumOf { it.liters }), formatMoney(months.sumOf { it.cost }), "", n0(months.sumOf { it.steps })),
                        csvName = "mesiace")
                }
            }
            item {
                SectionCard("Najčastejšie ciele · ${period.label.lowercase()}") {
                    if (dests.isEmpty()) Box(Modifier.padding(horizontal = 12.dp)) { EmptyNote() } else
                    SortableTable<Analytics.Dest>(dests, listOf(
                        TCol("Cieľ", 150.dp, start = true, bold = true, text = { (if (it.isPlace) "📍 " else "") + it.name }, key = { it.name }),
                        TCol("Návštevy", 70.dp, bold = true, text = { n0(it.visits) }, key = { it.visits.toDouble() }),
                        TCol("Km spolu", 72.dp, text = { n1(it.km) }, key = { it.km }),
                        TCol("Ø km", 56.dp, text = { n1(it.avgKm) }, key = { it.avgKm }),
                        TCol("Ø trvanie", 74.dp, text = { formatDuration(it.avgMs) }, key = { it.avgMs.toDouble() }),
                        TCol("Ø príchod", 72.dp, text = { hm(it.arrivalMin) }, key = { it.arrivalMin.toDouble() }),
                        TCol("Služobne", 66.dp, text = { "${it.bizPct.roundToInt()} %" }, key = { it.bizPct }),
                        TCol("Náklady", 80.dp, text = { formatMoney(it.cost) }, key = { it.cost }),
                        TCol("Naposledy", 86.dp, text = { formatDate(it.last) }, key = { it.last.toDouble() }),
                    ), initialSort = 1, initialDesc = true, limit = 15, onRowClick = { onOpenTrip(it.lastTripId) },
                        footer = listOf("${dests.size} cieľov", n0(dests.sumOf { it.visits }), n1(dests.sumOf { it.km }), "", "", "", "", formatMoney(dests.sumOf { it.cost }), ""),
                        note = "Ťuknutím na riadok otvoríte poslednú jazdu do cieľa.", csvName = "ciele")
                }
            }
            item {
                SectionCard("Dni v týždni · ${period.label.lowercase()}") {
                    SortableTable<Analytics.Weekday>(dows, listOf(
                        TCol("Deň", 92.dp, start = true, bold = true, text = { it.name }, key = { it.index.toDouble() }),
                        TCol("Jazdy", 56.dp, text = { n0(it.trips) }, key = { it.trips.toDouble() }),
                        TCol("Km", 66.dp, bold = true, text = { n1(it.km) }, key = { it.km }),
                        TCol("Ø km / deň", 78.dp, text = { n1(it.perDayKm) }, key = { it.perDayKm }),
                        TCol("Ø jazda", 66.dp, text = { if (it.trips > 0) n1(it.perTrip) + " km" else "" }, key = { it.perTrip }),
                        TCol("Za volantom", 86.dp, text = { hours(it.ms) }, key = { it.ms.toDouble() }),
                        TCol("Služobné km", 86.dp, text = { n1(it.bizKm) }, key = { it.bizKm }),
                        TCol("Ø 1. odchod", 80.dp, text = { hm(it.firstDeparture) }, key = { (it.firstDeparture ?: 9999).toDouble() }),
                        TCol("Ø skóre", 62.dp, text = { it.score?.toString() ?: "" }, key = { (it.score ?: -1).toDouble() }, color = { scoreColor(it.score) }),
                        TCol("Kroky", 74.dp, text = { n0(it.steps) }, key = { it.steps.toDouble() }),
                    ), initialSort = 0, initialDesc = false,
                        footer = listOf("Spolu", n0(dows.sumOf { it.trips }), n1(dows.sumOf { it.km }), "", "", hours(dows.sumOf { it.ms }), n1(dows.sumOf { it.bizKm }), "", "", n0(dows.sumOf { it.steps })),
                        csvName = "dni-v-tyzdni")
                }
            }
            item {
                SectionCard("Štýl jazdy po týždňoch · tabuľka") {
                    if (weeks.isEmpty()) Box(Modifier.padding(horizontal = 12.dp)) { EmptyNote() } else
                    SortableTable<Analytics.Week>(weeks, listOf(
                        TCol("Týždeň", 120.dp, start = true, bold = true, text = { it.label + "\n" + it.range }, key = { it.start.toDouble() }),
                        TCol("Jazdy", 56.dp, text = { n0(it.trips) }, key = { it.trips.toDouble() }),
                        TCol("Km", 66.dp, bold = true, text = { n1(it.km) }, key = { it.km }),
                        TCol("Brzdenia", 70.dp, text = { n0(it.brakes) }, key = { it.brakes.toDouble() }),
                        TCol("Zrýchlenia", 78.dp, text = { n0(it.accels) }, key = { it.accels.toDouble() }),
                        TCol("Na 100 km", 74.dp, text = { n1(it.per100) }, key = { it.per100 }),
                        TCol("Nad limitom", 100.dp, text = { formatDuration(it.speedingSec * 1000L) + " · " + n1(it.speedingPct) + " %" }, key = { it.speedingPct }),
                        TCol("Max km/h", 70.dp, text = { n0(it.maxSpeed.roundToInt()) }, key = { it.maxSpeed }),
                        TCol("Ø skóre", 62.dp, text = { it.score?.toString() ?: "" }, key = { (it.score ?: -1).toDouble() }, color = { scoreColor(it.score) }),
                    ), initialSort = 0, initialDesc = true, limit = 12, rowHeight = 48.dp,
                        footer = listOf("${weeks.size} týždňov", n0(weeks.sumOf { it.trips }), n1(weeks.sumOf { it.km }), n0(weeks.sumOf { it.brakes }), n0(weeks.sumOf { it.accels }),
                            n1(weeks.sumOf { it.km }.let { k -> if (k > 0) weeks.sumOf { it.brakes + it.accels } / k * 100 else 0.0 }), formatDuration(weeks.sumOf { it.speedingSec } * 1000L), "", ""),
                        csvName = "styl-po-tyzdnoch")
                }
            }
            item {
                Text("Obdobie 12 mesiacov: $yTitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun scoreColor(s: Int?): Color? = when {
    s == null -> null; s >= 85 -> GREEN; s >= 70 -> AMBER; else -> ROSE
}

@Composable private fun EmptyNote() = Text("Žiadne jazdy v období", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c, RoundedCornerShape(3.dp))); Spacer(Modifier.width(4.dp))
                Text(l, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- grafy (Canvas) ----------------

private const val AXIS_W = 34f   // dp – miesto pre popisy osi

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignEnd: Boolean = false, center: Boolean = false, size: Int = 9) {
    val r = tm.measure(text, TextStyle(fontSize = size.sp, color = color))
    val dx = when { alignEnd -> -r.size.width.toFloat(); center -> -r.size.width / 2f; else -> 0f }
    drawText(r, topLeft = Offset(x + dx, y - r.size.height / 2f))
}

private fun nice(max: Double): Double {
    if (max <= 0) return 1.0
    val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(max)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * p }.first { it >= max }
}

@Composable
private fun MonthChart(months: List<Analytics.Month>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val left = AXIS_W.dp.toPx(); val right = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        val maxKm = nice(months.maxOfOrNull { it.km } ?: 0.0); val maxEur = nice(months.maxOfOrNull { it.cost } ?: 0.0)
        for (k in 0..4) {
            val y = top + h * (1 - k / 4f)
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, n0((maxKm * k / 4).roundToInt()), left - 4.dp.toPx(), y, muted, alignEnd = true)
            label(tm, n0((maxEur * k / 4).roundToInt()) + " €", left + w + 4.dp.toPx(), y, AMBER)
        }
        val slot = w / months.size; val bw = slot * 0.62f
        val path = Path()
        months.forEachIndexed { i, m ->
            val x = left + i * slot + (slot - bw) / 2
            val hb = (m.bizKm / maxKm * h).toFloat(); val hp = (m.privKm / maxKm * h).toFloat()
            if (hp > 0) drawRoundRect(GREEN, Offset(x, top + h - hb - hp), Size(bw, hp), CornerRadius(4f, 4f))
            if (hb > 0) drawRect(VIOLET, Offset(x, top + h - hb), Size(bw, hb))
            val cx = left + i * slot + slot / 2; val cy = top + h - (m.cost / maxEur * h).toFloat()
            if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
            if (i % 2 == (months.size - 1) % 2) label(tm, m.label, cx, top + h + 9.dp.toPx(), muted, center = true, size = 8)
        }
        drawPath(path, AMBER, style = Stroke(2.dp.toPx()))
        months.forEachIndexed { i, m -> drawCircle(AMBER, 2.5.dp.toPx(), Offset(left + i * slot + slot / 2, top + h - (m.cost / maxEur * h).toFloat())) }
    }
}

@Composable
private fun LengthChart(bins: List<Analytics.Bin>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant; val fg = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val left = 8.dp.toPx(); val right = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val top = 14.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        val maxN = nice((bins.maxOfOrNull { it.count } ?: 0).toDouble())
        for (k in 0..4) {
            val y = top + h * (1 - k / 4f)
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, "${k * 25} %", left + w + 4.dp.toPx(), y, ROSE)
        }
        val slot = w / bins.size; val bw = slot * 0.6f
        val path = Path()
        bins.forEachIndexed { i, b ->
            val x = left + i * slot + (slot - bw) / 2; val bh = (b.count / maxN * h).toFloat()
            if (bh > 0) drawRoundRect(CYAN, Offset(x, top + h - bh), Size(bw, bh), CornerRadius(4f, 4f))
            if (b.count > 0) label(tm, b.count.toString(), x + bw / 2, top + h - bh - 7.dp.toPx(), fg, center = true)
            val cx = left + i * slot + slot / 2; val cy = top + h - (b.kmShare / 100 * h).toFloat()
            if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
            label(tm, b.label, cx, top + h + 9.dp.toPx(), muted, center = true, size = 8)
        }
        drawPath(path, ROSE, style = Stroke(2.dp.toPx()))
        bins.forEachIndexed { i, b -> drawCircle(ROSE, 2.5.dp.toPx(), Offset(left + i * slot + slot / 2, top + h - (b.kmShare / 100 * h).toFloat())) }
    }
}

@Composable
private fun HeatMap(heat: Analytics.Heat, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = heat.max.coerceAtLeast(0.1)
    Canvas(modifier.height(170.dp)) {
        val left = 22.dp.toPx(); val top = 12.dp.toPx(); val gap = 2f
        val cw = (size.width - left) / 24f; val ch = (size.height - top) / 7f
        for (h in 0..23 step 3) label(tm, h.toString(), left + h * cw + cw / 2, 5.dp.toPx(), muted, center = true, size = 8)
        for (d in 0..6) {
            label(tm, Analytics.DOW[d].take(2), 0f, top + d * ch + ch / 2, muted, size = 9)
            for (h in 0..23) {
                val v = heat.km[d][h]
                val c = if (v <= 0) empty else GREEN.copy(alpha = (0.18 + 0.82 * v / max).toFloat())
                drawRoundRect(c, Offset(left + h * cw + gap / 2, top + d * ch + gap / 2), Size(cw - gap, ch - gap), CornerRadius(3f, 3f))
            }
        }
    }
}

@Composable
private fun ScatterChart(cars: List<TripEntity>, modifier: Modifier, onOpen: (Long) -> Unit) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val log = cars.any { it.distanceM > 50_000 }
    val maxKm = if (log) nice(cars.maxOf { it.distanceM } / 1000) else nice(cars.maxOf { it.distanceM } / 1000)
    val maxV = nice(cars.maxOf { it.avgSpeedKmh }.coerceAtLeast(10.0))
    val density = androidx.compose.ui.platform.LocalDensity.current
    fun pos(t: TripEntity, w: Float, h: Float, left: Float, bottom: Float): Offset {
        val k = (t.distanceM / 1000).coerceAtLeast(0.1)
        val fx = if (log) ((ln(k) - ln(0.5)) / (ln(maxKm) - ln(0.5))).coerceIn(0.0, 1.0) else k / maxKm
        return Offset(left + (fx * w).toFloat(), (h - bottom) * (1 - (t.avgSpeedKmh / maxV)).toFloat() + 4f)
    }
    Canvas(modifier.onSizeChanged { sz = it }.pointerInput(cars) {
        detectTapGestures { p ->
            val left = with(density) { AXIS_W.dp.toPx() }; val bottom = with(density) { 18.dp.toPx() }
            val w = sz.width - left - 8f
            cars.minByOrNull { (pos(it, w, sz.height.toFloat(), left, bottom) - p).getDistance() }
                ?.takeIf { (pos(it, w, sz.height.toFloat(), left, bottom) - p).getDistance() < with(density) { 24.dp.toPx() } }?.let { onOpen(it.id) }
        }
    }) {
        val left = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val w = size.width - left - 8f; val h = size.height
        for (k in 0..4) {
            val y = (h - bottom) * (1 - k / 4f) + 4f
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, "${(maxV * k / 4).roundToInt()}", left - 4.dp.toPx(), y, muted, alignEnd = true)
        }
        val ticks = if (log) listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0).filter { it <= maxKm } else (0..4).map { maxKm * it / 4 }
        ticks.forEach { v ->
            val fx = if (log) (ln(v) - ln(0.5)) / (ln(maxKm) - ln(0.5)) else v / maxKm
            label(tm, n0(v.roundToInt()) + if (v == ticks.last()) " km" else "", left + (fx * w).toFloat(), h - 7.dp.toPx(), muted, center = true, size = 8)
        }
        label(tm, "Ø km/h", left + 2.dp.toPx(), 8.dp.toPx(), muted, size = 8)
        cars.forEach { t ->
            val c = if (t.category == CATEGORY_BUSINESS) VIOLET else GREEN
            drawCircle(c.copy(alpha = 0.8f), 4.5.dp.toPx(), pos(t, w, h, left, bottom))
        }
    }
}

@Composable
private fun StyleChart(weeks: List<Analytics.Week>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val left = AXIS_W.dp.toPx(); val right = AXIS_W.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        val maxR = nice(max(weeks.maxOf { it.per100 }, weeks.maxOf { it.speedingPct }))
        for (k in 0..4) {
            val y = top + h * (1 - k / 4f)
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            label(tm, "${k * 25}", left - 4.dp.toPx(), y, GREEN, alignEnd = true)
            label(tm, n1(maxR * k / 4), left + w + 4.dp.toPx(), y, muted)
        }
        val slot = w / weeks.size; val bw = (slot * 0.38f).coerceAtLeast(1f)
        val path = Path(); var started = false
        weeks.forEachIndexed { i, wk ->
            val x = left + i * slot + slot / 2
            val h1 = (wk.per100 / maxR * h).toFloat(); val h2 = (wk.speedingPct / maxR * h).toFloat()
            if (h1 > 0) drawRect(ROSE.copy(alpha = .6f), Offset(x - bw, top + h - h1), Size(bw, h1))
            if (h2 > 0) drawRect(AMBER.copy(alpha = .6f), Offset(x, top + h - h2), Size(bw, h2))
            wk.score?.let { sc -> val y = top + h * (1 - sc / 100f); if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y) }
            if (weeks.size <= 8 || i % (weeks.size / 6 + 1) == 0) label(tm, wk.label.substringBefore(" /"), x, top + h + 9.dp.toPx(), muted, center = true, size = 8)
        }
        drawPath(path, GREEN, style = Stroke(2.dp.toPx()))
    }
}

// ---------------- triediteľná tabuľka ----------------

private class TCol<T>(
    val title: String, val width: Dp, val start: Boolean = false, val bold: Boolean = false,
    val text: (T) -> String, val key: (T) -> Any, val color: (T) -> Color? = { null },
)

/** Tabuľka s pevným prvým stĺpcom, posúvaním do strany, triedením ťuknutím na nadpis, súčtami a exportom CSV. */
@Composable
private fun <T> SortableTable(
    rows: List<T>, cols: List<TCol<T>>, initialSort: Int, initialDesc: Boolean, footer: List<String>? = null,
    limit: Int? = null, onRowClick: ((T) -> Unit)? = null, note: String? = null, csvName: String, rowHeight: Dp = 38.dp,
) {
    val ctx = LocalContext.current
    var sortCol by remember { mutableIntStateOf(initialSort) }
    var desc by remember { mutableStateOf(initialDesc) }
    var showAll by remember { mutableStateOf(false) }
    val collator = remember { Collator.getInstance(SKL) }
    val sorted = remember(rows, sortCol, desc) {
        val cmp = Comparator<T> { a, b ->
            val x = cols[sortCol].key(a); val y = cols[sortCol].key(b)
            if (x is Double && y is Double) x.compareTo(y) else collator.compare(x.toString(), y.toString())
        }
        rows.sortedWith(if (desc) cmp.reversed() else cmp)
    }
    val shown = if (limit != null && !showAll) sorted.take(limit) else sorted
    val hScroll = rememberScrollState()
    val headBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)
    val zebra = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f)

    @Composable
    fun Cell(text: String, col: TCol<T>, bold: Boolean = col.bold, color: Color? = null, height: Dp = rowHeight) {
        Box(Modifier.width(col.width).height(height).padding(horizontal = 6.dp), contentAlignment = if (col.start) Alignment.CenterStart else Alignment.CenterEnd) {
            Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (col.start) TextAlign.Start else TextAlign.End, lineHeight = 13.sp)
        }
    }
    @Composable
    fun Header(i: Int) {
        val col = cols[i]
        Row(Modifier.width(col.width).height(40.dp).clickable { if (sortCol == i) desc = !desc else { sortCol = i; desc = !col.start } }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (col.start) Arrangement.Start else Arrangement.End) {
            Text(col.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 2,
                color = if (sortCol == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (col.start) TextAlign.Start else TextAlign.End)
            if (sortCol == i) Icon(if (desc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }

    Column {
        Row {
            // pevný prvý stĺpec
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                Box(Modifier.background(headBg)) { Header(0) }
                shown.forEachIndexed { r, row ->
                    Box(Modifier.background(if (r % 2 == 1) zebra else Color.Transparent).then(if (onRowClick != null) Modifier.clickable { onRowClick(row) } else Modifier)) {
                        Cell(cols[0].text(row), cols[0], color = if (onRowClick != null) MaterialTheme.colorScheme.primary else null)
                    }
                }
                if (footer != null) { HorizontalDivider(); Cell(footer[0], cols[0], bold = true) }
            }
            // ostatné stĺpce – posúvanie do strany
            Column(Modifier.horizontalScroll(hScroll)) {
                Row(Modifier.background(headBg)) { for (i in 1 until cols.size) Header(i) }
                shown.forEachIndexed { r, row ->
                    Row(Modifier.background(if (r % 2 == 1) zebra else Color.Transparent).then(if (onRowClick != null) Modifier.clickable { onRowClick(row) } else Modifier)) {
                        for (i in 1 until cols.size) Cell(cols[i].text(row), cols[i], color = cols[i].color(row))
                    }
                }
                if (footer != null) {
                    HorizontalDivider(Modifier.width(cols.drop(1).fold(0.dp) { a, c -> a + c.width }))
                    Row { for (i in 1 until cols.size) Cell(footer.getOrElse(i) { "" }, cols[i], bold = true) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(note ?: "${rows.size} riadkov", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (limit != null && rows.size > limit) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Menej" else "Všetky (${rows.size})") }
            IconButton(onClick = {
                val csv = buildString {
                    append(cols.joinToString(";") { "\"${it.title}\"" }).append("\r\n")
                    sorted.forEach { row -> append(cols.joinToString(";") { "\"" + it.text(row).replace("\n", " ").replace("\"", "\"\"") + "\"" }).append("\r\n") }
                    footer?.let { f -> append(f.joinToString(";") { "\"$it\"" }).append("\r\n") }
                }
                Reports.shareFile(ctx, "trasnik-$csvName.csv", "﻿" + csv, "text/csv")
            }) { Icon(Icons.Filled.TableChart, "Export do Excelu (CSV)") }
        }
    }
}
