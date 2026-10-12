package sk.cartracker.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.MapStyle
import sk.cartracker.data.ThemeMode
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.VibrationPattern
import sk.cartracker.data.remote.ApiProvider
import sk.cartracker.detection.ActivityDetectionManager
import sk.cartracker.security.BiometricAuth
import sk.cartracker.service.Notifications
import sk.cartracker.sync.SyncWorker

@Composable
fun SettingsScreen(onOpenPermissions: () -> Unit, onLoggedOut: () -> Unit, onOpenHelp: () -> Unit = {}) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }

    var message by remember { mutableStateOf<String?>(null) }
    var showLogout by remember { mutableStateOf(false) }
    var showDeleteData by remember { mutableStateOf(false) }
    var showBtPicker by remember { mutableStateOf(false) }
    var serverUrl by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }

    val btPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { showBtPicker = true } else { message = "Bez povolenia „Zariadenia v okolí“ nemožno vybrať Bluetooth auta." }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nastavenia", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---------- Detekcia ----------
        SectionCard("Automatická detekcia jazdy") {
            SwitchRow("Automaticky sledovať jazdu autom", "Spustí sa sama, keď telefón rozpozná jazdu vo vozidle",
                s.autoDetection) { on ->
                update { it.copy(autoDetection = on) }
                scope.launch { if (on) ActivityDetectionManager.register(ctx) else ActivityDetectionManager.unregister(ctx) }
            }
            SwitchRow("Spustiť po reštarte telefónu", null, s.startOnBoot, enabled = s.autoDetection) { on -> update { it.copy(startOnBoot = on) } }
            SliderRow("Rýchlosť potvrdzujúca auto", s.minDriveSpeedKmh, 15..50, 5, "km/h",
                "Pod touto rýchlosťou sa pohyb nepovažuje za jazdu autom (bicykel, beh).") { v -> update { it.copy(minDriveSpeedKmh = v) } }
            SliderRow("Čas na potvrdenie jazdy", s.confirmTimeoutMin, 2..15, 1, "min",
                "Ak sa rýchlosť nepotvrdí, detekcia sa potichu zruší.") { v -> update { it.copy(confirmTimeoutMin = v) } }
            SliderRow("Ukončiť jazdu po státí", s.stopDelayMin, 2..20, 1, "min",
                "Zastavenie v kolóne alebo na semafore jazdu neukončí.") { v -> update { it.copy(stopDelayMin = v) } }
            SliderRow("Minimálna dĺžka jazdy", s.minTripDistanceM, 0..3000, 100, "m",
                "Kratšie automatické jazdy sa neukladajú.") { v -> update { it.copy(minTripDistanceM = v) } }
        }

        // ---------- GPS ----------
        SectionCard("GPS") {
            SliderRow("Interval záznamu polohy", s.gpsIntervalSec, 1..15, 1, "s",
                "Kratší interval = presnejšia trasa, vyššia spotreba batérie.") { v -> update { it.copy(gpsIntervalSec = v) } }
            SwitchRow("Vysoká presnosť (GPS)", "Vypnutím ušetríte batériu, trasa bude menej presná", s.highAccuracy) { on -> update { it.copy(highAccuracy = on) } }
        }

        // ---------- Notifikácie ----------
        SectionCard("Notifikácie a vibrácie") {
            SwitchRow("Oznámiť zapnutie režimu jazdy", null, s.notifyOnStart) { on -> update { it.copy(notifyOnStart = on) } }
            SwitchRow("Oznámiť vypnutie režimu jazdy", "So súhrnom jazdy (km, čas, priemer)", s.notifyOnStop) { on -> update { it.copy(notifyOnStop = on) } }
            SwitchRow("Zvuk notifikácie", null, s.sound) { on -> update { it.copy(sound = on) } }
            SwitchRow("Vibrovať pri zapnutí / vypnutí", null, s.vibrate) { on -> update { it.copy(vibrate = on) } }
            if (s.vibrate) {
                Text("Typ vibrácie", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                VibrationPattern.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().selectable(s.vibrationPattern == p) { update { it.copy(vibrationPattern = p) } }
                        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.vibrationPattern == p, onClick = { update { it.copy(vibrationPattern = p) } })
                        Text(p.label)
                    }
                }
            }
            SwitchRow("Rýchlosť v trvalej notifikácii", null, s.showSpeedInNotification) { on -> update { it.copy(showSpeedInNotification = on) } }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Notifications.driveEvent(ctx, true, s, "Toto je skúšobná notifikácia") }) { Text("Vyskúšať") }
            }
        }

        // ---------- Bluetooth ----------
        SectionCard("Bluetooth auta (voliteľné)") {
            Text("Ak má auto Bluetooth, jeho pripojenie je spoľahlivý signál, že sedíte v aute – a odlíši ho aj od autobusu či vlaku.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().clickable {
                val needs = Build.VERSION.SDK_INT >= 31 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                if (needs) btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) else showBtPicker = true
            }.padding(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Bluetooth zariadenie auta")
                    Text(if (s.carBtName.isEmpty()) "Nevybrané – ťuknite pre výber" else s.carBtName,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.carBtAddress.isNotEmpty()) TextButton(onClick = {
                    update { it.copy(carBtAddress = "", carBtName = "", bluetoothTrigger = false, bluetoothRequired = false) }
                }) { Text("Zrušiť") }
            }
            val hasCar = s.carBtAddress.isNotEmpty()
            SwitchRow("Spustiť sledovanie po pripojení k autu", null, s.bluetoothTrigger, enabled = hasCar) { on -> update { it.copy(bluetoothTrigger = on) } }
            SwitchRow("Sledovať iba keď je pripojené auto", "Ignoruje autobus, vlak, taxík…", s.bluetoothRequired, enabled = hasCar) { on -> update { it.copy(bluetoothRequired = on) } }
        }

        // ---------- Synchronizácia ----------
        SectionCard("Server a synchronizácia") {
            SwitchRow("Automaticky odosielať jazdy", "Na server s databázou MySQL", s.autoSync) { on -> update { it.copy(autoSync = on) } }
            SwitchRow("Iba cez Wi-Fi", null, s.syncWifiOnly) { on ->
                update { it.copy(syncWifiOnly = on) }
                SyncWorker.schedulePeriodic(ctx, on)
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Adresa API servera") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = serverUrl != s.serverUrl, onClick = {
                        val url = ApiProvider.normalize(serverUrl)
                        if (!url.startsWith("http://") && !url.startsWith("https://")) { message = "Adresa musí začínať http:// alebo https://" }
                        else { update { it.copy(serverUrl = url) }; message = "Adresa servera uložená" }
                    }) { Text("Uložiť") }
                    OutlinedButton(onClick = { SyncWorker.enqueueNow(ctx, false); message = "Synchronizácia spustená" }) { Text("Synchronizovať teraz") }
                }
            }
        }

        // ---------- Kniha jázd ----------
        SectionCard("Kniha jázd") {
            Text("Predvolený typ novej jazdy", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            listOf(CATEGORY_PRIVATE to "Súkromná", CATEGORY_BUSINESS to "Služobná").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().selectable(s.defaultCategory == key) { update { it.copy(defaultCategory = key) } }
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.defaultCategory == key, onClick = { update { it.copy(defaultCategory = key) } })
                    Text(label)
                }
            }
            var carName by remember(s.carName) { mutableStateOf(s.carName) }
            var plate by remember(s.licensePlate) { mutableStateOf(s.licensePlate) }
            var odo by remember(s.odometerKm) { mutableStateOf(if (s.odometerKm > 0) s.odometerKm.toString() else "") }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(carName, { carName = it }, label = { Text("Vozidlo (napr. Škoda Octavia)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(plate, { plate = it.uppercase() }, label = { Text("EČV (ŠPZ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit).take(7) }, label = { Text("Aktuálny stav tachometra (km)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    supportingText = { if (s.odometerDate > 0) Text("Zadané ${sk.cartracker.ui.formatDateTime(s.odometerDate)} – ďalej sa dopočítava z jázd") },
                    modifier = Modifier.fillMaxWidth())
                val changed = carName != s.carName || plate != s.licensePlate || odo != (if (s.odometerKm > 0) s.odometerKm.toString() else "")
                Button(enabled = changed, onClick = {
                    val newOdo = odo.toIntOrNull() ?: 0
                    update {
                        it.copy(carName = carName.trim(), licensePlate = plate.trim(), odometerKm = newOdo,
                            odometerDate = if (newOdo != it.odometerKm) System.currentTimeMillis() else it.odometerDate)
                    }
                    message = "Údaje o vozidle uložené"
                }) { Text("Uložiť údaje o vozidle") }
            }
            SwitchRow("Zisťovať adresy začiatku a cieľa", "Ulica a mesto v zozname jázd a v knihe jázd (potrebuje internet)", s.detectAddresses) { on -> update { it.copy(detectAddresses = on) } }
        }

        // ---------- Palivo ----------
        SectionCard("Palivo a náklady") {
            SliderRow("Priemerná spotreba", s.fuelConsumptionX10, 30..200, 5, "l/100 km",
                format = { String.format(java.util.Locale("sk"), "%.1f l/100 km", it / 10.0) }) { v -> update { it.copy(fuelConsumptionX10 = v) } }
            SliderRow("Cena paliva", s.fuelPriceCents, 100..250, 1, "€/l",
                "Náklady sú odhad: km × spotreba × cena.",
                format = { String.format(java.util.Locale("sk"), "%.2f €/l", it / 100.0) }) { v -> update { it.copy(fuelPriceCents = v) } }
        }

        // ---------- Štýl jazdy ----------
        SectionCard("Štýl jazdy a rýchlosť") {
            SwitchRow("Hodnotiť štýl jazdy", "Prudké brzdenie a zrýchlenie, skóre 0–100 pre každú jazdu", s.drivingEvents) { on -> update { it.copy(drivingEvents = on) } }
            SliderRow("Rýchlostný limit", s.speedAlertKmh, 50..200, 10, "km/h",
                "Čas nad limitom znižuje skóre a vyznačí sa na mape.") { v -> update { it.copy(speedAlertKmh = v) } }
            SwitchRow("Upozorniť vibráciou pri prekročení", "Najviac raz za minútu", s.speedAlert) { on -> update { it.copy(speedAlert = on) } }
        }

        // ---------- Mapa ----------
        SectionCard("Mapa") {
            MapStyle.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().selectable(s.mapStyle == m) { update { it.copy(mapStyle = m) } }
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.mapStyle == m, onClick = { update { it.copy(mapStyle = m) } })
                    Text(m.label)
                }
            }
        }

        // ---------- Pomoc ----------
        SectionCard("Pomoc") {
            Text("Návod a dokumentácia (aj na tlač)", Modifier.fillMaxWidth().clickable(onClick = onOpenHelp).padding(16.dp))
        }

        // ---------- Zabezpečenie ----------
        SectionCard("Zabezpečenie") {
            val reason = remember { BiometricAuth.unavailableReason(ctx) }
            SwitchRow("Prihlásenie odtlačkom prsta", reason ?: "Odomknutie aplikácie a prihlásenie bez hesla",
                s.biometricEnabled && c.session.hasBiometricToken(), enabled = reason == null) { on ->
                if (on) {
                    val act = activity ?: return@SwitchRow
                    BiometricAuth.enroll(act, c.session) { ok, err ->
                        if (ok) { update { it.copy(biometricEnabled = true) } } else { message = err ?: "Nepodarilo sa zapnúť" }
                    }
                } else {
                    c.session.clearBiometricToken()
                    update { it.copy(biometricEnabled = false) }
                }
            }
        }

        // ---------- Vzhľad ----------
        SectionCard("Vzhľad") {
            ThemeMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().selectable(s.themeMode == m) { update { it.copy(themeMode = m) } }
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.themeMode == m, onClick = { update { it.copy(themeMode = m) } })
                    Text(m.label)
                }
            }
        }

        // ---------- Povolenia ----------
        SectionCard("Povolenia") {
            Text("Skontrolovať povolenia potrebné na automatické sledovanie",
                Modifier.fillMaxWidth().clickable(onClick = onOpenPermissions).padding(16.dp))
        }

        // ---------- Účet ----------
        SectionCard("Účet") {
            val user = c.session.user()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(user?.name ?: "", fontWeight = FontWeight.SemiBold)
                Text(user?.email ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                OutlinedButton(onClick = { showDeleteData = true }, modifier = Modifier.fillMaxWidth()) { Text("Vymazať jazdy z telefónu") }
                Button(onClick = { showLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("Odhlásiť sa") }
            }
        }

        Text("Car Tracking ${sk.cartracker.BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
    }

    // ---------- dialógy ----------
    message?.let { msg ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }

    if (showBtPicker) {
        val devices = remember { bondedDevices(ctx) }
        AlertDialog(
            onDismissRequest = { showBtPicker = false },
            title = { Text("Vyberte Bluetooth auta") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("Žiadne spárované zariadenia. Najprv spárujte telefón s autom v nastaveniach Bluetooth.")
                    devices.forEach { (name, addr) ->
                        Text(name, Modifier.fillMaxWidth().clickable {
                            update { it.copy(carBtAddress = addr, carBtName = name, bluetoothTrigger = true) }
                            showBtPicker = false
                        }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBtPicker = false }) { Text("Zavrieť") } },
        )
    }

    if (showDeleteData) {
        AlertDialog(
            onDismissRequest = { showDeleteData = false },
            title = { Text("Vymazať jazdy z telefónu?") },
            text = { Text("Neodoslané jazdy sa stratia. Jazdy uložené na serveri ostanú.") },
            confirmButton = { TextButton(onClick = { showDeleteData = false; scope.launch { c.trips.deleteAllLocal() } }) { Text("Vymazať") } },
            dismissButton = { TextButton(onClick = { showDeleteData = false }) { Text("Zrušiť") } },
        )
    }

    if (showLogout) {
        val bio = s.biometricEnabled && c.session.hasBiometricToken()
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Odhlásiť sa?") },
            text = {
                Text(if (bio) "„Odhlásiť“ ponechá prihlásenie odtlačkom. „Zabudnúť zariadenie“ zruší prihlásenie aj na serveri."
                     else "Po odhlásení sa automatické sledovanie jázd zastaví.")
            },
            confirmButton = {
                TextButton(onClick = { showLogout = false; scope.launch { c.auth.logout(forget = !bio); onLoggedOut() } }) { Text("Odhlásiť") }
            },
            dismissButton = {
                if (bio) TextButton(onClick = { showLogout = false; scope.launch { c.auth.logout(forget = true); onLoggedOut() } }) { Text("Zabudnúť zariadenie") }
                else TextButton(onClick = { showLogout = false }) { Text("Zrušiť") }
            },
        )
    }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: android.content.Context): List<Pair<String, String>> = try {
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
        ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first } ?: emptyList()
} catch (e: SecurityException) { emptyList() }
