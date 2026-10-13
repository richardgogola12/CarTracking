package sk.cartracker.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.ReminderState
import sk.cartracker.data.ReminderStatus
import sk.cartracker.data.local.REMINDER_KINDS
import sk.cartracker.data.local.ReminderEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDate
import java.util.Calendar
import java.util.UUID

fun reminderColor(st: ReminderState) = when (st) {
    ReminderState.OVERDUE -> Color(0xFFD32F2F)
    ReminderState.SOON -> Color(0xFFEF6C00)
    ReminderState.OK -> Color(0xFF2E7D32)
    ReminderState.DONE -> Color(0xFF78909C)
}

fun ReminderStatus.text(): String = listOfNotNull(
    daysLeft?.let { if (it < 0) "${-it} dní po termíne" else if (it == 0L) "dnes" else "o $it dní" },
    kmLeft?.let { if (it < 0) "${-it} km po termíne" else "o $it km" },
).joinToString(" · ").ifEmpty { state.label }

/** Predvolené opakovanie podľa typu: mesiace, km. */
private fun preset(kind: String): Pair<Int, Int> = when (kind) {
    "stk", "ek" -> 24 to 0
    "oil" -> 12 to 15000
    "vignette", "insurance" -> 12 to 0
    "tires" -> 6 to 0
    "service" -> 12 to 20000
    else -> 0 to 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val list by remember { c.reminders.reminders() }.collectAsStateWithLifecycle(emptyList())
    val vehicles by remember { c.vehicles.vehicles() }.collectAsStateWithLifecycle(emptyList())
    var statuses by remember { mutableStateOf<Map<String, ReminderStatus>>(emptyMap()) }
    LaunchedEffect(list) { statuses = list.associate { it.uuid to c.reminders.status(it) } }
    var editing by remember { mutableStateOf<ReminderEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pripomienky") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                editing = ReminderEntity(uuid = UUID.randomUUID().toString(), userId = 0, kind = "stk", title = "STK",
                    vehicleUuid = s.activeVehicleUuid.ifEmpty { vehicles.firstOrNull()?.uuid }, repeatMonths = 24)
            }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Pridať") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Upozornenie príde vopred podľa dátumu alebo najazdených km (tachometer sa dopočítava z jázd a tankovaní).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (list.isEmpty()) item { Text("Zatiaľ žiadne pripomienky.", Modifier.padding(vertical = 24.dp)) }
            items(list, key = { it.uuid }) { r ->
                val st = statuses[r.uuid] ?: ReminderStatus(if (r.done) ReminderState.DONE else ReminderState.OK, null, null)
                val v = vehicles.firstOrNull { it.uuid == r.vehicleUuid }
                Card(onClick = { editing = r }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(reminderColor(st.state), RoundedCornerShape(6.dp)))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(r.dueDate?.let { formatDate(it) }, r.dueKm?.let { "$it km" }, v?.label).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                            Text(if (r.done) "Vybavené" else st.text(), color = reminderColor(st.state), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!r.done) IconButton(onClick = { scope.launch { c.reminders.complete(r); if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly) } }) {
                            Icon(Icons.Filled.CheckCircle, "Vybavené", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    editing?.let { r ->
        ReminderDialog(r, vehicles, onDismiss = { editing = null }, onSave = { saved ->
            scope.launch {
                c.reminders.save(saved.copy(lastNotifiedAt = 0))
                if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                editing = null
            }
        }, onDelete = if (r.id != 0L) { { scope.launch { c.reminders.delete(r); editing = null }; Unit } } else null)
    }
}

@Composable
private fun ReminderDialog(
    initial: ReminderEntity, vehicles: List<VehicleEntity>,
    onDismiss: () -> Unit, onSave: (ReminderEntity) -> Unit, onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(initial.kind) }
    var title by remember { mutableStateOf(initial.title) }
    var vehicle by remember { mutableStateOf(initial.vehicleUuid) }
    var dueDate by remember { mutableStateOf(initial.dueDate) }
    var dueKm by remember { mutableStateOf(initial.dueKm?.toString() ?: "") }
    var repeatMonths by remember { mutableStateOf(initial.repeatMonths) }
    var repeatKm by remember { mutableStateOf(initial.repeatKm.takeIf { it > 0 }?.toString() ?: "") }
    var notifyDays by remember { mutableStateOf(initial.notifyDays) }
    var note by remember { mutableStateOf(initial.note) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.NotificationsActive, null) },
        title = { Text(if (initial.id == 0L) "Nová pripomienka" else "Upraviť pripomienku") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REMINDER_KINDS.forEach { (k, l) ->
                        FilterChip(kind == k, {
                            kind = k
                            if (title.isBlank() || REMINDER_KINDS.any { it.second == title }) title = if (k == "custom") "" else l
                            val (m, km) = preset(k)
                            repeatMonths = m; repeatKm = if (km > 0) km.toString() else ""
                        }, { Text(l) })
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Názov") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vehicles.isNotEmpty()) {
                    Text("Vozidlo", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(vehicle == null, { vehicle = null }, { Text("Žiadne") })
                        vehicles.forEach { v -> FilterChip(vehicle == v.uuid, { vehicle = v.uuid }, { Text(v.plate.ifBlank { v.name }) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val start = dueDate ?: Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.timeInMillis
                        pickDate(ctx, start) { dueDate = it }
                    }) {
                        Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                        Text(dueDate?.let { "Termín: ${formatDate(it)}" } ?: "Zadať dátum")
                    }
                    if (dueDate != null) TextButton(onClick = { dueDate = null }) { Text("Bez dátumu") }
                }
                OutlinedTextField(dueKm, { dueKm = it.filter(Char::isDigit).take(7) }, label = { Text("Pri stave tachometra (km) – nepovinné") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                SliderRow("Upozorniť vopred", notifyDays, 1..60, 1, "dní") { notifyDays = it }
                SliderRow("Opakovať každých", repeatMonths, 0..36, 1, "mes.", "0 = neopakovať",
                    format = { if (it == 0) "neopakovať" else "$it mes." }) { repeatMonths = it }
                OutlinedTextField(repeatKm, { repeatKm = it.filter(Char::isDigit).take(6) }, label = { Text("Opakovať každých … km (nepovinné)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Poznámka") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Odstrániť", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val km = dueKm.toIntOrNull()
                error = when {
                    title.isBlank() -> "Zadajte názov"
                    dueDate == null && km == null -> "Zadajte dátum alebo stav tachometra"
                    km != null && vehicle == null -> "Pri termíne podľa km vyberte vozidlo"
                    else -> null
                }
                if (error == null) onSave(initial.copy(
                    kind = kind, title = title.trim(), vehicleUuid = vehicle, dueDate = dueDate, dueKm = km,
                    notifyDays = notifyDays, repeatMonths = repeatMonths, repeatKm = repeatKm.toIntOrNull() ?: 0,
                    note = note.trim(), done = false,
                ))
            }) { Text("Uložiť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
