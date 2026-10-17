package sk.cartracker.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.DrivingScore
import sk.cartracker.data.cost
import sk.cartracker.data.liters
import sk.cartracker.data.local.TYPE_CAR
import sk.cartracker.data.local.TYPE_WALK
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDateTime
import sk.cartracker.ui.formatDay
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatTime
import sk.cartracker.ui.map.MapRoute
import sk.cartracker.ui.map.RouteMap

fun scoreColor(score: Int) = when {
    score >= 90 -> Color(0xFF2E7D32)
    score >= 75 -> Color(0xFF7CB342)
    score >= 55 -> Color(0xFFF9A825)
    else -> Color(0xFFD32F2F)
}

fun TripEntity.routeTitle(): String {
    val a = startPlace ?: startAddress?.let { shortPlace(it) }
    val b = endPlace ?: endAddress?.let { shortPlace(it) }
    return if (a != null && b != null) "$a → $b" else "${formatTime(startedAt)} – ${endedAt?.let { formatTime(it) } ?: "…"}"
}

private fun shortPlace(a: String) = a.substringAfterLast(", ").ifBlank { a }

@Composable
fun TripsScreen(onOpenTrip: (Long) -> Unit, onNavigate: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmMerge by remember { mutableStateOf(false) }
    val trips by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val vehicleList by remember { c.vehicles.vehicles() }.collectAsStateWithLifecycle(emptyList())
    var allVehicles by remember { mutableStateOf<Map<String, VehicleEntity>>(emptyMap()) }
    LaunchedEffect(vehicleList) { allVehicles = c.vehicles.all().associateBy { it.uuid } }
    var filter by rememberSaveable { mutableStateOf("all") }
    var showAdd by remember { mutableStateOf(false) }
    if (showAdd) AddTripDialog(onDismiss = { showAdd = false }) { id -> showAdd = false; onOpenTrip(id) }

    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.DirectionsCar, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("Zatiaľ žiadne jazdy", style = MaterialTheme.typography.titleMedium)
                Text("Jazda sa zaznamená automaticky, keď nasadnete do auta a pohnete sa.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showAdd = true }) { Text("+ Pridať záznam ručne") }
            }
        }
        return
    }

    val shown = remember(trips, filter) {
        when (filter) {
            "all" -> trips
            TYPE_CAR -> trips.filter { !it.isWalk }
            TYPE_WALK -> trips.filter { it.isWalk }
            else -> trips.filter { !it.isWalk && it.category == filter }
        }
    }
    val grouped = remember(shown) { shown.groupBy { formatDay(it.startedAt) } }
    val selectedTrips = trips.filter { it.id in selected }

    fun toggle(t: TripEntity) {
        val first = selectedTrips.firstOrNull()
        selected = when {
            t.id in selected -> selected - t.id
            first != null && first.type != t.type -> {
                Toast.makeText(ctx, "Zlúčiť možno iba jazdy rovnakého typu (auto s autom, chôdzu s chôdzou)", Toast.LENGTH_SHORT).show()
                selected
            }
            else -> selected + t.id
        }
    }

    Column(Modifier.fillMaxSize()) {
    if (selected.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Zrušiť výber") }
                Column(Modifier.weight(1f)) {
                    Text("Vybrané: ${selected.size}", fontWeight = FontWeight.SemiBold)
                    Text(formatDistance(selectedTrips.sumOf { it.distanceM }), style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = selected.size >= 2, onClick = { confirmMerge = true }) {
                    Icon(Icons.AutoMirrored.Filled.CallMerge, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Zlúčiť")
                }
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "filter") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.AssistChip(onClick = { showAdd = true }, label = { Text("+ Pridať") })
                androidx.compose.material3.AssistChip(onClick = { onNavigate("timeline") }, label = { Text("🕒 Časová os") })
                FilterChip(filter == "all", { filter = "all" }, { Text("Všetko") })
                FilterChip(filter == TYPE_CAR, { filter = TYPE_CAR }, { Text("Auto") },
                    leadingIcon = { Icon(Icons.Filled.DirectionsCar, null, Modifier.size(16.dp)) })
                FilterChip(filter == TYPE_WALK, { filter = TYPE_WALK }, { Text("Chôdza") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(16.dp)) })
                FilterChip(filter == CATEGORY_BUSINESS, { filter = CATEGORY_BUSINESS }, { Text("Služobné") },
                    leadingIcon = { Icon(Icons.Filled.BusinessCenter, null, Modifier.size(16.dp)) })
                FilterChip(filter == CATEGORY_PRIVATE, { filter = CATEGORY_PRIVATE }, { Text("Súkromné") },
                    leadingIcon = { Icon(Icons.Filled.Home, null, Modifier.size(16.dp)) })
            }
        }
        grouped.forEach { (day, list) ->
            item(key = "h_$day") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(day.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Text(formatDistance(list.sumOf { it.distanceM }), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.id }) { t ->
                TripRow(
                    t, s, allVehicles[t.vehicleUuid], selected = t.id in selected,
                    onClick = { if (selected.isNotEmpty()) toggle(t) else onOpenTrip(t.id) },
                    onLongClick = { toggle(t) },
                )
            }
        }
        if (selected.isEmpty()) item(key = "hint") {
            Text("Tip: podržaním jazdy ich môžete označiť a zlúčiť do jednej.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
    }

    if (confirmMerge && selectedTrips.size >= 2) {
        MergeDialog(
            trips = selectedTrips,
            vehicles = allVehicles,
            onDismiss = { confirmMerge = false },
            onConfirm = {
                confirmMerge = false
                scope.launch {
                    runCatching { c.trips.merge(selectedTrips) }
                        .onSuccess { id ->
                            selected = emptySet()
                            if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                            Toast.makeText(ctx, "Jazdy boli zlúčené", Toast.LENGTH_SHORT).show()
                            onOpenTrip(id)
                        }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Zlúčenie zlyhalo", Toast.LENGTH_LONG).show() }
                }
            },
        )
    }
}

