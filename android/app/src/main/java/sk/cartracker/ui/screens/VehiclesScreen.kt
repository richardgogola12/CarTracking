package sk.cartracker.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.local.FUEL_TYPES
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDate
import java.util.Locale
import java.util.UUID

fun fuelLabel(type: String) = FUEL_TYPES.firstOrNull { it.first == type }?.second ?: type

/** Zoznam vozidiel – pridanie, úprava, aktívne vozidlo, Bluetooth, skrytie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val vehicles by remember { c.vehicles.vehicles() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<VehicleEntity?>(null) }
    LaunchedEffect(Unit) { c.vehicles.ensureDefault() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vozidlá") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = VehicleEntity(uuid = UUID.randomUUID().toString(), userId = 0, name = "",
                        consumptionX10 = s.fuelConsumptionX10, fuelPriceCents = s.fuelPriceCents)
                },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Pridať vozidlo") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Aktívne vozidlo sa priradí každej novej jazde. Prepnúť ho môžeš kedykoľvek – aj počas jazdy " +
                    "(na Prehľade). Ak má vozidlo nastavené Bluetooth, prepne sa samo po pripojení.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(vehicles, key = { it.uuid }) { v ->
                val active = v.uuid == s.activeVehicleUuid
                Card(
                    onClick = { scope.launch { c.vehicles.setActive(v.uuid) } },
                    colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(if (v.fuelType == "electric") Icons.Filled.ElectricCar else Icons.Filled.DirectionsCar, null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(v.plate.ifBlank { null }, fuelLabel(v.fuelType),
                                String.format(Locale("sk"), "%.1f %s/100 km", v.consumptionX10 / 10.0, v.unit)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                            if (v.btName.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bluetooth, null, Modifier.size(14.dp)); Text(" ${v.btName}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (active) Icon(Icons.Filled.CheckCircle, "Aktívne", tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { editing = v }) { Icon(Icons.Filled.Edit, "Upraviť") }
                    }
                }
            }
        }
    }

    editing?.let { v ->
        VehicleDialog(
            initial = v,
            canDelete = v.id != 0L && vehicles.size > 1,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    val res = c.vehicles.save(saved)
                    if (s.activeVehicleUuid.isEmpty()) c.vehicles.setActive(res.uuid)
                    if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                    editing = null
                }
            },
            onDelete = { scope.launch { c.vehicles.archive(v); editing = null } },
        )
    }
}

@Composable
private fun VehicleDialog(
    initial: VehicleEntity, canDelete: Boolean,
    onDismiss: () -> Unit, onSave: (VehicleEntity) -> Unit, onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var plate by remember { mutableStateOf(initial.plate) }
    var fuel by remember { mutableStateOf(initial.fuelType) }
    var cons by remember { mutableStateOf(if (initial.consumptionX10 > 0) (initial.consumptionX10 / 10.0).toString().replace('.', ',') else "") }
    var price by remember { mutableStateOf(String.format(Locale("sk"), "%.2f", initial.fuelPriceCents / 100.0)) }
    var odo by remember { mutableStateOf(if (initial.odometerKm > 0) initial.odometerKm.toString() else "") }
    var btAddress by remember { mutableStateOf(initial.btAddress) }
    var btName by remember { mutableStateOf(initial.btName) }
    var pickBt by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) pickBt = true }

    fun num(sv: String) = sv.trim().replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Nové vozidlo" else "Upraviť vozidlo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Názov (napr. Škoda Octavia)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(plate, { plate = it.uppercase() }, label = { Text("EČV (ŠPZ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Palivo", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FUEL_TYPES.forEach { (key, label) -> FilterChip(fuel == key, { fuel = key }, { Text(label) }) }
                }
                val unit = if (fuel == "electric") "kWh" else "l"
                OutlinedTextField(cons, { cons = it }, label = { Text("Spotreba ($unit/100 km)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price, { price = it }, label = { Text("Cena (€/$unit)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit).take(7) }, label = { Text("Stav tachometra (km)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (initial.odometerDate > 0) Text("zadané ${formatDate(initial.odometerDate)}, ďalej sa dopočítava z jázd") })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                    val need = Build.VERSION.SDK_INT >= 31 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    if (need) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) else pickBt = true
                }.padding(vertical = 6.dp)) {
                    Icon(Icons.Filled.Bluetooth, null)
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bluetooth vozidla")
                        Text(btName.ifEmpty { "nevybrané – ťuknite" }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (btAddress.isNotEmpty()) TextButton(onClick = { btAddress = ""; btName = "" }) { Text("Zrušiť") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (canDelete) OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Odstrániť vozidlo") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c10 = num(cons)?.let { (it * 10).toInt() }
                val pc = num(price)?.let { (it * 100).toInt() }
                val newOdo = odo.toIntOrNull() ?: 0
                error = when {
                    name.isBlank() -> "Zadajte názov vozidla"
                    c10 == null || c10 !in 1..1000 -> "Zadajte platnú spotrebu"
                    pc == null || pc !in 1..2000 -> "Zadajte platnú cenu"
                    else -> null
                }
                if (error == null) onSave(initial.copy(
                    name = name.trim(), plate = plate.trim(), fuelType = fuel, consumptionX10 = c10!!, fuelPriceCents = pc!!,
                    odometerKm = newOdo,
                    odometerDate = if (newOdo != initial.odometerKm) System.currentTimeMillis() else initial.odometerDate,
                    btAddress = btAddress, btName = btName,
                ))
            }) { Text("Uložiť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )

    if (pickBt) {
        val devices = remember { bondedBtDevices(ctx) }
        AlertDialog(
            onDismissRequest = { pickBt = false },
            title = { Text("Bluetooth vozidla") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (devices.isEmpty()) Text("Žiadne spárované zariadenia. Najprv spárujte telefón s autom v nastaveniach Bluetooth.")
                    devices.forEach { (n, a) ->
                        Text(n, Modifier.fillMaxWidth().clickable { btName = n; btAddress = a; pickBt = false }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickBt = false }) { Text("Zavrieť") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Odstrániť vozidlo?") },
            text = { Text("Vozidlo sa skryje. Jeho jazdy ostanú zachované v zozname aj v knihe jázd.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Odstrániť") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušiť") } },
        )
    }
}

@SuppressLint("MissingPermission")
fun bondedBtDevices(ctx: Context): List<Pair<String, String>> = try {
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
        ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first } ?: emptyList()
} catch (e: SecurityException) { emptyList() }
