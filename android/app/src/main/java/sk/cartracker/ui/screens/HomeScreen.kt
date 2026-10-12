package sk.cartracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
fun HomeScreen(onOpenPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val state by TrackingState.state.collectAsState()
    val live by TrackingState.live.collectAsState()
    val trips by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    val pending by remember { c.trips.pendingCount() }.collectAsStateWithLifecycle(0)

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
            DriveState.IDLE -> Quad(MaterialTheme.colorScheme.outline, Icons.Filled.LocalParking, "Čakám na jazdu",
                if (s.autoDetection) "Automatická detekcia je zapnutá" else "Automatická detekcia je VYPNUTÁ")
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
                if (state == DriveState.DRIVING && l != null) {
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
                if (state == DriveState.DRIVING) {
                    Button(
                        onClick = { TrackingService.signal(ctx, TrackingService.ACTION_STOP) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.size(8.dp)); Text("Ukončiť jazdu") }
                } else {
                    OutlinedButton(
                        onClick = {
                            val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (ok) TrackingService.startManual(ctx) else onOpenPermissions()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.size(8.dp)); Text("Spustiť jazdu ručne") }
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
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                StatBox("Dnes", formatDistance(today.sumOf { it.distanceM }), Modifier.weight(1f))
                StatBox("Tento týždeň", formatDistance(week.sumOf { it.distanceM }), Modifier.weight(1f))
                StatBox("Jázd spolu", trips.size.toString(), Modifier.weight(1f))
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
                if (s.carBtAddress.isNotEmpty()) Text("Bluetooth auta (${s.carBtName}): " +
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
