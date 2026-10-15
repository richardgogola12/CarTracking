package sk.cartracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.DiagLog
import sk.cartracker.data.RuntimeState
import sk.cartracker.service.TrackingState
import sk.cartracker.ui.Reports
import sk.cartracker.ui.formatDay
import sk.cartracker.ui.formatTime

private val FILTERS = listOf(
    "Všetko" to emptySet(),
    "Detekcia" to setOf(DiagLog.SYSTEM, DiagLog.CHECK, DiagLog.GPS, DiagLog.BT),
    "Jazda" to setOf(DiagLog.DRIVE),
    "Chôdza" to setOf(DiagLog.WALK),
    "Problémy" to setOf(DiagLog.ERROR),
)

/** Diagnostika detekcie: stav podmienok a záznam udalostí – prečo sa jazda spustila / nespustila / ukončila. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val state by TrackingState.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<DiagLog.Entry>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            entries = DiagLog.read(ctx).asReversed()
            delay(3000)
        }
    }
    val shown = remember(entries, filter) { FILTERS[filter].second.let { f -> if (f.isEmpty()) entries else entries.filter { it.category in f } } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Diagnostika detekcie") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            actions = {
                IconButton(onClick = {
                    Reports.shareFile(ctx, "cartracking-diagnostika.txt", statusText(ctx, s) + "\n\n" + DiagLog.asText(entries.asReversed()), "text/plain")
                }) { Icon(Icons.Filled.Share, "Zdieľať") }
                IconButton(onClick = { DiagLog.clear(ctx); tick++ }) { Icon(Icons.Filled.Delete, "Vymazať") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Podmienky detekcie", fontWeight = FontWeight.Bold)
                        checks(ctx, s).forEach { (ok, text) ->
                            Text((if (ok) "✅ " else "⚠️ ") + text, style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        }
                        Text("Aktuálny stav: ${state.name} · posledná aktivita: ${RuntimeState.lastActivity(ctx) ?: "–"}" +
                            RuntimeState.lastActivityAt(ctx).takeIf { it > 0 }?.let { " (${formatTime(it)})" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                val days = remember(entries) { sk.cartracker.data.BatteryStats.days(ctx) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Batéria počas sledovania", fontWeight = FontWeight.Bold)
                        if (days.isEmpty()) Text("Zatiaľ bez údajov – zapíšu sa po prvej jazde alebo chôdzi.", style = MaterialTheme.typography.bodySmall)
                        days.take(7).forEach { d ->
                            val date = runCatching { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(d.day)!!.time }.getOrNull()
                            Text(
                                (date?.let { sk.cartracker.ui.formatShortDate(it) } ?: d.day) + ": GPS ${sk.cartracker.ui.formatDuration(d.trackedMs)}" +
                                    (if (d.measuredMs > 0) " · −${d.dropPct} %" else " · nabíjal sa") +
                                    (d.perHour?.let { String.format(java.util.Locale("sk"), " (≈ %.1f %%/h)", it) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("Úspora batérie (redšie body na diaľnici a pri státí): " + (if (s.adaptiveGps) "zapnutá" else "vypnutá") +
                            " · interval GPS ${s.gpsIntervalSec} s" + (if (s.highAccuracy) ", vysoká presnosť" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SwitchRow("Zaznamenávať diagnostiku", "Krátky záznam v telefóne (max. ~400 kB), nikam sa neposiela, kým ho nezdieľate.", s.diagnostics) { on ->
                    scope.launch { c.settings.update { it.copy(diagnostics = on) } }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FILTERS.forEachIndexed { i, (label, _) -> FilterChip(filter == i, { filter = i }, { Text(label) }) }
                }
                Text("Najnovšie hore · ${shown.size} záznamov. Pri probléme ťuknite na Zdieľať a pošlite súbor.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shown.isEmpty()) item { Text("Zatiaľ žiadne udalosti. Objavia sa, keď systém nahlási aktivitu alebo spustíte jazdu.", Modifier.padding(16.dp)) }
            val rows: List<Any> = buildList {
                var d = ""
                shown.take(1500).forEach { e -> val day = formatDay(e.ts); if (day != d) { add(day); d = day }; add(e) }
            }
            items(rows) { r ->
                if (r is String) Text(r, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                else if (r is DiagLog.Entry) Row {
                    Text(formatTime(r.ts) + ":" + String.format("%02d", (r.ts / 1000) % 60), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(66.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.category, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = categoryColor(r.category))
                        Text(r.message, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun categoryColor(cat: String) = when (cat) {
    DiagLog.ERROR -> Color(0xFFD32F2F)
    DiagLog.DRIVE -> Color(0xFF2E7D32)
    DiagLog.WALK -> Color(0xFF1565C0)
    DiagLog.CHECK, DiagLog.GPS -> Color(0xFFEF6C00)
    else -> Color(0xFF6D4C41)
}

private fun granted(ctx: android.content.Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** Kontrola podmienok, bez ktorých automatika nefunguje. */
private fun checks(ctx: android.content.Context, s: AppSettings): List<Pair<Boolean, String>> {
    val pm = ctx.getSystemService(PowerManager::class.java)
    return listOf(
        s.autoDetection to "Automatické sledovanie jazdy " + if (s.autoDetection) "zapnuté" else "VYPNUTÉ",
        RuntimeState.isDetectionRegistered(ctx) to "Rozpoznávanie aktivity " + if (RuntimeState.isDetectionRegistered(ctx)) "zaregistrované" else "NEZAREGISTROVANÉ (otvorte Povolenia)",
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) to "Presná poloha",
        (Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) to "Poloha „Povoliť vždy“",
        (Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) to "Fyzická aktivita",
        (Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) to "Notifikácie",
        pm.isIgnoringBatteryOptimizations(ctx.packageName) to "Bez obmedzenia batérie",
        (!s.bluetoothRequired || RuntimeState.isCarBtConnected(ctx)) to
            (if (s.bluetoothRequired) "Bluetooth auta " + (if (RuntimeState.isCarBtConnected(ctx)) "pripojené" else "NEPRIPOJENÉ – jazda sa nespustí (iba s pripojeným autom)") else "Bluetooth nie je podmienkou"),
    )
}

private fun statusText(ctx: android.content.Context, s: AppSettings) = buildString {
    append("Trasník ${sk.cartracker.BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n")
    checks(ctx, s).forEach { (ok, t) -> append(if (ok) "[OK] " else "[!!] ").append(t).append('\n') }
    append("Nastavenia: rýchlosť ${s.minDriveSpeedKmh} km/h, potvrdenie ${s.confirmTimeoutMin} min, státie ${s.stopDelayMin} min, GPS ${s.gpsIntervalSec} s, chôdza ${s.walkTracking}")
}
