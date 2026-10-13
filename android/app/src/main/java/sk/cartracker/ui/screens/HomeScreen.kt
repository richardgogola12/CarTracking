package sk.cartracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.RuntimeState
import sk.cartracker.service.DriveState
import sk.cartracker.service.TrackingService
import sk.cartracker.service.TrackingState
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDateTime
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.permissionItems
import java.util.Calendar

@Composable
fun HomeScreen(onOpenPermissions: () -> Unit, onOpenVehicles: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val state by TrackingState.state.collectAsState()
    val live by TrackingState.live.collectAsState()
    val trips by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    val pending by remember { c.trips.pendingCount() }.collectAsStateWithLifecycle(0)
    val vehicles by remember { c.vehicles.vehicles() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var vehicleMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { c.vehicles.ensureDefault() }

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    val missing = remember(resumeTick, s) { permissionItems(ctx, s).filter { it.required && !it.granted } }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) { while (state != DriveState.IDLE) { now = System.currentTimeMillis(); delay(1000) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ahoj, ${c.session.user()?.name ?: ""}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---- Aktívne vozidlo – prepínateľné kedykoľvek, aj počas jazdy ----
        val activeVehicle = vehicles.firstOrNull { it.uuid == s.activeVehicleUuid } ?: vehicles.firstOrNull()
        Box {
            Card(onClick = { vehicleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Vozidlo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(activeVehicle?.label ?: "Pridajte vozidlo", fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Filled.ArrowDropDown, "Zmeniť vozidlo")
                }
            }
            DropdownMenu(expanded = vehicleMenu, onDismissRequest = { vehicleMenu = false }) {
                vehicles.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        leadingIcon = { if (v.uuid == activeVehicle?.uuid) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { vehicleMenu = false; scope.launch { c.vehicles.setActive(v.uuid) } },
                    )
                }
                HorizontalDivider()
                activeVehicle?.let { av ->
                    DropdownMenuItem(text = { Text("Tankovanie – ${av.plate.ifBlank { av.name }}") }, leadingIcon = { Icon(Icons.Filled.LocalGasStation, null) },
                        onClick = { vehicleMenu = false; onNavigate("refuels/${av.uuid}") })
                }
                DropdownMenuItem(text = { Text("Spravovať vozidlá…") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                    onClick = { vehicleMenu = false; onOpenVehicles() })
            }
        }

        if (missing.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), onClick = onOpenPermissions) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Automatická detekcia nemusí fungovať", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Chýba: " + missing.joinToString { it.title }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // ---- Stav ----
        val (color, icon, title, subtitle) = when (state) {
            DriveState.DRIVING -> Quad(Color(0xFF2E7D32), Icons.Filled.DirectionsCar, "Režim jazdy ZAPNUTÝ",
                if (live?.manual == true) "Ručne spustená jazda" else "Automaticky rozpoznaná jazda")
            DriveState.CANDIDATE -> Quad(Color(0xFFEF6C00), Icons.Filled.HourglassTop, "Overujem jazdu…",
                "Čakám, kým rýchlosť potvrdí, že ide o auto")
            DriveState.WALKING -> Quad(Color(0xFF1565C0), Icons.AutoMirrored.Filled.DirectionsWalk, "Chôdza sa zaznamenáva",
                if (live?.manual == true) "Ručne spustená chôdza" else "Automaticky rozpoznaná chôdza")
            DriveState.IDLE -> Quad(MaterialTheme.colorScheme.outline, Icons.Filled.LocalParking, "Čakám na jazdu",
                listOf(
                    if (s.autoDetection) "auto: zapnuté" else "auto: VYPNUTÉ",
                    if (!s.walkTracking) "chôdza: vypnutá" else if (s.walkWorkHoursOnly) "chôdza: v pracovnom čase" else "chôdza: zapnutá",
                ).joinToString(" · "))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(48.dp), tint = color)
                }
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)

                val l = live
                if (state == DriveState.WALKING && l != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Kroky", "${l.steps}", Modifier.weight(1f))
                        StatBox("Vzdialenosť", formatDistance(l.distanceM), Modifier.weight(1f))
                        StatBox("Čas", formatDuration(now - l.startedAt), Modifier.weight(1f))
                    }
                }
                if (state == DriveState.DRIVING && l != null) {
                    l.vehicleName?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    if (s.roadLimits) {
                        Spacer(Modifier.height(12.dp))
                        SpeedLimitRow(l)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Vzdialenosť", formatDistance(l.distanceM), Modifier.weight(1f))
                        StatBox("Čas", formatDuration(now - l.startedAt), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Rýchlosť", "${l.speedKmh.toInt()} km/h", Modifier.weight(1f))
                        StatBox("Max", "${l.maxSpeedKmh.toInt()} km/h", Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                val hasLoc = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
                if (state == DriveState.DRIVING || state == DriveState.WALKING) {
                    Button(
                        onClick = { TrackingService.signal(ctx, TrackingService.ACTION_STOP) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.size(8.dp)); Text(if (state == DriveState.WALKING) "Ukončiť chôdzu" else "Ukončiť jazdu") }
                }
                if (state != DriveState.DRIVING) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (hasLoc()) TrackingService.startManual(ctx) else onOpenPermissions() },
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Filled.DirectionsCar, null); Spacer(Modifier.size(6.dp)); Text("Jazda") }
                        if (state != DriveState.WALKING) OutlinedButton(
                            onClick = { if (hasLoc()) TrackingService.startManualWalk(ctx) else onOpenPermissions() },
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null); Spacer(Modifier.size(6.dp)); Text("Chôdza") }
                    }
                }
            }
        }

        // ---- Pripomienky, ktoré sa blížia ----
        val reminders by remember { c.reminders.reminders() }.collectAsStateWithLifecycle(emptyList())
        var dueList by remember { mutableStateOf<List<Pair<sk.cartracker.data.local.ReminderEntity, sk.cartracker.data.ReminderStatus>>>(emptyList()) }
        LaunchedEffect(reminders, resumeTick) {
            dueList = reminders.filter { !it.done }.map { it to c.reminders.status(it) }
                .filter { it.second.state == sk.cartracker.data.ReminderState.SOON || it.second.state == sk.cartracker.data.ReminderState.OVERDUE }
        }
        if (dueList.isNotEmpty()) {
            Card(onClick = { onNavigate("reminders") }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🔔 Pripomienky", fontWeight = FontWeight.SemiBold)
                    dueList.take(4).forEach { (r, st) ->
                        Text("${r.title}: ${st.text()}", color = reminderColor(st.state), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ---- Kde som zaparkoval ----
        val parking = remember(resumeTick, state) { RuntimeState.parking(ctx) }
        if (state == DriveState.IDLE && parking != null) {
            SectionCard("Kde som zaparkoval") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalParking, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text(parking.address ?: String.format(java.util.Locale.US, "%.5f, %.5f", parking.lat, parking.lng), fontWeight = FontWeight.SemiBold)
                            Text("zaparkované ${formatDateTime(parking.ts)} (pred ${formatDuration(System.currentTimeMillis() - parking.ts)})",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val uri = android.net.Uri.parse("geo:0,0?q=${parking.lat},${parking.lng}(Moje auto)")
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                        }) { Icon(Icons.Filled.Navigation, null); Spacer(Modifier.size(6.dp)); Text("Nájsť auto") }
                        OutlinedButton(onClick = { RuntimeState.clearParking(ctx); resumeTick++ }) { Text("Skryť") }
                    }
                }
            }
        }

        // ---- Štatistiky ----
        val startOfDay = remember(trips) { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis }
        val startOfWeek = startOfDay - ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7) * 86_400_000L
        val today = trips.filter { it.startedAt >= startOfDay }
        val week = trips.filter { it.startedAt >= startOfWeek }
        SectionCard("Prehľad") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🚗 Auto", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth()) {
                    StatBox("Dnes", formatDistance(today.filter { !it.isWalk }.sumOf { it.distanceM }), Modifier.weight(1f))
                    StatBox("Tento týždeň", formatDistance(week.filter { !it.isWalk }.sumOf { it.distanceM }), Modifier.weight(1f))
                    StatBox("Jázd spolu", trips.count { !it.isWalk }.toString(), Modifier.weight(1f))
                }
                if (s.walkTracking || trips.any { it.isWalk }) {
                    Text("🚶 Chôdza", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth()) {
                        StatBox("Kroky dnes", today.filter { it.isWalk }.sumOf { it.steps }.toString(), Modifier.weight(1f))
                        StatBox("Dnes", formatDistance(today.filter { it.isWalk }.sumOf { it.distanceM }), Modifier.weight(1f))
                        StatBox("Tento týždeň", formatDistance(week.filter { it.isWalk }.sumOf { it.distanceM }), Modifier.weight(1f))
                    }
                }
            }
        }

        // ---- Detekcia & synchronizácia ----
        SectionCard("Detekcia a synchronizácia") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val registered = remember(resumeTick) { RuntimeState.isDetectionRegistered(ctx) }
                Text("Rozpoznávanie aktivity: " + if (registered && s.autoDetection) "aktívne ✅" else "neaktívne ⚠️")
                val last = remember(resumeTick, state) { RuntimeState.lastActivity(ctx) }
                if (last != null) Text("Posledná zmena: $last (${formatDateTime(RuntimeState.lastActivityAt(ctx))})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vehicles.any { it.btAddress.isNotEmpty() }) Text("Bluetooth vozidla: " +
                    if (RuntimeState.isCarBtConnected(ctx)) "pripojené" else "odpojené", style = MaterialTheme.typography.bodySmall)
                Text("Čaká na odoslanie: $pending jázd", style = MaterialTheme.typography.bodySmall)
                val lastSync = remember(resumeTick, pending) { RuntimeState.lastSyncAt(ctx) }
                if (lastSync > 0) Text("Posledná synchronizácia: ${formatDateTime(lastSync)}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { SyncWorker.enqueueNow(ctx, wifiOnly = false) }) {
                    Icon(Icons.Filled.CloudSync, null); Spacer(Modifier.size(8.dp)); Text("Synchronizovať teraz")
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Dopravná značka s aktuálnym limitom + ručné prepnutie typu cesty (ak mapa nevie / je offline). */
@Composable
private fun SpeedLimitRow(l: sk.cartracker.service.LiveTrip) {
    val over = l.limitKmh != null && l.speedKmh > l.limitKmh
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).background(Color.White, CircleShape)
                .border(6.dp, Color(0xFFD32F2F), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(l.limitKmh?.toString() ?: "?", color = Color.Black, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(l.zone?.label?.replaceFirstChar { it.uppercase() } ?: "Zisťujem typ cesty…", fontWeight = FontWeight.SemiBold,
                color = if (over) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(l.roadName, if (l.zoneManual) "zvolené ručne" else "z mapy").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
    val override by TrackingState.zoneOverride.collectAsState()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(override == null, { TrackingState.zoneOverride.value = null }, { Text("Auto") })
        sk.cartracker.data.RoadZone.entries.forEach { z ->
            FilterChip(override == z, { TrackingState.zoneOverride.value = z }, { Text(z.label.replaceFirstChar { it.uppercase() }) })
        }
    }
}