/** Potvrdenie zlúčenia so súhrnom a upozorneniami (dlhá prestávka, rôzne vozidlá). */
@Composable
private fun MergeDialog(trips: List<TripEntity>, vehicles: Map<String, VehicleEntity>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val sorted = trips.sortedBy { it.startedAt }
    val first = sorted.first()
    val end = sorted.maxOf { it.endedAt ?: it.startedAt }
    val maxGap = sorted.zipWithNext { a, b -> b.startedAt - (a.endedAt ?: a.startedAt) }.maxOrNull() ?: 0L
    val vehicleSet = sorted.mapNotNull { it.vehicleUuid }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.CallMerge, null) },
        title = { Text("Zlúčiť ${trips.size} ${if (first.isWalk) "záznamy chôdze" else "jazdy"}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${formatDateTime(first.startedAt)} – ${formatTime(end)}")
                Text("Spolu: ${formatDistance(sorted.sumOf { it.distanceM })}" +
                    if (first.isWalk) " · ${sorted.sumOf { it.steps }} krokov" else "", fontWeight = FontWeight.SemiBold)
                Text("Z: ${first.startAddress ?: "—"}", style = MaterialTheme.typography.bodySmall)
                Text("Do: ${sorted.maxBy { it.endedAt ?: it.startedAt }.endAddress ?: "—"}", style = MaterialTheme.typography.bodySmall)
                if (maxGap > 2 * 3_600_000L) Text("⚠️ Medzi jazdami je prestávka ${formatDuration(maxGap)}.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (vehicleSet.size > 1) Text("⚠️ Jazdy majú rôzne vozidlá – zlúčená jazda bude patriť vozidlu " +
                    "${vehicles[first.vehicleUuid]?.label ?: "prvej jazdy"} (dá sa zmeniť v detaile).",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Vzdialenosť, čas, udalosti a kroky sa sčítajú; ak je niektorá jazda služobná, zlúčená bude služobná. " +
                    "Zlúčenie sa nedá vrátiť späť.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Zlúčiť") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TripRow(
    t: TripEntity, s: AppSettings, vehicle: VehicleEntity?, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        t.isWalk -> Icons.AutoMirrored.Filled.DirectionsWalk
                        t.category == CATEGORY_BUSINESS -> Icons.Filled.BusinessCenter
                        t.triggerSource == "bluetooth" -> Icons.Filled.Bluetooth
                        t.triggerSource == "manual" -> Icons.Filled.TouchApp
                        else -> Icons.Filled.DirectionsCar
                    }, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.routeTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (t.isWalk) "${formatTime(t.startedAt)} · ${formatDuration((t.endedAt ?: t.startedAt) - t.startedAt)} · ${t.steps} krokov"
                    else "${formatTime(t.startedAt)} · ${formatDuration((t.endedAt ?: t.startedAt) - t.startedAt)} · ${formatMoney(t.cost(vehicle, s))}" +
                        (vehicle?.let { " · ${it.plate.ifBlank { it.name }}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDistance(t.distanceM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    t.score?.let {
                        Text("$it", style = MaterialTheme.typography.labelSmall, color = Color.White,
                            modifier = Modifier.background(scoreColor(it), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                        Spacer(Modifier.size(6.dp))
                    }
                    Icon(if (t.synced) Icons.Filled.CloudDone else Icons.Filled.CloudUpload, if (t.synced) "Synchronizované" else "Čaká na odoslanie",
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val trip by remember { c.trips.trip(tripId) }.collectAsStateWithLifecycle(null)
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    var points by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    var odometer by remember { mutableStateOf<Double?>(null) }
    val vehicles by remember { c.vehicles.vehicles() }.collectAsStateWithLifecycle(emptyList())
    var vehicle by remember { mutableStateOf<VehicleEntity?>(null) }
    LaunchedEffect(trip?.vehicleUuid, vehicles) { vehicle = c.vehicles.byUuid(trip?.vehicleUuid) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var newPlace by remember { mutableStateOf<sk.cartracker.data.local.PlaceEntity?>(null) }
    var mergeNext by remember { mutableStateOf<TripEntity?>(null) }
    var splitOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(tripId, reload) { points = c.trips.points(tripId) }
    LaunchedEffect(trip?.startedAt, vehicle) { trip?.let { odometer = c.trips.odometerAt(it.startedAt, vehicle) } }

    val routes = remember(points, trip?.startAddress, trip?.endAddress) {
        listOf(MapRoute(points, trip?.startAddress, trip?.endAddress))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(trip?.let { formatDateTime(it.startedAt) } ?: "Jazda") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            actions = {
                IconButton(onClick = { editOpen = true }) { Icon(Icons.Filled.Edit, "Upraviť jazdu") }
                IconButton(onClick = {
                    val t = trip ?: return@IconButton
                    scope.launch {
                        mergeNext = c.trips.nextTrip(t)
                        if (mergeNext == null) Toast.makeText(ctx, "Po tejto jazde nenasleduje žiadna ďalšia", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.CallMerge, "Zlúčiť s nasledujúcou") }
                IconButton(onClick = { splitOpen = true }) { Icon(Icons.Filled.ContentCut, "Rozdeliť jazdu") }
                androidx.compose.foundation.layout.Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "Ďalšie") }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                DropdownMenuItem(text = { Text("Zdieľať") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = {
                    moreMenu = false
                    val t = trip ?: return@DropdownMenuItem
                    val text = buildString {
                        appendLine("Jazda ${formatDateTime(t.startedAt)}")
                        t.startAddress?.let { appendLine("Z: $it") }
                        t.endAddress?.let { appendLine("Do: $it") }
                        appendLine("${formatDistance(t.distanceM)}, ${formatDuration((t.endedAt ?: t.startedAt) - t.startedAt)}, Ø ${t.avgSpeedKmh.toInt()} km/h")
                        points.lastOrNull()?.let { append("https://www.openstreetmap.org/?mlat=${it.lat}&mlon=${it.lng}#map=15/${it.lat}/${it.lng}") }
                    }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Zdieľať jazdu"))
                })
                DropdownMenuItem(text = { Text("Zmazať") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { moreMenu = false; confirmDelete = true })
                }
                }
            },
        )
    }) { pad ->
        val t = trip ?: return@Scaffold
        if (splitOpen) SplitTripDialog(t, points, onDismiss = { splitOpen = false }) { splitOpen = false; reload++ }
        if (editOpen) EditTripDialog(t, onDismiss = { editOpen = false }) { editOpen = false; reload++ }
        if (fullscreen) {
            RouteMap(routes, Modifier.padding(pad).fillMaxSize(), colorBySpeed = !t.isWalk, showEvents = !t.isWalk,
                fullscreen = true, onToggleFullscreen = { fullscreen = false })
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            RouteMap(routes, Modifier.fillMaxWidth().height(380.dp), colorBySpeed = !t.isWalk, showEvents = !t.isWalk,
                onToggleFullscreen = { fullscreen = true })

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Trasa
                SectionCard("Trasa") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PlaceRow("A", Color(0xFF2E7D32), listOfNotNull(t.startPlace, t.startAddress).joinToString(" · ").ifEmpty { "Adresa nezistená" }, formatDateTime(t.startedAt))
                        PlaceRow("B", Color(0xFFC62828), listOfNotNull(t.endPlace, t.endAddress).joinToString(" · ").ifEmpty { "Adresa nezistená" }, t.endedAt?.let { formatDateTime(it) } ?: "")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val sLat = t.startLat; val sLng = t.startLng; val eLat = t.endLat; val eLng = t.endLng
                            if (t.startPlace == null && sLat != null && sLng != null) TextButton(onClick = {
                                newPlace = c.places.newPlace("", sk.cartracker.data.local.PLACE_OTHER, sLat, sLng)
                            }) { Text("Štart ako miesto") }
                            if (t.endPlace == null && eLat != null && eLng != null) TextButton(onClick = {
                                newPlace = c.places.newPlace("", sk.cartracker.data.local.PLACE_OTHER, eLat, eLng)
                            }) { Text("Cieľ ako miesto") }
                        }
                    }
                }
                // Vozidlo jazdy – dá sa zmeniť aj dodatočne
                if (!t.isWalk) TripVehicleCard(vehicle, vehicles) { v ->
                    scope.launch {
                        c.trips.setVehicle(t, v.uuid)
                        if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                    }
                }
                // Chôdza
                if (t.isWalk) SectionCard("Chôdza") {
                    val mins = ((t.endedAt ?: t.startedAt) - t.startedAt) / 60_000.0
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        StatBox("Kroky", "${t.steps}", Modifier.weight(1f))
                        StatBox("Vzdialenosť", formatDistance(t.distanceM), Modifier.weight(1f))
                        StatBox("Trvanie", formatDuration((t.endedAt ?: t.startedAt) - t.startedAt), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        StatBox("Tempo", if (t.distanceM > 50) paceText(mins / (t.distanceM / 1000.0)) else "–", Modifier.weight(1f))
                        StatBox("Priemer", String.format(java.util.Locale("sk"), "%.1f km/h", t.avgSpeedKmh), Modifier.weight(1f))
                        StatBox("Kroky/min", if (mins > 0) "${(t.steps / mins).toInt()}" else "–", Modifier.weight(1f))
                    }
                }
                // Štatistiky
                if (!t.isWalk) SectionCard("Štatistiky") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Vzdialenosť", formatDistance(t.distanceM), Modifier.weight(1f))
                            StatBox("Trvanie", formatDuration((t.endedAt ?: t.startedAt) - t.startedAt), Modifier.weight(1f))
                            StatBox("Priemer", "${t.avgSpeedKmh.toInt()} km/h", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Maximum", "${t.maxSpeedKmh.toInt()} km/h", Modifier.weight(1f))
                            StatBox("Palivo", String.format(java.util.Locale("sk"), "%.1f %s", t.liters(vehicle, s), vehicle?.unit ?: "l"), Modifier.weight(1f))
                            StatBox("Náklady", formatMoney(t.cost(vehicle, s)), Modifier.weight(1f))
                        }
                    }
                }
                // Graf rýchlosti
                if (!t.isWalk && points.isNotEmpty()) SectionCard("Rýchlosť počas jazdy") {
                    sk.cartracker.ui.SpeedChart(points, t.maxSpeedKmh)
                }
                // Štýl jazdy
                if (t.score != null) SectionCard("Štýl jazdy") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(t.score, Modifier.size(84.dp))
                        Spacer(Modifier.size(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(DrivingScore.label(t.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scoreColor(t.score))
                            Text("Prudké brzdenia: ${t.hardBrakes}")
                            Text("Prudké zrýchlenia: ${t.hardAccels}")
                            Text("Nad limitom: ${formatDuration(t.speedingSec * 1000L).let { if (t.speedingSec in 1..59) "${t.speedingSec} s" else it }}")
                        }
                    }
                }
                // Kniha jázd
                if (!t.isWalk) SpeedingCard(points)
                if (!t.isWalk) TripLogbookCard(t, odometer) { category, note ->
                    scope.launch {
                        c.trips.updateMeta(t, category, note)
                        if (category != t.category) c.settings.update { sk.cartracker.data.TripClassifier.learn(it, t, category) }
                        if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                    }
                }
                Text(
                    "Spustenie: " + when (t.triggerSource) { "bluetooth" -> "Bluetooth auta"; "manual" -> "ručne"; else -> "automaticky" } +
                        " · bodov GPS: ${points.size} · " + if (t.synced) "uložené na serveri ✅" else "čaká na synchronizáciu",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    newPlace?.let { p ->
        PlaceDialog(p, onDismiss = { newPlace = null }, onSave = { saved ->
            newPlace = null
            scope.launch {
                c.places.save(saved)
                trip?.let { t -> c.trips.updateTripRaw(c.places.applyToTrip(t, c.places.list(), s, setCategory = false).copy(synced = false)) }
                if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                Toast.makeText(ctx, "Miesto „${saved.name}“ uložené", Toast.LENGTH_SHORT).show()
            }
        }, onDelete = null)
    }

    val next = mergeNext
    val cur = trip
    if (next != null && cur != null) {
        MergeDialog(
            trips = listOf(cur, next), vehicles = emptyMap(),
            onDismiss = { mergeNext = null },
            onConfirm = {
                mergeNext = null
                scope.launch {
                    runCatching { c.trips.merge(listOf(cur, next)) }
                        .onSuccess {
                            reload++
                            if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                            Toast.makeText(ctx, "Zlúčené s nasledujúcou jazdou", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Zlúčenie zlyhalo", Toast.LENGTH_LONG).show() }
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Zmazať jazdu?") },
            text = { Text("Jazda sa zmaže z telefónu aj zo servera.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    trip?.let { scope.launch { c.trips.delete(it); onBack() } }
                }) { Text("Zmazať") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušiť") } },
        )
    }
}

@Composable
private fun PlaceRow(letter: String, color: Color, address: String, time: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(color, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Text(letter, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(address, fontWeight = FontWeight.Medium)
            if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ScoreRing(score: Int, modifier: Modifier) {
    val color = scoreColor(score)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension * 0.11f
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * score / 100f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text("/ 100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripLogbookCard(t: TripEntity, odometer: Double?, onSave: (String, String) -> Unit) {
    var note by remember(t.id, t.note) { mutableStateOf(t.note) }
    SectionCard("Kniha jázd") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = t.category == CATEGORY_PRIVATE, onClick = { onSave(CATEGORY_PRIVATE, note) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Filled.Home, null, Modifier.size(18.dp)) },
                ) { Text("Súkromná") }
                SegmentedButton(
                    selected = t.category == CATEGORY_BUSINESS, onClick = { onSave(CATEGORY_BUSINESS, note) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Filled.BusinessCenter, null, Modifier.size(18.dp)) },
                ) { Text("Služobná") }
            }
            OutlinedTextField(note, { note = it.take(500) }, label = { Text("Účel jazdy / poznámka") },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            PurposeChips(note, t.endPlace ?: t.endAddress) { p -> note = p; onSave(t.category, p) }
            if (note != t.note) TextButton(onClick = { onSave(t.category, note) }) { Text("Uložiť poznámku") }
            odometer?.let {
                Text("Tachometer: ${it.toLong()} km → ${(it + t.distanceM / 1000).toLong()} km",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun paceText(minPerKm: Double): String {
    val m = minPerKm.toInt()
    val sec = ((minPerKm - m) * 60).toInt()
    return String.format(java.util.Locale("sk"), "%d:%02d min/km", m, sec)
}

@Composable
private fun TripVehicleCard(current: VehicleEntity?, vehicles: List<VehicleEntity>, onChange: (VehicleEntity) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SectionCard("Vozidlo") {
        Box {
            Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Text(current?.label ?: "Nepriradené", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text("Zmeniť", color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vehicles.forEach { v ->
                    DropdownMenuItem(text = { Text(v.label) }, onClick = { open = false; if (v.uuid != current?.uuid) onChange(v) })
                }
            }
        }
    }
}
