package sk.cartracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
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
import sk.cartracker.data.CarCosts
import sk.cartracker.data.cost
import sk.cartracker.data.local.EXPENSE_TYPES
import sk.cartracker.data.local.ExpenseEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.local.expenseLabel
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatMoney
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private enum class CostPeriod(val label: String) { LAST12("12 mesiacov"), THIS_YEAR("Tento rok"), LAST_YEAR("Minulý rok"), ALL("Celkom") }

/** Od–do a dĺžka v mesiacoch. */
private fun costRange(p: CostPeriod, firstTs: Long?): Triple<Long, Long, Double> {
    val now = System.currentTimeMillis()
    val c = Calendar.getInstance()
    fun yearStart(y: Int) = Calendar.getInstance().apply { clear(); set(y, 0, 1) }.timeInMillis
    val y = c.get(Calendar.YEAR)
    val (from, to) = when (p) {
        CostPeriod.LAST12 -> (c.apply { add(Calendar.MONTH, -12) }.timeInMillis) to now
        CostPeriod.THIS_YEAR -> yearStart(y) to now
        CostPeriod.LAST_YEAR -> yearStart(y - 1) to yearStart(y)
        CostPeriod.ALL -> (firstTs ?: now) to now
    }
    return Triple(from, to, ((to - from) / (30.44 * 86_400_000)).coerceAtLeast(1.0))
}

/** Výdavky na auto a celkové náklady (palivo + servis, poistenie, známka…), cena za km a mesiac. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpensesScreen(vehicleUuid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val expenses by remember(vehicleUuid) { c.expenses.expenses(vehicleUuid) }.collectAsStateWithLifecycle(emptyList())
    val refuels by remember(vehicleUuid) { c.refuels.refuels(vehicleUuid) }.collectAsStateWithLifecycle(emptyList())
    var vehicle by remember { mutableStateOf<VehicleEntity?>(null) }
    var period by remember { mutableStateOf(CostPeriod.LAST12) }
    var summary by remember { mutableStateOf<CarCosts.Summary?>(null) }
    var editing by remember { mutableStateOf<ExpenseEntity?>(null) }

    LaunchedEffect(vehicleUuid, expenses, refuels, period, s) {
        val v = c.vehicles.byUuid(vehicleUuid); vehicle = v
        val trips = c.trips.tripsBetween(0, System.currentTimeMillis() + 86_400_000).filter { !it.isWalk && it.vehicleUuid == vehicleUuid }
        val first = listOfNotNull(trips.minOfOrNull { it.startedAt }, expenses.minOfOrNull { it.ts }, refuels.minOfOrNull { it.ts }).minOrNull()
        val (from, to, months) = costRange(period, first)
        val inT = trips.filter { it.startedAt in from until to }
        summary = CarCosts.compute(
            inT.sumOf { it.distanceM } / 1000.0,
            refuels.filter { it.ts in from until to }, expenses.filter { it.ts in from until to },
            inT.sumOf { it.cost(v, s) }, months,
        )
    }
    fun save(e: ExpenseEntity) = scope.launch {
        c.expenses.save(e)
        if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Náklady na auto"); vehicle?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) } } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                scope.launch {
                    editing = ExpenseEntity(uuid = UUID.randomUUID().toString(), userId = 0, vehicleUuid = vehicleUuid, ts = System.currentTimeMillis(),
                        type = "service", amount = 0.0, odometerKm = (c.reminders.odometerNow(vehicleUuid) ?: 0.0).toInt())
                }
            }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Pridať výdavok") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("Celkové náklady") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CostPeriod.entries.forEach { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
                        }
                        val sm = summary
                        if (sm != null) {
                            Text(formatMoney(sm.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("na km: " + (sm.perKm?.let { String.format(Locale("sk", "SK"), "%.3f €", it) } ?: "–"), fontWeight = FontWeight.SemiBold)
                                Text("mesačne: " + (sm.perMonth?.let { formatMoney(it) } ?: "–"), fontWeight = FontWeight.SemiBold)
                            }
                            Text("${String.format(Locale("sk", "SK"), "%.0f", sm.km)} km", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                            CostLine("Palivo" + if (sm.fuelFromRefuels) " (tankovania)" else " (odhad z jázd)", sm.fuel)
                            sm.byType.forEach { (t, a) -> CostLine(expenseLabel(t), a) }
                            if (!sm.fuelFromRefuels) Text("V období nie je zadané tankovanie – palivo je odhad podľa spotreby a ceny vozidla.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (expenses.isEmpty()) item {
                Text("Zatiaľ žiadne výdavky. Pridajte servis, poistenie, diaľničnú známku, parkovanie… – aplikácia spočíta, koľko vás auto naozaj stojí na kilometer a mesiac.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(expenses, key = { it.uuid }) { e ->
                Card(Modifier.fillMaxWidth().clickable { editing = e }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(expenseLabel(e.type), fontWeight = FontWeight.SemiBold)
                            Text(formatDate(e.ts) + (if (e.odometerKm > 0) " · ${e.odometerKm} km" else "") + (if (e.note.isNotBlank()) " · ${e.note}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatMoney(e.amount), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    editing?.let { e ->
        ExpenseDialog(e, onDismiss = { editing = null }, onDelete = if (e.id != 0L) ({ scope.launch { c.expenses.delete(e) }; editing = null }) else null) {
            save(it); editing = null
        }
    }
}

@Composable
private fun CostLine(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatMoney(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseDialog(e: ExpenseEntity, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (ExpenseEntity) -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(e.type) }
    var amount by remember { mutableStateOf(if (e.amount > 0) String.format(Locale.US, "%.2f", e.amount) else "") }
    var ts by remember { mutableStateOf(e.ts) }
    var odo by remember { mutableStateOf(if (e.odometerKm > 0) e.odometerKm.toString() else "") }
    var note by remember { mutableStateOf(e.note) }
    var confirmDel by remember { mutableIntStateOf(0) }
    val value = amount.trim().replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e.id == 0L) "Nový výdavok" else "Upraviť výdavok") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EXPENSE_TYPES.forEach { (k, l) -> FilterChip(type == k, { type = k }, { Text(l) }) }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Suma (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickDate(ctx, ts) { ts = it } }) { Text("Dátum: " + formatDate(ts)) }
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit).take(7) }, label = { Text("Tachometer (km, nepovinné)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Poznámka") }, modifier = Modifier.fillMaxWidth())
                if (onDelete != null) TextButton(onClick = { if (confirmDel == 1) onDelete() else confirmDel = 1 }) {
                    Text(if (confirmDel == 1) "Naozaj zmazať?" else "Zmazať výdavok", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null && value > 0, onClick = {
                onSave(e.copy(type = type, amount = value ?: 0.0, ts = ts, odometerKm = odo.toIntOrNull() ?: 0, note = note.trim()))
            }) { Text("Uložiť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
