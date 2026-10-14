package sk.cartracker.ui.screens

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.RefuelRepository
import sk.cartracker.data.local.RefuelEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatMoney
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private val SK = Locale("sk", "SK")
private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
private fun fmt(d: Double, dec: Int = 2) = String.format(SK, "%.${dec}f", d)

fun pickDate(ctx: Context, initial: Long, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(ctx, { _, y, m, d ->
        val out = Calendar.getInstance().apply { timeInMillis = initial; set(y, m, d) }
        onPick(out.timeInMillis)
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

/** Tankovanie jedného vozidla a skutočná spotreba (metóda plná–plná nádrž). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelsScreen(vehicleUuid: String, openAdd: Boolean = false, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val refuels by remember(vehicleUuid) { c.refuels.refuels(vehicleUuid) }.collectAsStateWithLifecycle(emptyList())
    var vehicle by remember { mutableStateOf<VehicleEntity?>(null) }
    var editing by remember { mutableStateOf<RefuelEntity?>(null) }
    var odoEstimate by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(vehicleUuid, refuels) {
        vehicle = c.vehicles.byUuid(vehicleUuid)
        odoEstimate = c.reminders.odometerNow(vehicleUuid)
    }
    // Skratka „Tankovanie“ na ikone aplikácie → hneď formulár nového tankovania
    LaunchedEffect(Unit) {
        if (openAdd) editing = RefuelEntity(
            uuid = UUID.randomUUID().toString(), userId = 0, vehicleUuid = vehicleUuid, ts = System.currentTimeMillis(),
            liters = 0.0, totalPrice = 0.0, odometerKm = (c.reminders.odometerNow(vehicleUuid) ?: 0.0).toInt(),
        )
    }
    val stats = remember(refuels) { RefuelRepository.stats(refuels) }
    var odoRatio by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    LaunchedEffect(refuels) { odoRatio = c.trips.odometerRatio(vehicleUuid, refuels) }
    var calMsg by remember { mutableStateOf<String?>(null) }
    val perRefuel = remember(stats) { stats.points.associate { it.refuel.uuid to it.per100 } }
    val unit = vehicle?.unit ?: "l"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Tankovanie"); vehicle?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) } } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                editing = RefuelEntity(
                    uuid = UUID.randomUUID().toString(), userId = 0, vehicleUuid = vehicleUuid, ts = System.currentTimeMillis(),
                    liters = 0.0, totalPrice = 0.0, odometerKm = (odoEstimate ?: 0.0).toInt(),
                )
            }, icon = { Icon(Icons.Filled.LocalGasStation, null) }, text = { Text("Natankoval som") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // Kalibrácia km podľa tachometra
                SectionCard("Kalibrácia km podľa tachometra") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val current = s.odoFactor(vehicleUuid)
                        Text("Aktuálna oprava: " + if (current == 1.0) "žiadna" else "${fmt((current - 1) * 100, 1)} % (× ${fmt(current, 4)})",
                            fontWeight = FontWeight.SemiBold)
                        val r = odoRatio
                        if (r == null) {
                            Text("Zadávajte stav tachometra pri tankovaní. Po dvoch tankovaniach s odstupom aspoň 100 km aplikácia porovná tachometer s km z GPS a navrhne opravu.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val (ratio, odoKm, appKm) = r
                            val suggested = (current * ratio).coerceIn(0.5, 1.5)
                            Text("Tachometer: ${fmt(odoKm, 0)} km · aplikácia: ${fmt(appKm, 0)} km → rozdiel ${fmt((ratio - 1) * 100, 1)} %",
                                style = MaterialTheme.typography.bodySmall)
                            if (kotlin.math.abs(ratio - 1) < 0.005) Text("Km sedia s tachometrom ✓", color = MaterialTheme.colorScheme.primary)
                            else {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        c.settings.update { it.copy(odoFactors = it.withOdoFactor(vehicleUuid, suggested)) }
                                        calMsg = "Oprava ${fmt((suggested - 1) * 100, 1)} % sa použije pre nové jazdy."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Použiť pre nové jazdy") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        c.settings.update { it.copy(odoFactors = it.withOdoFactor(vehicleUuid, suggested)) }
                                        val n = c.trips.rescaleVehicleTrips(vehicleUuid, ratio)
                                        if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                                        calMsg = "Opravené aj staršie jazdy ($n) a nové jazdy."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Použiť aj na všetky staršie jazdy") }
                            }
                        }
                        if (current != 1.0) TextButton(onClick = {
                            scope.launch { c.settings.update { it.copy(odoFactors = it.withOdoFactor(vehicleUuid, null)) }; calMsg = "Oprava zrušená (staršie jazdy ostávajú)." }
                        }) { Text("Zrušiť opravu") }
                        calMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item {
                SectionCard("Skutočná spotreba") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Priemer", stats.average?.let { "${fmt(it, 1)} $unit" } ?: "–", Modifier.weight(1f))
                            StatBox("Nastavená", vehicle?.let { "${fmt(it.consumptionX10 / 10.0, 1)} $unit" } ?: "–", Modifier.weight(1f))
                            StatBox("Cena / km", stats.pricePerKm?.let { "${fmt(it, 3)} €" } ?: "–", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Tankovaní", "${refuels.size}", Modifier.weight(1f))
                            StatBox("Spolu", "${fmt(stats.totalUnits, 1)} $unit", Modifier.weight(1f))
                            StatBox("Zaplatené", formatMoney(stats.totalPrice), Modifier.weight(1f))
                        }
                        if (stats.average == null) {
                            Text("Spotreba sa vypočíta po druhom tankovaní „do plna“ (z litrov a rozdielu tachometra).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else vehicle?.let { v ->
                            val last = refuels.firstOrNull()
                            OutlinedButton(onClick = {
                                scope.launch {
                                    c.vehicles.save(v.copy(
                                        consumptionX10 = (stats.average * 10).toInt(),
                                        fuelPriceCents = last?.let { (it.pricePerUnit * 100).toInt() } ?: v.fuelPriceCents,
                                    ))
                                    vehicle = c.vehicles.byUuid(vehicleUuid)
                                }
                            }) { Text("Použiť skutočnú spotrebu a poslednú cenu pre odhady") }
                        }
                    }
                }
            }
            items(refuels, key = { it.uuid }) { r ->
                Card(onClick = { editing = r }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalGasStation, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${formatDate(r.ts)} · ${r.odometerKm} km", fontWeight = FontWeight.SemiBold)
                            Text("${fmt(r.liters)} $unit × ${fmt(r.pricePerUnit, 3)} € · ${if (r.fullTank) "do plna" else "čiastočne"}" +
                                (if (r.note.isNotBlank()) " · ${r.note}" else ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatMoney(r.totalPrice), fontWeight = FontWeight.Bold)
                            perRefuel[r.uuid]?.let { Text("${fmt(it, 1)} $unit/100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RefuelDialog(r, unit, onDismiss = { editing = null }, onSave = { saved ->
            scope.launch {
                c.refuels.save(saved)
                if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                editing = null
            }
        }, onDelete = if (r.id != 0L) { { scope.launch { c.refuels.delete(r); editing = null }; Unit } } else null)
    }
}

@Composable
private fun RefuelDialog(initial: RefuelEntity, unit: String, onDismiss: () -> Unit, onSave: (RefuelEntity) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    var ts by remember { mutableStateOf(initial.ts) }
    var liters by remember { mutableStateOf(if (initial.liters > 0) fmt(initial.liters) else "") }
    var total by remember { mutableStateOf(if (initial.totalPrice > 0) fmt(initial.totalPrice) else "") }
    var perUnit by remember { mutableStateOf(if (initial.liters > 0) fmt(initial.pricePerUnit, 3) else "") }
    var odo by remember { mutableStateOf(if (initial.odometerKm > 0) initial.odometerKm.toString() else "") }
    var full by remember { mutableStateOf(initial.fullTank) }
    var note by remember { mutableStateOf(initial.note) }
    var error by remember { mutableStateOf<String?>(null) }

    // dopočítanie: zadané litre + cena za liter → spolu, alebo litre + spolu → cena za liter
    fun recalcTotal() { val l = num(liters); val p = num(perUnit); if (l != null && p != null) total = fmt(l * p) }
    fun recalcPer() { val l = num(liters); val t = num(total); if (l != null && t != null && l > 0) perUnit = fmt(t / l, 3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Tankovanie" else "Upraviť tankovanie") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(ctx, ts) { ts = it } }) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(formatDate(ts))
                }
                OutlinedTextField(liters, { liters = it; recalcTotal() }, label = { Text("Natankované ($unit)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(perUnit, { perUnit = it; recalcTotal() }, label = { Text("Cena za $unit (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(total, { total = it; recalcPer() }, label = { Text("Spolu zaplatené (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit).take(7) }, label = { Text("Stav tachometra (km)") }, singleLine = true,
                    supportingText = { Text("Predvyplnený odhad z jázd – opravte podľa tachometra") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Do plnej nádrže")
                        Text("Potrebné na presný výpočet spotreby", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(full, { full = it })
                }
                OutlinedTextField(note, { note = it.take(100) }, label = { Text("Poznámka (čerpacia stanica…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Odstrániť", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = num(liters); val t = num(total); val o = odo.toIntOrNull()
                error = when {
                    l == null || l <= 0 -> "Zadajte natankované množstvo"
                    t == null || t <= 0 -> "Zadajte cenu"
                    o == null || o <= 0 -> "Zadajte stav tachometra"
                    else -> null
                }
                if (error == null) onSave(initial.copy(ts = ts, liters = l!!, totalPrice = t!!, odometerKm = o!!, fullTank = full, note = note.trim()))
            }) { Text("Uložiť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
