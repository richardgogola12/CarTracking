package sk.cartracker.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.local.PLACE_CLIENT
import sk.cartracker.data.local.PLACE_HOME
import sk.cartracker.data.local.PLACE_KINDS
import sk.cartracker.data.local.PLACE_WORK
import sk.cartracker.data.local.PlaceEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.map.MapTiles
import java.util.Locale

fun placeIcon(kind: String) = when (kind) {
    PLACE_HOME -> Icons.Filled.Home
    PLACE_WORK -> Icons.Filled.Work
    PLACE_CLIENT -> Icons.Filled.BusinessCenter
    else -> Icons.Filled.Place
}

fun categoryLabel(c: String) = when (c) { CATEGORY_BUSINESS -> "služobné"; CATEGORY_PRIVATE -> "súkromné"; else -> "typ jazdy nemení" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val places by remember { c.places.places() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<PlaceEntity?>(null) }
    var applyDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miesta") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
                actions = { IconButton(onClick = { applyDialog = true }) { Icon(Icons.Filled.Sync, "Použiť na existujúce jazdy") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = c.places.newPlace("", PLACE_HOME, 48.15, 17.11).copy(lat = Double.NaN) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Pridať miesto") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Keď jazda začne alebo skončí v okruhu miesta, v zozname sa zobrazí jeho názov (napr. Domov → Práca), " +
                    "doplní sa účel jazdy a podľa nastavenia miesta aj typ jazdy (súkromná / služobná).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (places.isEmpty()) item {
                Text("Zatiaľ žiadne miesta. Pridajte napr. Domov a Prácu.", Modifier.padding(vertical = 24.dp))
            }
            items(places, key = { it.uuid }) { p ->
                Card(onClick = { editing = p }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(placeIcon(p.kind), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text("okruh ${p.radiusM} m · ${categoryLabel(p.category)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        PlaceDialog(p, onDismiss = { editing = null }, onSave = { saved ->
            scope.launch {
                c.places.save(saved)
                if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                editing = null
                applyDialog = true
            }
        }, onDelete = if (p.id != 0L) { { scope.launch { c.places.delete(p); editing = null }; Unit } } else null)
    }

    if (applyDialog) {
        AlertDialog(
            onDismissRequest = { applyDialog = false },
            title = { Text("Použiť miesta na existujúce jazdy?") },
            text = { Text("Doplnia sa názvy miest pri všetkých doterajších jazdách. Typ jazdy a účel môžete nechať tak, ako ste ich nastavili.") },
            confirmButton = {
                TextButton(onClick = {
                    applyDialog = false
                    scope.launch {
                        val n = c.places.applyToAll(s, setCategory = false)
                        Toast.makeText(ctx, "Upravených jázd: $n", Toast.LENGTH_SHORT).show()
                        if (n > 0 && s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                    }
                }) { Text("Iba názvy") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { applyDialog = false }) { Text("Nie") }
                    TextButton(onClick = {
                        applyDialog = false
                        scope.launch {
                            val n = c.places.applyToAll(s, setCategory = true)
                            Toast.makeText(ctx, "Upravených jázd: $n", Toast.LENGTH_SHORT).show()
                            if (n > 0 && s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                        }
                    }) { Text("Aj typ jazdy") }
                }
            },
        )
    }
}

/** Úprava miesta. Poloha: aktuálna GPS, výber na mape, alebo predvyplnená (z jazdy). */
@Composable
fun PlaceDialog(initial: PlaceEntity, onDismiss: () -> Unit, onSave: (PlaceEntity) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var category by remember { mutableStateOf(initial.category) }
    var radius by remember { mutableStateOf(initial.radiusM) }
    var lat by remember { mutableStateOf(initial.lat) }
    var lng by remember { mutableStateOf(initial.lng) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasLocation = !lat.isNaN()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Nové miesto" else "Upraviť miesto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Názov (napr. Domov, Firma XY)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PLACE_KINDS.forEach { (k, l) ->
                        FilterChip(kind == k, {
                            kind = k
                            if (name.isBlank() || PLACE_KINDS.any { it.second == name }) name = if (k == "other") name else l
                            if (category.isEmpty()) category = if (k == PLACE_CLIENT) CATEGORY_BUSINESS else category
                        }, { Text(l) }, leadingIcon = { Icon(placeIcon(k), null, Modifier.size(16.dp)) })
                    }
                }
                Text("Jazdy z / do tohto miesta", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Nemeniť typ", CATEGORY_PRIVATE to "Súkromné", CATEGORY_BUSINESS to "Služobné").forEach { (k, l) ->
                        FilterChip(category == k, { category = k }, { Text(l) })
                    }
                }
                SliderRow("Okruh miesta", radius, 50..1000, 1, "m") { radius = it }
                Text(if (hasLocation) String.format(Locale.US, "Poloha: %.5f, %.5f", lat, lng) else "Poloha nie je zadaná",
                    color = if (hasLocation) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            currentLocation(ctx)?.let { lat = it.first; lng = it.second; error = null }
                                ?: run { error = "Polohu sa nepodarilo zistiť – skúste vonku alebo vyberte na mape" }
                        }
                    }) { Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Tu som") }
                    OutlinedButton(onClick = { picking = true }) { Icon(Icons.Filled.Map, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Na mape") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Odstrániť miesto", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    name.isBlank() -> "Zadajte názov"
                    !hasLocation -> "Zadajte polohu (Tu som alebo Na mape)"
                    else -> null
                }
                if (error == null) onSave(initial.copy(name = name.trim(), kind = kind, category = category, radiusM = radius, lat = lat, lng = lng))
            }) { Text("Uložiť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )

    if (picking) {
        MapPicker(
            start = if (hasLocation) GeoPoint(lat, lng) else null,
            onDismiss = { picking = false },
            onPick = { p -> lat = p.latitude; lng = p.longitude; picking = false; error = null },
        )
    }
}

@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Pair<Double, Double>? = try {
    LocationServices.getFusedLocationProviderClient(ctx)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        ?.let { it.latitude to it.longitude }
} catch (e: Exception) { null }

/** Výber bodu na mape: posuňte mapu tak, aby bol bod pod zameriavačom v strede. */
@Composable
fun MapPicker(start: GeoPoint?, onDismiss: () -> Unit, onPick: (GeoPoint) -> Unit) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(if (start != null) 17.0 else 8.0)
            controller.setCenter(start ?: GeoPoint(48.67, 19.70))
        }
    }
    DisposableEffect(Unit) {
        map.setTileSource(MapTiles.source(sk.cartracker.data.MapStyle.DETAILED, false))
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            // zameriavač
            Box(Modifier.align(Alignment.Center).size(22.dp).background(Color(0x551565C0), CircleShape))
            Box(Modifier.align(Alignment.Center).size(8.dp).background(Color(0xFF1565C0), CircleShape))
            Text("Posuňte mapu tak, aby bolo miesto v strede", Modifier.align(Alignment.TopCenter)
                .padding(top = 16.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp))
            Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))) { Text("Zrušiť") }
                Button(onClick = { val c = map.mapCenter; onPick(GeoPoint(c.latitude, c.longitude)) }) { Text("Použiť toto miesto") }
            }
        }
    }
}
