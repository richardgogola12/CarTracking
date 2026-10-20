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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import sk.cartracker.data.cost
import sk.cartracker.data.liters
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.Reports
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDay
import sk.cartracker.ui.formatTime
import androidx.compose.foundation.clickable
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatMonth
import sk.cartracker.ui.map.MapRoute
import sk.cartracker.ui.map.RouteMap
import java.util.Calendar
import java.util.Locale

private enum class Period(val label: String) { DAY("Deň"), WEEK("Týždeň"), MONTH("Mesiac"), YEAR("Rok") }

/** Posun obdobia (0 = aktuálne), ktoré obsahuje deň [ts] – pre výber dátumu ťuknutím na názov obdobia. */
private fun offsetFor(period: Period, ts: Long): Int {
    fun noon(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
    fun monday(t: Long) = noon(t).apply { firstDayOfWeek = Calendar.MONDAY; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }.timeInMillis
    val now = System.currentTimeMillis()
    val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = ts }
    return when (period) {
        Period.DAY -> Math.round((noon(ts).timeInMillis - noon(now).timeInMillis) / 86_400_000.0).toInt()
        Period.WEEK -> Math.round((monday(ts) - monday(now)) / (7 * 86_400_000.0)).toInt()
        Period.MONTH -> (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + b.get(Calendar.MONTH) - a.get(Calendar.MONTH)
        Period.YEAR -> b.get(Calendar.YEAR) - a.get(Calendar.YEAR)
    }.coerceAtMost(0)
}

private data class Range(val from: Long, val to: Long, val title: String, val buckets: List<Pair<String, LongRange>>)

private fun range(period: Period, offset: Int): Range {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val buckets = mutableListOf<Pair<String, LongRange>>()
    return when (period) {
        Period.DAY -> {
            cal.add(Calendar.DAY_OF_MONTH, offset)
            val from = cal.timeInMillis
            repeat(24) { h ->
                val s = cal.timeInMillis; cal.add(Calendar.HOUR_OF_DAY, 1)
                buckets += (if (h % 6 == 0) "$h:00" else "") to (s until cal.timeInMillis)
            }
            val title = when (offset) { 0 -> "Dnes · "; -1 -> "Včera · "; else -> "" } + formatDay(from)
            Range(from, cal.timeInMillis, title, buckets)
        }
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
fun StatsScreen(onNavigate: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val all by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    val vehicleList by remember { c.vehicles.vehicles() }.collectAsStateWithLifecycle(emptyList())
    var vehicleMap by remember { mutableStateOf<Map<String, VehicleEntity>>(emptyMap()) }
    LaunchedEffect(vehicleList) { vehicleMap = c.vehicles.all().associateBy { it.uuid } }

    val allRefuels by remember { c.refuels.allRefuels() }.collectAsStateWithLifecycle(emptyList())
    var walkMode by rememberSaveable { mutableStateOf(false) }
    var vehicleFilter by rememberSaveable { mutableStateOf("") }   // "" = všetky vozidlá
    var vehicleMenu by remember { mutableStateOf(false) }
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val r = remember(period, offset) { range(period, offset) }
    val trips = remember(all, r, walkMode, vehicleFilter) {
        all.filter { it.startedAt >= r.from && it.startedAt < r.to && it.isWalk == walkMode &&
            (walkMode || vehicleFilter.isEmpty() || it.vehicleUuid == vehicleFilter) }.sortedBy { it.startedAt }
    }
    val selectedVehicle = vehicleMap[vehicleFilter]
    // Kniha jázd sa vedie po vozidlách: vybrané vozidlo, prípadne jediné existujúce
    val logbookVehicle = selectedVehicle ?: vehicleList.singleOrNull()

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
            SegmentedButton(!walkMode, { walkMode = false }, SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Filled.DirectionsCar, null, Modifier.size(18.dp)) }) { Text("Auto") }
            SegmentedButton(walkMode, { walkMode = true }, SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(18.dp)) }) { Text("Chôdza") }
        }
        if (!walkMode && vehicleList.size > 1) Box {
            OutlinedButton(onClick = { vehicleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedVehicle?.label ?: "Všetky vozidlá", Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = vehicleMenu, onDismissRequest = { vehicleMenu = false }) {
                DropdownMenuItem(text = { Text("Všetky vozidlá") }, onClick = { vehicleFilter = ""; vehicleMenu = false })
                vehicleList.forEach { v ->
                    DropdownMenuItem(text = { Text(v.label) }, onClick = { vehicleFilter = v.uuid; vehicleMenu = false })
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(period == p, { period = p; offset = 0 }, SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(p.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Predchádzajúce") }
            // ťuknutím na názov obdobia sa vyberie dátum
            Text(r.title, Modifier.weight(1f).clickable { pickDate(ctx, minOf(r.from, System.currentTimeMillis())) { d -> offset = offsetFor(period, d) } },
                textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { offset++ }, enabled = offset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nasledujúce") }
        }

        val total = trips.sumOf { it.distanceM }
        val time = trips.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt }

        if (walkMode) SectionCard("Podrobný prehľad chôdze") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kroky po dňoch, rok v krokoch, kedy chodíte, dni v týždni, tempo, pracovný čas, série a tabuľky po dňoch, týždňoch a mesiacoch.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onNavigate("walkstats") }) { Text("Otvoriť") }
            }
        }

        if (walkMode) {
            val stepsTotal = trips.sumOf { it.steps }
            val activeDays = trips.map { formatDate(it.startedAt) }.distinct().size
            SectionCard("Súhrn chôdze") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Kroky", String.format(Locale("sk"), "%,d", stepsTotal), Modifier.weight(1f))
                        StatBox("Vzdialenosť", formatDistance(total), Modifier.weight(1f))
                        StatBox("Čas", formatDuration(time), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Záznamov", "${trips.size}", Modifier.weight(1f))
                        StatBox("Kroky / deň", if (activeDays > 0) "${stepsTotal / activeDays}" else "–", Modifier.weight(1f))
                        StatBox("Tempo", if (total > 100) paceText(time / 60_000.0 / (total / 1000.0)) else "–", Modifier.weight(1f))
                    }
                }
            }
            SectionCard("Kroky") {
                BarChart(r.buckets.map { (label, range) -> label to trips.filter { it.startedAt in range }.sumOf { it.steps }.toDouble() },
                    Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 12.dp, vertical = 8.dp), unit = "krokov")
            }
        } else {
            val business = trips.filter { it.category == CATEGORY_BUSINESS }.sumOf { it.distanceM }
            val liters = trips.sumOf { it.liters(vehicleMap[it.vehicleUuid], s) }
            val cost = trips.sumOf { it.cost(vehicleMap[it.vehicleUuid], s) }
            SectionCard("Súhrn") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Vzdialenosť", formatDistance(total), Modifier.weight(1f))
                        StatBox("Čas za volantom", formatDuration(time), Modifier.weight(1f))
                        StatBox("Jázd", "${trips.size}", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Ø rýchlosť", if (time > 0) "${(total / 1000 / (time / 3_600_000.0)).toInt()} km/h" else "–", Modifier.weight(1f))
                        StatBox("Palivo", String.format(Locale("sk"), "%.1f", liters), Modifier.weight(1f))
                        StatBox("Náklady", formatMoney(cost), Modifier.weight(1f))
                    }
                }
            }

            val periodRefuels = allRefuels.filter { it.ts >= r.from && it.ts < r.to && (vehicleFilter.isEmpty() || it.vehicleUuid == vehicleFilter) }
            if (periodRefuels.isNotEmpty()) SectionCard("Tankovanie (skutočné náklady)") {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    StatBox("Tankovaní", "${periodRefuels.size}", Modifier.weight(1f))
                    StatBox("Natankované", String.format(Locale("sk"), "%.1f", periodRefuels.sumOf { it.liters }), Modifier.weight(1f))
                    StatBox("Zaplatené", formatMoney(periodRefuels.sumOf { it.totalPrice }), Modifier.weight(1f))
                }
            }

            SectionCard("Najazdené km") {
                BarChart(r.buckets.map { (label, range) -> label to trips.filter { it.startedAt in range }.sumOf { it.distanceM } / 1000.0 },
                    Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 12.dp, vertical = 8.dp), unit = "km")
            }

            if (vehicleFilter.isEmpty() && vehicleList.size > 1 && total > 0) SectionCard("Podľa vozidiel") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trips.groupBy { it.vehicleUuid }.entries.sortedByDescending { e -> e.value.sumOf { it.distanceM } }.forEach { (uuid, list) ->
                        val km = list.sumOf { it.distanceM }
                        Column {
                            Row {
                                Text(vehicleMap[uuid]?.label ?: "Nepriradené", Modifier.weight(1f), fontSize = 13.sp)
                                Text("${formatDistance(km)} · ${formatMoney(list.sumOf { it.cost(vehicleMap[it.vehicleUuid], s) })}", fontSize = 13.sp)
                            }
                            Box(Modifier.fillMaxWidth((km / total).toFloat()).height(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                        }
                    }
                }
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
        }

        // Deň: zoznam záznamov a časová os
        if (period == Period.DAY) SectionCard(if (walkMode) "Chôdza v tento deň" else "Jazdy v tento deň") {
            Column(Modifier.padding(horizontal = 4.dp)) {
                if (trips.isEmpty()) Text("Žiadne záznamy", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                trips.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { onNavigate("trip/${t.id}") }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatTime(t.startedAt)}–${t.endedAt?.let { formatTime(it) } ?: ""}", fontWeight = FontWeight.SemiBold)
                            Text("${t.startPlace ?: t.startAddress ?: "?"} → ${t.endPlace ?: t.endAddress ?: "?"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatDistance(t.distanceM), fontWeight = FontWeight.Bold)
                            Text(if (t.isWalk) "${t.steps} krokov" else if (t.category == sk.cartracker.data.local.CATEGORY_BUSINESS) "služobná" else "súkromná",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedButton(onClick = { onNavigate("timeline?d=${r.from}") }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("🕒 Časová os dňa") }
            }
        }

        // Kalendár aktivity (mesiac obdobia) a rekordy
        SectionCard(if (walkMode) "Kalendár krokov" else "Kalendár jázd") {
            val monthTs = if (period == Period.MONTH) r.from else minOf(System.currentTimeMillis(), r.to - 1)
            ActivityCalendar(all.filter { walkMode || vehicleFilter.isEmpty() || it.vehicleUuid == vehicleFilter || it.isWalk }, walkMode, monthTs, s.dailyStepGoal)
        }
        RecordsCard(if (walkMode) all else all.filter { vehicleFilter.isEmpty() || it.vehicleUuid == vehicleFilter }, walkMode, s)

        if (walkMode) {
            val work = remember(all, r, s) { workWalking(all, s, r.from, r.to) }
            SectionCard("Pracovný výkaz chôdze") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hours = "%02d:%02d–%02d:%02d".format(s.workFromMin / 60, s.workFromMin % 60, s.workToMin / 60, s.workToMin % 60)
                    Text("Chôdza v pracovnom čase ($hours, dni podľa Nastavenia → Chôdza) za „${r.title}“.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Kroky", String.format(Locale("sk"), "%,d", work.sumOf { it.steps }), Modifier.weight(1f))
                        StatBox("Vzdialenosť", formatDistance(work.sumOf { it.distanceM }), Modifier.weight(1f))
                        StatBox("Dní", "${work.size}", Modifier.weight(1f))
                    }
                    if (work.isNotEmpty()) StatBox("Priemer na pracovný deň", String.format(Locale("sk"), "%,d krokov · %s",
                        work.sumOf { it.steps } / work.size, formatDistance(work.sumOf { it.distanceM } / work.size)), Modifier.fillMaxWidth())
                    Button(enabled = work.isNotEmpty() && activity != null, modifier = Modifier.fillMaxWidth(), onClick = {
                        val html = Reports.workWalkHtml(work, r.title, c.session.user()?.name ?: "", s)
                        activity?.let { Reports.printHtml(it, "Výkaz chôdze ${r.title}", html = html) }
                    }) { Icon(Icons.Filled.Print, null); Spacer(Modifier.size(8.dp)); Text("Výkaz – tlač / PDF") }
                    OutlinedButton(enabled = work.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                        Reports.shareFile(ctx, "vykaz-chodze-${r.title.replace(' ', '-')}.csv", Reports.workWalkCsv(work), "text/csv")
                    }) { Icon(Icons.Filled.TableChart, null); Spacer(Modifier.size(8.dp)); Text("Export do Excelu (CSV)") }
                }
            }
        }

        SectionCard("Analýzy") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Vývoj za 12 mesiacov, kedy jazdíte, dĺžky jázd, štýl po týždňoch a tabuľky po mesiacoch, cieľoch a dňoch so zoraďovaním.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onNavigate("analytics") }) { Text("Otvoriť") }
            }
        }

        if (!walkMode) SectionCard("Časté trasy") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Opakované cesty (napr. Domov → Práca): priemerný čas a kedy je najlepšie vyraziť.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onNavigate("routes") }) { Text("Otvoriť") }
            }
        }

        if (routes.any { it.points.isNotEmpty() }) SectionCard(if (walkMode) "Mapa chôdze" else "Mapa jázd") {
            RouteMap(routes, Modifier.fillMaxWidth().height(320.dp).padding(top = 4.dp), colorBySpeed = false, showEvents = false)
        }

        if (!walkMode) SectionCard("Kniha jázd a export") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val prevMonth = remember { sk.cartracker.ui.MonthlyLogbook.previousMonth() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Minulý mesiac (${prevMonth.title}), všetky vozidlá:", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.FilledTonalButton(enabled = activity != null, onClick = {
                        scope.launch { activity?.let { if (!sk.cartracker.ui.MonthlyLogbook.print(it)) android.widget.Toast.makeText(ctx, "Minulý mesiac nie sú jazdy autom", android.widget.Toast.LENGTH_SHORT).show() } }
                    }) { Text("PDF") }
                    OutlinedButton(onClick = {
                        scope.launch { if (!sk.cartracker.ui.MonthlyLogbook.shareCsv(ctx)) android.widget.Toast.makeText(ctx, "Minulý mesiac nie sú jazdy autom", android.widget.Toast.LENGTH_SHORT).show() }
                    }) { Text("Excel") }
                }
                HorizontalDivider()
                if (logbookVehicle == null) {
                    Text("Kniha jázd sa vedie pre každé vozidlo zvlášť – vyberte vozidlo vyššie.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${logbookVehicle.label}: ${trips.size} jázd za obdobie „${r.title}“.", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = trips.isNotEmpty() && activity != null && logbookVehicle != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        val odo = trips.firstOrNull()?.let { c.trips.odometerAt(it.startedAt, logbookVehicle) }
                        val html = Reports.logbookHtml(trips, s, r.title, c.session.user()?.name ?: "", odo, logbookVehicle)
                        activity?.let { Reports.printHtml(it, "Kniha jázd ${r.title}", html = html, landscape = true) }
                    }
                }) { Icon(Icons.Filled.Print, null); Spacer(Modifier.size(8.dp)); Text("Kniha jázd – tlač / PDF") }
                OutlinedButton(enabled = trips.isNotEmpty() && logbookVehicle != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        val odo = trips.firstOrNull()?.let { c.trips.odometerAt(it.startedAt, logbookVehicle) }
                        val name = "kniha-jazd-${(logbookVehicle?.plate?.ifBlank { null } ?: logbookVehicle?.name ?: "").replace(' ', '-')}-${r.title.replace(' ', '-')}.csv"
                        Reports.shareFile(ctx, name, Reports.logbookCsv(trips, s, odo, logbookVehicle), "text/csv")
                    }
                }) { Icon(Icons.Filled.TableChart, null); Spacer(Modifier.size(8.dp)); Text("Export do Excelu (CSV)") }
                if (logbookVehicle != null) OutlinedButton(enabled = activity != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        runCatching { c.trips.syncAll(); c.vehicles.sharedTrips(logbookVehicle.uuid, r.from, r.to) }
                            .onSuccess { list ->
                                val html = Reports.sharedLogbookHtml(list, logbookVehicle, r.title)
                                activity?.let { Reports.printHtml(it, "Spoločná kniha jázd ${r.title}", html = html, landscape = true) }
                            }
                            .onFailure { android.widget.Toast.makeText(ctx, c.api.errorMessage(it), android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text("👥 Spoločná kniha jázd (všetci vodiči)") }
            }
        }

        // Cestovné náhrady za služobné jazdy
        if (!walkMode && logbookVehicle != null) {
            val reimb = remember(trips, logbookVehicle, s) { Reports.reimbursements(trips, logbookVehicle, s) }
            SectionCard("Cestovné náhrady") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logbookVehicle.ratePerKmMilli == 0) Text("Pri vozidle nie je zadaná sadzba náhrady €/km (Vozidlá → upraviť). Počíta sa iba náhrada za palivo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Služobné km", formatDistance(reimb.sumOf { it.km } * 1000), Modifier.weight(1f))
                        StatBox("Za km", formatMoney(reimb.sumOf { it.basic }), Modifier.weight(1f))
                        StatBox("Za palivo", formatMoney(reimb.sumOf { it.fuel }), Modifier.weight(1f))
                    }
                    StatBox("Na vyplatenie spolu", formatMoney(reimb.sumOf { it.total }), Modifier.fillMaxWidth())
                    Button(enabled = reimb.isNotEmpty() && activity != null, modifier = Modifier.fillMaxWidth(), onClick = {
                        val html = Reports.reimbursementHtml(reimb, logbookVehicle, r.title, c.session.user()?.name ?: "")
                        activity?.let { Reports.printHtml(it, "Cestovné náhrady ${r.title}", html = html) }
                    }) { Icon(Icons.Filled.Print, null); Spacer(Modifier.size(8.dp)); Text("Výkaz náhrad – tlač / PDF") }
                    OutlinedButton(enabled = reimb.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                        Reports.shareFile(ctx, "nahrady-${r.title.replace(' ', '-')}.csv", Reports.reimbursementCsv(reimb, logbookVehicle), "text/csv")
                    }) { Icon(Icons.Filled.TableChart, null); Spacer(Modifier.size(8.dp)); Text("Export do Excelu (CSV)") }
                }
            }
        }
    }
}

@Composable
private fun BarChart(data: List<Pair<String, Double>>, modifier: Modifier, unit: String = "km") {
    val max = (data.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("max ${max.toInt()} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
