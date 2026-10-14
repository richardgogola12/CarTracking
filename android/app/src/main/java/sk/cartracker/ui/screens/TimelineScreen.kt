package sk.cartracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.cost
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.ui.Reports
import sk.cartracker.ui.formatDay
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatTime
import java.util.Calendar
import java.util.Locale

/** Jeden úsek dňa: jazda, chôdza alebo pobyt na mieste medzi nimi. */
data class DaySegment(val kind: Kind, val from: Long, val to: Long, val trip: TripEntity?, val place: String?) {
    enum class Kind { CAR, WALK, STAY }
    val ms get() = to - from
}

fun daySegments(trips: List<TripEntity>, dayStartTs: Long): List<DaySegment> {
    val dayEnd = Calendar.getInstance().apply { timeInMillis = dayStartTs; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    val list = trips.filter { (it.endedAt ?: it.startedAt) > dayStartTs && it.startedAt < dayEnd }.sortedBy { it.startedAt }
    val out = mutableListOf<DaySegment>()
    list.forEachIndexed { i, t ->
        if (i > 0) {
            val prev = list[i - 1]
            val gapFrom = prev.endedAt ?: prev.startedAt
            if (t.startedAt - gapFrom >= 2 * 60_000L)
                out += DaySegment(DaySegment.Kind.STAY, gapFrom, t.startedAt, null, prev.endPlace ?: prev.endAddress ?: t.startPlace ?: t.startAddress)
        }
        out += DaySegment(if (t.isWalk) DaySegment.Kind.WALK else DaySegment.Kind.CAR, t.startedAt, t.endedAt ?: t.startedAt, t, null)
    }
    return out
}

private val CAR_COLOR = Color(0xFF2E7D32)
private val WALK_COLOR = Color(0xFF1565C0)
private val STAY_COLOR = Color(0xFF8D6E63)

/** Časová os dňa – jazdy, chôdza a pobyty za sebou, s tlačou a exportom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(onBack: () -> Unit, onOpenTrip: (Long) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val c = ctx.container
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    var day by rememberSaveable { mutableLongStateOf(dayStart(System.currentTimeMillis())) }
    var trips by remember { mutableStateOf<List<TripEntity>>(emptyList()) }
    var vehicles by remember { mutableStateOf<Map<String, VehicleEntity>>(emptyMap()) }
    val all by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(day, all) {
        trips = c.trips.tripsBetween(day - 86_400_000L, day + 2 * 86_400_000L)
        vehicles = c.vehicles.all().associateBy { it.uuid }
    }
    val segments = remember(trips, day) { daySegments(trips, day) }
    fun shift(d: Int) { day = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_MONTH, d) }.timeInMillis.let(::dayStart) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Časová os dňa") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            actions = {
                IconButton(enabled = segments.isNotEmpty() && activity != null, onClick = {
                    activity?.let { Reports.printHtml(it, "Časová os ${formatDay(day)}", html = Reports.timelineHtml(segments, day, vehicles, s, c.session.user()?.name ?: "")) }
                }) { Icon(Icons.Filled.Print, "Tlač / PDF") }
                IconButton(enabled = segments.isNotEmpty(), onClick = {
                    Reports.shareFile(ctx, "casova-os-${sk.cartracker.ui.formatDate(day).replace(" ", "")}.csv", Reports.timelineCsv(segments, vehicles), "text/csv")
                }) { Icon(Icons.Filled.TableChart, "Export CSV") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Predchádzajúci deň") }
                    Text(formatDay(day).replaceFirstChar { it.uppercase() }, Modifier.weight(1f).clickable { pickDate(ctx, day) { day = dayStart(it) } },
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { shift(1) }, enabled = day < dayStart(System.currentTimeMillis())) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ďalší deň") }
                }
                val cars = segments.filter { it.kind == DaySegment.Kind.CAR }
                val walks = segments.filter { it.kind == DaySegment.Kind.WALK }
                SectionCard("Súhrn dňa") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Autom", formatDistance(cars.sumOf { it.trip!!.distanceM }), Modifier.weight(1f))
                            StatBox("Za volantom", formatDuration(cars.sumOf { it.ms }), Modifier.weight(1f))
                            StatBox("Jázd", "${cars.size}", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Pešo", formatDistance(walks.sumOf { it.trip!!.distanceM }), Modifier.weight(1f))
                            StatBox("Kroky", String.format(Locale("sk"), "%,d", walks.sumOf { it.trip!!.steps }), Modifier.weight(1f))
                            StatBox("Náklady", formatMoney(cars.sumOf { it.trip!!.cost(vehicles[it.trip.vehicleUuid], s) }), Modifier.weight(1f))
                        }
                    }
                }
                if (segments.isEmpty()) Text("V tento deň nie sú žiadne jazdy ani chôdza.", Modifier.padding(24.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            }
            items(segments) { seg -> SegmentRow(seg, vehicles, onOpenTrip) }
        }
    }
}

@Composable
private fun SegmentRow(seg: DaySegment, vehicles: Map<String, VehicleEntity>, onOpenTrip: (Long) -> Unit) {
    val color = when (seg.kind) { DaySegment.Kind.CAR -> CAR_COLOR; DaySegment.Kind.WALK -> WALK_COLOR; DaySegment.Kind.STAY -> STAY_COLOR }
    val t = seg.trip
    Row(Modifier.fillMaxWidth().then(if (t != null) Modifier.clickable { onOpenTrip(t.id) } else Modifier)) {
        Column(Modifier.width(54.dp)) {
            Text(formatTime(seg.from), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(formatTime(seg.to), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // čiara s bodom
        Column(Modifier.width(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Box(Modifier.width(if (seg.kind == DaySegment.Kind.STAY) 2.dp else 4.dp).height(if (seg.kind == DaySegment.Kind.STAY) 40.dp else 58.dp)
                .background(color.copy(alpha = if (seg.kind == DaySegment.Kind.STAY) 0.35f else 0.8f)))
        }
        Column(Modifier.weight(1f).padding(start = 6.dp, bottom = 10.dp)) {
            when (seg.kind) {
                DaySegment.Kind.STAY -> {
                    Text("🅿️ " + (seg.place ?: "Na mieste"), style = MaterialTheme.typography.bodyMedium)
                    Text("pobyt ${formatDuration(seg.ms)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    val title = (if (seg.kind == DaySegment.Kind.CAR) "🚗 " else "🚶 ") +
                        listOf(t!!.startPlace ?: t.startAddress?.substringBefore(","), t.endPlace ?: t.endAddress?.substringBefore(","))
                            .map { it ?: "?" }.joinToString(" → ")
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(buildString {
                        append(formatDistance(t.distanceM)).append(" · ").append(formatDuration(seg.ms))
                        if (seg.kind == DaySegment.Kind.CAR) {
                            append(" · Ø ").append(t.avgSpeedKmh.toInt()).append(" km/h")
                            vehicles[t.vehicleUuid]?.let { append(" · ").append(it.name) }
                            if (t.category == CATEGORY_BUSINESS) append(" · služobná")
                        } else append(" · ").append(String.format(Locale("sk"), "%,d", t.steps)).append(" krokov")
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
