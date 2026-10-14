package sk.cartracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sk.cartracker.data.TripCheck
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatTime

/** Kontrola jázd za posledných 30 dní – navrhnuté opravy jedným ťuknutím. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripCheckScreen(onBack: () -> Unit, onOpenTrip: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tick by remember { mutableIntStateOf(0) }
    var issues by remember { mutableStateOf<List<TripCheck.Issue>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TripCheck.Issue?>(null) }
    LaunchedEffect(tick) { issues = runCatching { TripCheck.scan(ctx) }.getOrDefault(emptyList()) }

    fun run(i: TripCheck.Issue) {
        busy = true
        scope.launch {
            val msg = runCatching { TripCheck.apply(ctx, i) }.getOrElse { "Oprava zlyhala: ${it.message}" }
            busy = false; tick++
            snack.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kontrola jázd") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
                actions = { IconButton(onClick = { issues = null; tick++ }) { Icon(Icons.Filled.Refresh, "Znova skontrolovať") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val list = issues
        if (list == null || busy) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    if (list.isEmpty()) "✅ Za posledných 30 dní sa nenašlo nič podozrivé."
                    else "Našlo sa ${list.size} vecí na kontrolu (posledných 30 dní). Oprava sa dá ťuknutím, „Ignorovať“ položku skryje.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(list, key = { it.key }) { i ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(icon(i.kind) + " " + i.title, fontWeight = FontWeight.Bold)
                        Text(
                            formatDate(i.trip.startedAt) + " · " + formatTime(i.trip.startedAt) + " · " + formatDistance(i.trip.distanceM) +
                                (i.trip.startPlace ?: i.trip.startAddress)?.let { " · z $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(i.detail, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            i.fix?.let { f -> Button(onClick = { if (f == TripCheck.Fix.DELETE) confirm = i else run(i) }) { Text(f.label) } }
                            OutlinedButton(onClick = { onOpenTrip(i.trip.id) }) { Text("Zobraziť") }
                            TextButton(onClick = { TripCheck.dismiss(ctx, i.key); issues = list - i }) { Text("Ignorovať") }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { i ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Zmazať jazdu?") },
            text = { Text("${formatDate(i.trip.startedAt)} ${formatTime(i.trip.startedAt)}, ${formatDistance(i.trip.distanceM)}. Zmaže sa aj zo servera.") },
            confirmButton = { TextButton(onClick = { confirm = null; run(i) }) { Text("Zmazať") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Zrušiť") } },
        )
    }
}

private fun icon(k: TripCheck.Kind) = when (k) {
    TripCheck.Kind.STOP_END -> "⏱"
    TripCheck.Kind.STOP_START -> "⏳"
    TripCheck.Kind.STOP_MIDDLE -> "✂️"
    TripCheck.Kind.SPLIT -> "🔗"
    TripCheck.Kind.GPS_GAP -> "📡"
    TripCheck.Kind.SPEED -> "⚠️"
    TripCheck.Kind.NOT_A_TRIP -> "❓"
}
