package sk.cartracker.ui.screens

import android.graphics.Color as AColor
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import sk.cartracker.container
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.formatDateTime
import sk.cartracker.ui.formatDay
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatTime

@Composable
fun TripsScreen(onOpenTrip: (Long) -> Unit) {
    val c = LocalContext.current.container
    val trips by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())

    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.DirectionsCar, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("Zatiaľ žiadne jazdy", style = MaterialTheme.typography.titleMedium)
                Text("Jazda sa zaznamená automaticky, keď nasadnete do auta a pohnete sa.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val grouped = remember(trips) { trips.groupBy { formatDay(it.startedAt) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        grouped.forEach { (day, list) ->
            item(key = "h_$day") {
                Text(day.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
            items(list, key = { it.id }) { TripRow(it) { onOpenTrip(it.id) } }
        }
    }
}

@Composable
private fun TripRow(t: TripEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (t.triggerSource) { "bluetooth" -> Icons.Filled.Bluetooth; "manual" -> Icons.Filled.TouchApp; else -> Icons.Filled.DirectionsCar },
                null, tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${formatTime(t.startedAt)} – ${t.endedAt?.let { formatTime(it) } ?: "…"}", fontWeight = FontWeight.SemiBold)
                Text("${formatDuration((t.endedAt ?: t.startedAt) - t.startedAt)} · Ø ${t.avgSpeedKmh.toInt()} km/h · max ${t.maxSpeedKmh.toInt()} km/h",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDistance(t.distanceM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(if (t.synced) Icons.Filled.CloudDone else Icons.Filled.CloudUpload, if (t.synced) "Synchronizované" else "Čaká na odoslanie",
                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val trip by remember { c.trips.trip(tripId) }.collectAsStateWithLifecycle(null)
    var points by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(tripId) { points = c.trips.points(tripId) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(trip?.let { formatDateTime(it.startedAt) } ?: "Jazda") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Zmazať") } },
        )
    }) { pad ->
        val t = trip ?: return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            TripMap(points, Modifier.fillMaxWidth().height(360.dp))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    StatBox("Vzdialenosť", formatDistance(t.distanceM), Modifier.weight(1f))
                    StatBox("Trvanie", formatDuration((t.endedAt ?: t.startedAt) - t.startedAt), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    StatBox("Priemer", "${t.avgSpeedKmh.toInt()} km/h", Modifier.weight(1f))
                    StatBox("Maximum", "${t.maxSpeedKmh.toInt()} km/h", Modifier.weight(1f))
                }
                Text("Začiatok: ${formatDateTime(t.startedAt)}")
                t.endedAt?.let { Text("Koniec: ${formatDateTime(it)}") }
                Text("Spustenie: " + when (t.triggerSource) { "bluetooth" -> "Bluetooth auta"; "manual" -> "ručne"; else -> "automaticky" })
                Text("Bodov GPS: ${points.size}")
                Text(if (t.synced) "Uložené na serveri ✅" else "Čaká na synchronizáciu so serverom")
            }
        }
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
private fun TripMap(points: List<PointEntity>, modifier: Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        mv.overlays.clear()
        val geo = points.map { GeoPoint(it.lat, it.lng) }
        if (geo.isEmpty()) return@AndroidView
        mv.overlays.add(Polyline(mv).apply {
            setPoints(geo)
            outlinePaint.color = AColor.rgb(21, 101, 192)
            outlinePaint.strokeWidth = 12f
        })
        mv.overlays.add(Marker(mv).apply { position = geo.first(); title = "Štart"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
        mv.overlays.add(Marker(mv).apply { position = geo.last(); title = "Cieľ"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
        mv.post {
            if (geo.size > 1) mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false)
            else mv.controller.setCenter(geo.first())
        }
        mv.invalidate()
    })
}
