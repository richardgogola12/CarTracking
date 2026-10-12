package sk.cartracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.Reports
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatMonth
import sk.cartracker.ui.map.MapRoute
import sk.cartracker.ui.map.RouteMap
import java.util.Calendar
import java.util.Locale

private enum class Period(val label: String) { WEEK("Týždeň"), MONTH("Mesiac"), YEAR("Rok") }

private data class Range(val from: Long, val to: Long, val title: String, val buckets: List<Pair<String, LongRange>>)

private fun range(period: Period, offset: Int): Range {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val buckets = mutableListOf<Pair<String, LongRange>>()
    return when (period) {
        Period.WEEK -> {
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.add(Calendar.WEEK_OF_YEAR, offset)
            val names = listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")
            val from = cal.timeInMillis
            repeat(7) { i ->
                val s = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH, 1)
                buckets += names[i] to (s until cal.timeInMillis)
            }
            Range(from, cal.timeInMillis, "${formatDate(from)} – ${formatDate(cal.timeInMillis - 1)}", buckets)
        }
        Period.MONTH -> {
            cal.set(Calendar.DAY_OF_MONTH, 1); cal.add(Calendar.MONTH, offset)
            val from = cal.timeInMillis
            val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            repeat(days) { i ->
                val s = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH, 1)
                buckets += (if ((i + 1) % 5 == 0 || i == 0) "${i + 1}" else "") to (s until cal.timeInMillis)
            }
            Range(from, cal.timeInMillis, formatMonth(from), buckets)
        }
        Period.YEAR -> {
            cal.set(Calendar.DAY_OF_YEAR, 1); cal.add(Calendar.YEAR, offset)
            val from = cal.timeInMillis
            val year = cal.get(Calendar.YEAR)
            val names = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
            repeat(12) { i ->
                val s = cal.timeInMillis; cal.add(Calendar.MONTH, 1)
                buckets += names[i] to (s until cal.timeInMillis)
            }
            Range(from, cal.timeInMillis, "Rok $year", buckets)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val all by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val r = remember(period, offset) { range(period, offset) }
    val trips = remember(all, r) { all.filter { it.startedAt >= r.from && it.startedAt < r.to }.sortedBy { it.startedAt } }

    // Trasy na mape obdobia (max. 40 jázd, body preriedené)
    var routes by remember { mutableStateOf<List<MapRoute>>(emptyList()) }
    LaunchedEffect(trips) {
        routes = trips.takeLast(40).map { t ->
            val pts = c.trips.points(t.id)
            val step = (pts.size / 300).coerceAtLeast(1)
            MapRoute(pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }, t.startAddress, t.endAddress)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Štatistiky", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(period == p, { period = p; offset = 0 }, SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(p.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Predchádzajúce") }
            Text(r.title, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { offset++ }, enabled = offset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nasledujúce") }
        }

        val total = trips.sumOf { it.distanceM }
        val time = trips.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt }
        val business = trips.filter { it.category == CATEGORY_BUSINESS }.sumOf { it.distanceM }
        SectionCard("Súhrn") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    StatBox("Vzdialenosť", formatDistance(total), Modifier.weight(1f))
                    StatBox("Čas za volantom", formatDuration(time), Modifier.weight(1f))
                    StatBox("Jázd", "${trips.size}", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    StatBox("Ø rýchlosť", if (time > 0) "${(total / 1000 / (time / 3_600_000.0)).toInt()} km/h" else "–", Modifier.weight(1f))
                    StatBox("Palivo", String.format(Locale("sk"), "%.1f l", s.fuelLiters(total)), Modifier.weight(1f))
                    StatBox("Náklady", formatMoney(s.fuelCost(total)), Modifier.weight(1f))
                }
            }
        }

        SectionCard("Najazdené km") {
            BarChart(r.buckets.map { (label, range) -> label to trips.filter { it.startedAt in range }.sumOf { it.distanceM } / 1000.0 },
                Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 12.dp, vertical = 8.dp))
        }

        if (total > 0) SectionCard("Súkromné vs. služobné") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val share = (business / total).toFloat()
                Box(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF43A047), RoundedCornerShape(7.dp))) {
                    Box(Modifier.fillMaxWidth(share).height(14.dp).background(Color(0xFF1565C0), RoundedCornerShape(7.dp)))
                }
                Row {
                    Text("● Služobné ${formatDistance(business)}", color = Color(0xFF1565C0), modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("● Súkromné ${formatDistance(total - business)}", color = Color(0xFF43A047), fontSize = 13.sp)
                }
            }
        }

        val scored = trips.mapNotNull { it.score }
        if (scored.isNotEmpty()) SectionCard("Štýl jazdy") {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(scored.average().toInt(), Modifier.size(80.dp))
                Spacer(Modifier.size(16.dp))
                Column {
                    Text("Priemerné skóre z ${scored.size} jázd")
                    Text("Prudké brzdenia: ${trips.sumOf { it.hardBrakes }}", style = MaterialTheme.typography.bodySmall)
                    Text("Prudké zrýchlenia: ${trips.sumOf { it.hardAccels }}", style = MaterialTheme.typography.bodySmall)
                    trips.maxByOrNull { it.distanceM }?.let {
                        Text("Najdlhšia jazda: ${formatDistance(it.distanceM)} (${formatDate(it.startedAt)})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (routes.any { it.points.isNotEmpty() }) SectionCard("Mapa jázd") {
            RouteMap(routes, Modifier.fillMaxWidth().height(320.dp).padding(top = 4.dp), colorBySpeed = false, showEvents = false)
        }

        SectionCard("Kniha jázd a export") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Exportuje ${trips.size} jázd za obdobie „${r.title}“.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = trips.isNotEmpty() && activity != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        val odo = trips.firstOrNull()?.let { c.trips.odometerAt(it.startedAt, s) }
                        val html = Reports.logbookHtml(trips, s, r.title, c.session.user()?.name ?: "", odo)
                        activity?.let { Reports.printHtml(it, "Kniha jázd ${r.title}", html = html, landscape = true) }
                    }
                }) { Icon(Icons.Filled.Print, null); Spacer(Modifier.size(8.dp)); Text("Kniha jázd – tlač / PDF") }
                OutlinedButton(enabled = trips.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        val odo = trips.firstOrNull()?.let { c.trips.odometerAt(it.startedAt, s) }
                        Reports.shareFile(ctx, "kniha-jazd-${r.title.replace(' ', '-')}.csv", Reports.logbookCsv(trips, s, odo), "text/csv")
                    }
                }) { Icon(Icons.Filled.TableChart, null); Spacer(Modifier.size(8.dp)); Text("Export do Excelu (CSV)") }
            }
        }
    }
}

@Composable
private fun BarChart(data: List<Pair<String, Double>>, modifier: Modifier) {
    val max = (data.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("max ${max.toInt()} km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = data.size
            val slot = size.width / n
            val bw = slot * 0.62f
            for (k in 0..3) {
                val y = size.height * k / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            data.forEachIndexed { i, (_, v) ->
                val h = (v / max * size.height).toFloat()
                if (h > 0f) drawRoundRect(
                    barColor, Offset(i * slot + (slot - bw) / 2, size.height - h), Size(bw, h),
                    CornerRadius(bw / 4, bw / 4),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            data.forEach { (label, _) ->
                Text(label, Modifier.weight(1f), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
