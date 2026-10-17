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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.HelpOutline

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenPermissions: () -> Unit, onLoggedOut: () -> Unit, onOpenHelp: () -> Unit = {}, onOpenVehicles: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
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
    var busy by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            busy = "Vytváram zálohu…"
            message = runCatching { "Záloha vytvorená ✅\n\n" + sk.cartracker.data.Backup.export(ctx, uri) }
                .getOrElse { "Zálohu sa nepodarilo vytvoriť: ${it.message}" }
            busy = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    fun doRestore(uri: android.net.Uri, withSettings: Boolean) {
        scope.launch {
            busy = "Obnovujem…"
            message = runCatching { "Obnova dokončená ✅\n\n" + sk.cartracker.data.Backup.restore(ctx, uri, withSettings) }
                .getOrElse { "Obnova zlyhala: ${it.message}" }
            busy = null
            c.vehicles.ensureDefault()
            if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
        }
    }

    val btPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { showBtPicker = true } else { message = "Bez povolenia „Zariadenia v okolí“ nemožno vybrať Bluetooth auta." }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nastavenia", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("Ťuknutím na skupinu ju rozbalíte. Všetky nastavenia sa ukladajú aj na server a obnovia sa na novom telefóne.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---------- Účet, server a záloha ----------
        Group("Účet, server a záloha", Icons.Filled.AccountCircle, "${c.session.user()?.email ?: ""} · " + s.serverUrl.removePrefix("https://").substringBefore("/")) {
            Sub("Účet") {
                val user = c.session.user()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(user?.name ?: "", fontWeight = FontWeight.SemiBold)
                    Text(user?.email ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    OutlinedButton(onClick = {
                        sk.cartracker.sync.RestoreWorker.start(ctx)
                        message = "Sťahujem údaje zo servera – priebeh je na Prehľade."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Stiahnuť údaje a nastavenia zo servera") }
                    Text("Po prihlásení na novom telefóne sa to stane samo. Čo už v telefóne je, sa nezdvojí.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        sk.cartracker.sync.BackupCheckWorker.runOnce(ctx, s.syncWifiOnly, replace = true)
                        message = "Kontrolujem zálohu na serveri – chýbajúce údaje sa odošlú."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Skontrolovať zálohu na serveri") }
                    val last = remember(message) { sk.cartracker.data.ServerBackup.lastCheck(ctx) }
                    Text(last?.let { (at, res) -> "Posledná kontrola ${sk.cartracker.ui.formatDateTime(at)}: $res" }
                        ?: "Kontrola zálohy prebehne automaticky (raz hneď, potom každý týždeň).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { showDeleteData = true }, modifier = Modifier.fillMaxWidth()) { Text("Vymazať jazdy z telefónu") }
                    Button(onClick = { showLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("Odhlásiť sa") }
                }
            }

            Sub("Server a synchronizácia") {
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
                            if (!url.startsWith("https://") || url.length < 12) { message = "Adresa musí začínať https:// – nešifrované spojenie nie je povolené" }
                            else { update { it.copy(serverUrl = url) }; message = "Adresa servera uložená" }
                        }) { Text("Uložiť") }
                        OutlinedButton(onClick = { SyncWorker.enqueueNow(ctx, false); message = "Synchronizácia spustená" }) { Text("Synchronizovať teraz") }
                    }
                }
            }

            Sub("Záloha a obnova") {
                Text("Všetky jazdy s trasami, chôdza, vozidlá, miesta, tankovania, pripomienky a nastavenia v jednom súbore. " +
                    "Uložte ho napr. na Google Disk – hodí sa pri výmene telefónu.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null, onClick = {
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        exportLauncher.launch("cartracking-zaloha-$date.ctbak")
                    }) { Text("Zálohovať") }
                    OutlinedButton(enabled = busy == null, onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Obnoviť zo zálohy") }
                }
                busy?.let { Text(it, Modifier.padding(start = 16.dp, bottom = 12.dp)) }
            }
        }

        // ---------- Detekcia jazdy ----------
        Group("Detekcia jazdy", Icons.Filled.DirectionsCar, (if (s.autoDetection) "zapnutá" else "vypnutá") + " · od ${s.minDriveSpeedKmh} km/h · koniec po ${s.stopDelayMin} min státia · GPS ${s.gpsIntervalSec} s") {
            Sub("Automatická detekcia jazdy") {
                SwitchRow("Automaticky sledovať jazdu autom", "Spustí sa sama, keď telefón rozpozná jazdu vo vozidle",
                    s.autoDetection) { on ->
                    update { it.copy(autoDetection = on) }
                    scope.launch { if (on || s.walkTracking) ActivityDetectionManager.register(ctx) else ActivityDetectionManager.unregister(ctx) }
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

            Sub("GPS") {
                SliderRow("Interval záznamu polohy", s.gpsIntervalSec, 1..15, 1, "s",
                    "Kratší interval = presnejšia trasa, vyššia spotreba batérie.") { v -> update { it.copy(gpsIntervalSec = v) } }
                SwitchRow("Vysoká presnosť (GPS)", "Vypnutím ušetríte batériu, trasa bude menej presná", s.highAccuracy) { on -> update { it.copy(highAccuracy = on) } }
                SwitchRow("Šetriť batériu počas jazdy", "Na diaľnici redšie body, pri státí (kolóna, semafor) úsporný režim – pri rozjazde hneď späť. Spotrebu ukazuje Diagnostika.",
                    s.adaptiveGps) { on -> update { it.copy(adaptiveGps = on) } }
            }
        }

        // ---------- Chôdza a kroky ----------
        Group("Chôdza a kroky", Icons.AutoMirrored.Filled.DirectionsWalk, (if (s.walkTracking) "sleduje sa" else "vypnutá") + " · vzdialenosť " + (if (s.walkDistanceGps) "z GPS" else "z krokov (${s.strideCm} cm)") + " · cieľ ${s.dailyStepGoal}") {
            Sub("Chôdza") {
                SwitchRow("Automaticky sledovať chôdzu", "Kroky, vzdialenosť a trasa pešo", s.walkTracking) { on ->
                    update { it.copy(walkTracking = on) }
                    scope.launch { if (on || s.autoDetection) ActivityDetectionManager.register(ctx) else ActivityDetectionManager.unregister(ctx) }
                }
                SwitchRow("Iba v pracovnom čase", "Mimo pracovného času sa chôdza nezaznamenáva", s.walkWorkHoursOnly, enabled = s.walkTracking) { on ->
                    update { it.copy(walkWorkHoursOnly = on) }
                }
                if ((s.walkTracking && s.walkWorkHoursOnly) || s.ruleWorkTime || s.ruleWeekendPrivate) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne").forEachIndexed { i, d ->
                            val day = i + 1
                            val on = day in s.workDaySet
                            FilterChip(on, {
                                val set = if (on) s.workDaySet - day else s.workDaySet + day
                                update { it.copy(workDays = set.sorted().joinToString(",")) }
                            }, { Text(d) })
                        }
                    }
                    SliderRow("Začiatok práce", s.workFromMin, 0..1410, 30, "", format = { hm(it) }) { v -> update { it.copy(workFromMin = v) } }
                    SliderRow("Koniec práce", s.workToMin, 30..1440, 30, "", format = { hm(it) }) { v -> update { it.copy(workToMin = v) } }
                }
                Text("Vzdialenosť chôdze merať", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SegmentedButton(!s.walkDistanceGps, { update { it.copy(walkDistanceGps = false) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Z krokov") }
                    SegmentedButton(s.walkDistanceGps, { update { it.copy(walkDistanceGps = true) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Z GPS") }
                }
                Text(
                    if (s.walkDistanceGps) "GPS meria skutočne prejdenú trasu – presnejšie vonku. V budove GPS nefunguje, vtedy sa použijú kroky × dĺžka kroku. Kroky sa počítajú stále."
                    else "Kroky × dĺžka kroku – funguje aj v budove a šetrí batériu. Presnosť závisí od nastavenej dĺžky kroku.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderRow("Dĺžka kroku", s.strideCm, 50..100, 1, "cm",
                    if (s.walkDistanceGps) "Použije sa, keď GPS nemá signál." else "Vzdialenosť = kroky × dĺžka kroku.") { v -> update { it.copy(strideCm = v) } }
                SliderRow("Ukončiť po nečinnosti", s.walkStopMin, 1..15, 1, "min",
                    "Koľko minút bez krokov ukončí záznam chôdze.") { v -> update { it.copy(walkStopMin = v) } }
                SliderRow("Minimálna vzdialenosť", s.walkMinDistanceM, 0..1000, 50, "m",
                    "Kratšie presuny (napr. ku kávovaru) sa neukladajú.") { v -> update { it.copy(walkMinDistanceM = v) } }
                SliderRow("Interval GPS pri chôdzi", s.walkGpsIntervalSec, 5..60, 5, "s",
                    "Dlhší interval šetrí batériu počas celého pracovného dňa.") { v -> update { it.copy(walkGpsIntervalSec = v) } }
                SwitchRow("Notifikácia pri začiatku a konci chôdze", null, s.walkNotify) { on -> update { it.copy(walkNotify = on) } }
                SwitchRow("Vibrovať pri začiatku a konci chôdze", null, s.walkVibrate) { on -> update { it.copy(walkVibrate = on) } }
                SliderRow("Denný cieľ krokov", s.dailyStepGoal, 1000..30000, 1, "", "Zobrazuje sa vo widgete ako krúžok.",
                    format = { String.format(java.util.Locale("sk"), "%,d krokov", it) }) { v -> update { it.copy(dailyStepGoal = v) } }
            }

            Sub("Kalibrácia dĺžky kroku podľa GPS") {
                val est = s.strideFromGpsCm
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (est != null) "Z chôdze vonku (${s.strideCalWalks}×, ${String.format(java.util.Locale("sk"), "%,d", s.strideCalSteps)} krokov) vychádza dĺžka kroku $est cm (nastavené ${s.strideCm} cm)."
                        else "Zbieram údaje z chôdze vonku s dobrým GPS (aspoň 3 chôdze po 300 m; zatiaľ ${s.strideCalWalks}).",
                        style = MaterialTheme.typography.bodySmall)
                    if (est != null && est != s.strideCm) OutlinedButton(onClick = { update { it.copy(strideCm = est) } }) { Text("Použiť $est cm") }
                }
                SwitchRow("Automaticky upravovať dĺžku kroku", "Po každej vhodnej chôdzi vonku sa dĺžka kroku spresní", s.strideAuto) { on ->
                    update { x -> x.copy(strideAuto = on, strideCm = if (on) x.strideFromGpsCm ?: x.strideCm else x.strideCm) }
                }
                if (s.strideCalWalks > 0) TextButton(onClick = { update { it.copy(strideCalMeters = 0, strideCalSteps = 0, strideCalWalks = 0) } },
                    modifier = Modifier.padding(horizontal = 8.dp)) { Text("Vynulovať nazbierané údaje") }
            }
        }

        // ---------- Vozidlá, miesta, tankovanie ----------
        Group("Vozidlá, miesta, tankovanie", Icons.Filled.LocalGasStation, "vozidlá, Bluetooth, miesta, pripomienky, tankovanie, náklady, časté trasy") {
            Sub("Vozidlá") {
                Text("Názov, EČV, palivo, spotreba, cena, tachometer a Bluetooth sa nastavujú pri každom vozidle.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Spravovať vozidlá", Modifier.fillMaxWidth().clickable(onClick = onOpenVehicles).padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                SwitchRow("Spustiť sledovanie po pripojení Bluetooth vozidla", "Vozidlo sa zároveň samo nastaví ako aktívne",
                    s.bluetoothTrigger) { on -> update { it.copy(bluetoothTrigger = on) } }
                SwitchRow("Sledovať iba keď je pripojené vozidlo", "Ignoruje autobus, vlak, taxík…", s.bluetoothRequired) { on -> update { it.copy(bluetoothRequired = on) } }
            }

            Sub("Miesta, pripomienky, tankovanie") {
                Text("Miesta (Domov, Práca, Klient…)", Modifier.fillMaxWidth().clickable { onNavigate("places") }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("Pripomienky (STK, EK, olej, známka, poistka…)", Modifier.fillMaxWidth().clickable { onNavigate("reminders") }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("Tankovanie – pri vozidle v časti Vozidlá (ikona pumpy)", Modifier.fillMaxWidth().clickable(onClick = onOpenVehicles).padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("Náklady na auto (servis, poistenie, známka… cena za km)", Modifier.fillMaxWidth().clickable {
                    scope.launch { val v = c.vehicles.active() ?: c.vehicles.all().firstOrNull(); if (v != null) onNavigate("expenses/${v.uuid}") else onOpenVehicles() }
                }.padding(16.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("Časté trasy", Modifier.fillMaxWidth().clickable { onNavigate("routes") }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---------- Kniha jázd a zlučovanie ----------
        Group("Kniha jázd a zlučovanie", Icons.AutoMirrored.Filled.MenuBook, "predvolene " + (if (s.defaultCategory == CATEGORY_BUSINESS) "služobná" else "súkromná") + " · zlučovanie " + (if (s.autoMerge) "zapnuté" else "vypnuté")) {
            Sub("Kniha jázd") {
                Text("Predvolený typ novej jazdy", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                listOf(CATEGORY_PRIVATE to "Súkromná", CATEGORY_BUSINESS to "Služobná").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().selectable(s.defaultCategory == key) { update { it.copy(defaultCategory = key) } }
                        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.defaultCategory == key, onClick = { update { it.copy(defaultCategory = key) } })
                        Text(label)
                    }
                }
                SwitchRow("Zisťovať adresy začiatku a cieľa", "Ulica a mesto v zozname jázd a v knihe jázd (potrebuje internet)", s.detectAddresses) { on -> update { it.copy(detectAddresses = on) } }
                var addrBusy by remember { mutableStateOf<String?>(null) }
                Text(addrBusy ?: "Opraviť adresy jázd (posledných 90 dní)",
                    Modifier.fillMaxWidth().clickable(enabled = addrBusy == null) {
                        addrBusy = "Zisťujem adresy…"
                        scope.launch {
                            val n = runCatching {
                                c.trips.refreshAddresses(ctx, 90) { i, total -> addrBusy = "Zisťujem adresy… $i / $total" }
                            }
                            addrBusy = null
                            message = n.fold({ "Adresy prepočítané ✅\nZmenených jázd: $it\n\nObec sa teraz zisťuje z OpenStreetMap a adresa z najpresnejšieho bodu na začiatku a konci jazdy." },
                                { "Nepodarilo sa: ${it.message}" })
                            if (s.autoSync) sk.cartracker.sync.SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                        }
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            Sub("Šablóny účelu jazdy") {
                var tpl by remember(s.purposeTemplates) { mutableStateOf(s.purposeTemplates) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ťukacie návrhy pod poľom „Účel jazdy“ v detaile jazdy. Každý účel na samostatný riadok. Okrem nich sa ponúknu účely, ktoré ste použili pri jazdách do toho istého cieľa, a naposledy použité.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(tpl, { tpl = it.take(2000) }, label = { Text("Šablóny (jedna na riadok)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    if (tpl != s.purposeTemplates) Button(onClick = { update { it.copy(purposeTemplates = tpl.trim()) } }) { Text("Uložiť šablóny") }
                }
            }

            Sub("Účel jazdy automaticky") {
                SwitchRow("Pýtať sa po jazde v notifikácii", "Tlačidlá Služobná / Súkromná – netreba otvárať aplikáciu", s.askCategory) { on -> update { it.copy(askCategory = on) } }
                val learned = remember(s.learnedRoutes) { sk.cartracker.data.TripClassifier.parse(s.learnedRoutes).size }
                SwitchRow("Učiť sa z mojich rozhodnutí", "Keď jazdu na rovnakej trase zaradíte aspoň 2× rovnako, ďalšie sa zaradia samy" +
                    (if (learned > 0) " · naučené trasy: $learned" else ""), s.learnCategory) { on -> update { it.copy(learnCategory = on) } }
                if (learned > 0) Text("Zabudnúť naučené trasy", Modifier.fillMaxWidth().clickable { update { it.copy(learnedRoutes = "") } }.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.primary)
                SwitchRow("V pracovnom čase služobná", "Pracovné dni a čas sa nastavujú v časti Chôdza a kroky (${workSummary(s)})", s.ruleWorkTime) { on -> update { it.copy(ruleWorkTime = on) } }
                SwitchRow("V deň voľna súkromná", "Víkend a ďalšie dni mimo pracovných dní", s.ruleWeekendPrivate) { on -> update { it.copy(ruleWeekendPrivate = on) } }
                Text("Poradie: naučená trasa → miesto s nastaveným účelom (napr. Klient) → pravidlá času → predvolený typ.",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Sub("Kontrola jázd") {
                SwitchRow("Upozorniť na podozrivé jazdy", "Na Prehľade karta, keď sa nájde zabudnutý koniec, rozdelená jazda, výpadok GPS a pod.", s.tripCheck) { on -> update { it.copy(tripCheck = on) } }
                Text("Otvoriť kontrolu jázd", Modifier.fillMaxWidth().clickable { onNavigate("tripcheck") }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            Sub("Zlučovanie jázd") {
                SwitchRow("Automaticky zlúčiť po krátkej prestávke", "Napr. zastávka na pumpe – vznikne jedna jazda namiesto dvoch",
                    s.autoMerge) { on -> update { it.copy(autoMerge = on) } }
                if (s.autoMerge) {
                    SliderRow("Max. prestávka – auto", s.autoMergeGapMin, 1..60, 1, "min") { v -> update { it.copy(autoMergeGapMin = v) } }
                    SliderRow("Max. prestávka – chôdza", s.walkMergeGapMin, 1..30, 1, "min") { v -> update { it.copy(walkMergeGapMin = v) } }
                    SliderRow("Max. vzdialenosť konca a začiatku", s.autoMergeMaxDistanceM, 100..5000, 100, "m",
                        "Ak nová jazda začne ďalej, ide o samostatnú jazdu. Jazdy iným vozidlom sa nezlučujú.") { v -> update { it.copy(autoMergeMaxDistanceM = v) } }
                }
                Text("Ručne: v záložke Jazdy podržte jazdu, označte ďalšie a ťuknite na Zlúčiť; alebo v detaile jazdy ikona zlúčenia s nasledujúcou.",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- Štýl jazdy a rýchlosť ----------
        val limitMode = when { !s.roadLimits -> 0; !s.useRoadSigns -> 1; else -> 2 }
        val customCount = remember(s.customLimits) { sk.cartracker.data.SpeedLimits.parseCustom(s.customLimits).size }
        Group("Štýl jazdy a rýchlosť", Icons.Filled.Speed, (if (s.drivingEvents) "hodnotenie zapnuté" else "hodnotenie vypnuté") + " · " +
            listOf("jednotný limit ${s.speedAlertKmh} km/h", "limit podľa typu cesty", "limity zo značiek v mape")[limitMode] +
            (if (customCount > 0) " · moje limity: $customCount" else "")) {
            Sub("Štýl jazdy a rýchlosť") {
                SwitchRow("Hodnotiť štýl jazdy", "Prudké brzdenie a zrýchlenie, skóre 0–100 pre každú jazdu", s.drivingEvents) { on -> update { it.copy(drivingEvents = on) } }
                Text("Ako určovať rýchlostný limit", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold)
                listOf(
                    Triple(0, "Jednotný limit", "Jedna hranica všade (napr. 130 km/h)."),
                    Triple(1, "Podľa typu cesty", "Obec 50 / mimo obce 90 / diaľnica 130 (limity sa dajú zmeniť pri vozidle). Značky sa neberú do úvahy."),
                    Triple(2, "Podľa skutočných značiek z mapy", "Limit cesty, po ktorej idete, z OpenStreetMap – zóny 30, úseky so 70 v obci, limity v jednom smere. Kde mapa limit nemá, platí typ cesty."),
                ).forEach { (k, title, sub) ->
                    Row(Modifier.fillMaxWidth().selectable(limitMode == k) {
                        update { it.copy(roadLimits = k > 0, useRoadSigns = k == 2) }
                    }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(limitMode == k, onClick = { update { it.copy(roadLimits = k > 0, useRoadSigns = k == 2) } })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text(title)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (limitMode == 0) SliderRow("Jednotný rýchlostný limit", s.speedAlertKmh, 50..200, 10, "km/h") { v -> update { it.copy(speedAlertKmh = v) } }
                SliderRow("Tolerancia", s.speedToleranceKmh, 0..20, 1, "km/h",
                    "O koľko môže GPS rýchlosť prekročiť limit, kým sa to počíta ako prekročenie.") { v -> update { it.copy(speedToleranceKmh = v) } }
                SwitchRow("Upozorniť vibráciou pri prekročení", "Najviac raz za minútu", s.speedAlert) { on -> update { it.copy(speedAlert = on) } }
                if (limitMode == 2) {
                    var tilesCount by remember { mutableStateOf(sk.cartracker.data.SpeedLimits.diskCount()) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Limity z mapy uložené v telefóne: $tilesCount úsekov (~1 km², platia 30 dní, fungujú aj bez internetu).",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tilesCount > 0) TextButton(onClick = { sk.cartracker.data.SpeedLimits.clearDisk(); tilesCount = 0 }) { Text("Vymazať") }
                    }
                }
            }
            if (limitMode > 0) Sub("Moje limity") {
                Text("Kde mapa limit nepozná alebo ho má zle, opravte ho v detaile jazdy pri prekročení rýchlosti (Opraviť limit). " +
                    "Oprava platí do 200 m od miesta a v danom smere jazdy, má prednosť pred mapou a ukladá sa aj na server.",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val list = remember(s.customLimits) { sk.cartracker.data.SpeedLimits.parseCustom(s.customLimits) }
                if (list.isEmpty()) Text("Zatiaľ žiadne opravy.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                list.forEachIndexed { i, cl ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${cl.limit} km/h", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                        Text(String.format(java.util.Locale.US, "%.5f, %.5f", cl.lat, cl.lng) + (cl.bearing?.let { " · smer $it°" } ?: ""),
                            Modifier.weight(1f).clickable {
                                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("geo:${cl.lat},${cl.lng}?q=${cl.lat},${cl.lng}(Limit ${cl.limit})"))) }
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { update { it.copy(customLimits = sk.cartracker.data.SpeedLimits.formatCustom(list.filterIndexed { j, _ -> j != i })) } }) { Text("Zmazať") }
                    }
                }
                if (list.isNotEmpty()) Text("Zmazať všetky", Modifier.fillMaxWidth().clickable { update { it.copy(customLimits = "") } }.padding(16.dp),
                    color = MaterialTheme.colorScheme.error)
            }
        }

        // ---------- Zvuky ----------
        Group("Zvuky", Icons.AutoMirrored.Filled.VolumeUp, if (!s.sound) "vypnuté" else
            "zapnuté · hlasitosť ${s.soundVolume} %" + (if (s.soundIgnoreSilent) " · aj v tichom režime" else "")) {
            Sub("Zvuky udalostí") { SoundSettingsBody(s) { t -> update(t) } }
        }

        // ---------- Notifikácie a zhrnutia ----------
        Group("Notifikácie a zhrnutia", Icons.Filled.Notifications, (if (s.notifyOnStart || s.notifyOnStop) "oznámenia zapnuté" else "bez oznámení") + (if (s.vibrate) " · vibrácie" else "") + (if (s.monthlySummary) " · mesačné zhrnutie" else "")) {
            Sub("Notifikácie a vibrácie") {
                SwitchRow("Oznámiť zapnutie režimu jazdy", null, s.notifyOnStart) { on -> update { it.copy(notifyOnStart = on) } }
                SwitchRow("Oznámiť vypnutie režimu jazdy", "So súhrnom jazdy (km, čas, priemer)", s.notifyOnStop) { on -> update { it.copy(notifyOnStop = on) } }
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

            Sub("Zhrnutia") {
                SwitchRow("Mesačné zhrnutie", "1. deň v mesiaci notifikácia: km, náklady, kroky a porovnanie s predchádzajúcim mesiacom",
                    s.monthlySummary) { on -> update { it.copy(monthlySummary = on) } }
            }
        }

        // ---------- Mapa a offline mapy ----------
        Group("Mapa a offline mapy", Icons.Filled.Map, s.mapStyle.label + (if (s.offlineMaps) " · režim offline" else "")) {
            Sub("Mapa") {
                MapStyle.entries.filter { it.shown }.forEach { m ->
                    Row(Modifier.fillMaxWidth().selectable(s.mapStyle == m) { update { it.copy(mapStyle = m) } }
                        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.mapStyle == m, onClick = { update { it.copy(mapStyle = m) } })
                        Text(m.label)
                    }
                }
                SwitchRow("Režim offline", "Mapy iba z uložených dlaždíc – bez internetu", s.offlineMaps) { on -> update { it.copy(offlineMaps = on) } }
                SliderRow("Miesto pre uložené mapy", s.mapCacheMb, 100..4000, 1, "MB",
                    "Po zmene sa prejaví pri ďalšom spustení aplikácie.") { v -> update { it.copy(mapCacheMb = v) } }
                Text("Offline mapy – uložené dlaždice", Modifier.fillMaxWidth().clickable { onNavigate("offline") }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Sub("Preriediť staré trasy") {
                SliderRow("Preriediť trasy staršie ako", s.thinAfterMonths, 0..36, 1, "mes.",
                    "Šetrí miesto v telefóne: v trasách starších jázd ostane bod asi každých 60 m (v zákrutách hustejšie), udalosti ostanú. " +
                        "Mažú sa len body uložené na serveri – tam ostane plná trasa. Kilometre a kniha jázd sa nemenia. 0 = nepreriedovať.",
                    format = { if (it == 0) "nikdy" else "$it mes." }) { v -> update { it.copy(thinAfterMonths = v) } }
                var thinMsg by remember { mutableStateOf<String?>(null) }
                if (s.thinAfterMonths > 0) Text(thinMsg ?: "Preriediť teraz", Modifier.fillMaxWidth().clickable(enabled = thinMsg == null) {
                    thinMsg = "Prerieďujem…"
                    scope.launch {
                        val n = runCatching { sk.cartracker.sync.PointThinning.run(ctx) }.getOrDefault(0)
                        thinMsg = null
                        message = if (n > 0) "Hotovo ✅ Odstránených $n bodov trasy." else "Nie je čo preriediť (jazdy sú už preriedené alebo ešte nie sú na serveri)."
                    }
                }.padding(16.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---------- Widgety a rýchla lišta ----------
        Group("Widgety, lišta a skratky", Icons.Filled.Widgets, "widgety na ploche, tlačidlá Jazda / Chôdza v lište, skratky na ikone") {
            Sub("Tlačidlá v rýchlej lište") {
                Text("Spustenie alebo ukončenie jazdy / chôdze jedným ťuknutím zo stiahnutej hornej lišty.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sk.cartracker.service.BaseTrackTile.requestAdd(ctx, false) { message = it } }) { Text("🚗 Pridať Jazda") }
                    OutlinedButton(onClick = { sk.cartracker.service.BaseTrackTile.requestAdd(ctx, true) { message = it } }) { Text("🚶 Pridať Chôdza") }
                }
            }

            Sub("Skratky na ikone aplikácie") {
                Text("Podržte prst na ikone Trasníka na ploche: Začať jazdu, Začať chôdzu, Kde je auto (navigácia) a Tankovanie. " +
                    "Skratku môžete pretiahnuť aj na plochu ako samostatnú ikonu. Druhé ťuknutie na Začať jazdu / chôdzu záznam ukončí.",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            Sub("Widgety na ploche") {
                Text("Podržte prst na ploche → Widgety → Trasník. K dispozícii je hlavný widget a menšie: tlačidlá, kroky, parkovanie a mesiac.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---------- Vzhľad a zabezpečenie ----------
        Group("Vzhľad a zabezpečenie", Icons.Filled.Palette, s.themeMode.label + (if (s.biometricEnabled) " · odtlačok prsta" else "")) {
            Sub("Vzhľad") {
                ThemeMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().selectable(s.themeMode == m) { update { it.copy(themeMode = m) } }
                        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.themeMode == m, onClick = { update { it.copy(themeMode = m) } })
                        Text(m.label)
                    }
                }
            }

            Sub("Zabezpečenie") {
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
        }

        // ---------- Diagnostika a povolenia ----------
        Group("Diagnostika a povolenia", Icons.Filled.BugReport, (if (s.diagnostics) "diagnostika zapnutá" else "diagnostika vypnutá") + " · kontrola povolení") {
            Sub("Povolenia") {
                Text("Skontrolovať povolenia potrebné na automatické sledovanie",
                    Modifier.fillMaxWidth().clickable(onClick = onOpenPermissions).padding(16.dp))
            }

            Sub("Diagnostika detekcie") {
                Text("Záznam udalostí: čo hlásil systém, aké rýchlosti namerala GPS a prečo sa jazda spustila, nespustila alebo ukončila.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Zaznamenávať diagnostiku", null, s.diagnostics) { on -> update { it.copy(diagnostics = on) } }
                Text("Otvoriť diagnostiku", Modifier.fillMaxWidth().clickable { onNavigate("diagnostics") }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---------- Pomoc ----------
        Group("Pomoc a aktualizácie", Icons.AutoMirrored.Filled.HelpOutline, "návod a dokumentácia, aktualizácia aplikácie") {
            Sub("Pomoc") {
                Text("Návod a dokumentácia (aj na tlač)", Modifier.fillMaxWidth().clickable(onClick = onOpenHelp).padding(16.dp))
            }
            Sub("Aktualizácie") {
                SwitchRow("Upozorniť na novú verziu", "Raz denne sa pozrie na GitHub (github.com/richardgogola12/CarTracking), či je nové zostavenie", s.updateCheck) { on -> update { it.copy(updateCheck = on) } }
                Text("Skontrolovať aktualizáciu teraz", Modifier.fillMaxWidth().clickable { sk.cartracker.ui.MainActivity.showUpdate.value = true }.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }

        Text("Trasník ${sk.cartracker.BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
    }

    // ---------- dialógy ----------
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Obnoviť zo zálohy?") },
            text = { Text("Údaje zo zálohy sa PRIDAJÚ k súčasným – nič sa neprepíše ani nezmaže; čo už v telefóne je, sa preskočí. " +
                "Obnovené jazdy sa odošlú aj na server.") },
            confirmButton = {
                TextButton(onClick = { restoreUri = null; doRestore(uri, true) }) { Text("Obnoviť aj nastavenia") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { restoreUri = null }) { Text("Zrušiť") }
                    TextButton(onClick = { restoreUri = null; doRestore(uri, false) }) { Text("Iba údaje") }
                }
            },
        )
    }
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

private fun hm(min: Int): String = String.format(java.util.Locale("sk"), "%d:%02d", min / 60, min % 60)

/** Rozbaľovacia skupina nastavení s ikonou a krátkym súhrnom. */
@Composable
private fun Group(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, summary: String, content: @Composable () -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!open) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            androidx.compose.material3.Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Zbaliť" else "Rozbaliť")
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 8.dp)) { content() }
        }
    }
}

/** Podsekcia v skupine (nadpis + obsah, bez ďalšej karty). */
@Composable
private fun Sub(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        content()
    }
}

private fun workSummary(s: sk.cartracker.data.AppSettings): String {
    val names = listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")
    val days = s.workDaySet.sorted().mapNotNull { names.getOrNull(it - 1) }.joinToString(" ")
    fun hm(m: Int) = String.format(java.util.Locale.US, "%d:%02d", m / 60, m % 60)
    return "$days ${hm(s.workFromMin)}–${hm(s.workToMin)}"
}
