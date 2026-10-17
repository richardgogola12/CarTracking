package sk.cartracker.ui.screens

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TYPE_CAR
import sk.cartracker.data.local.TYPE_WALK
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatTime
import java.util.Calendar
import kotlin.math.roundToInt

/** Ručné pridanie zabudnutej jazdy / chôdze (bez trasy). */
@Composable
fun AddTripDialog(onDismiss: () -> Unit, onAdded: (Long) -> Unit) = TripFormDialog(null, onDismiss, onAdded)

/** Úprava existujúcej jazdy / chôdze: čas, km, odkiaľ / kam, vozidlo, typ, účel, kroky. Trasa GPS ostáva. */
@Composable
fun EditTripDialog(trip: TripEntity, onDismiss: () -> Unit, onSaved: () -> Unit) = TripFormDialog(trip, onDismiss) { onSaved() }

private fun kmText(m: Double) = if (m <= 0) "" else String.format(java.util.Locale("sk"), "%.2f", m / 1000).trimEnd('0').trimEnd(',')

@Composable
private fun TripFormDialog(edit: TripEntity?, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(edit?.type ?: TYPE_CAR) }
    var vehicles by remember { mutableStateOf<List<VehicleEntity>>(emptyList()) }
    var vehicle by remember { mutableStateOf(edit?.vehicleUuid) }
    var places by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        vehicles = c.vehicles.all().filter { !it.archived || it.uuid == edit?.vehicleUuid }
        if (edit == null || vehicle == null) vehicle = c.vehicles.active()?.uuid
        places = c.places.list().map { it.name }
    }
    val now = System.currentTimeMillis()
    var start by remember { mutableLongStateOf(edit?.startedAt ?: (now - 3_600_000L)) }
    var end by remember { mutableLongStateOf(edit?.endedAt ?: edit?.startedAt ?: (now - 3_000_000L)) }
    val fromInit = edit?.let { it.startPlace ?: it.startAddress }.orEmpty()
    val toInit = edit?.let { it.endPlace ?: it.endAddress }.orEmpty()
    var from by remember { mutableStateOf(fromInit) }
    var to by remember { mutableStateOf(toInit) }
    val kmInit = edit?.let { kmText(it.distanceM) }.orEmpty()
    var km by remember { mutableStateOf(kmInit) }
    var steps by remember { mutableStateOf(edit?.steps?.takeIf { it > 0 }?.toString().orEmpty()) }
    var business by remember { mutableStateOf(edit?.category == CATEGORY_BUSINESS) }
    var note by remember { mutableStateOf(edit?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pickTime(ts: Long, onPick: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        TimePickerDialog(ctx, { _, h, m ->
            onPick(Calendar.getInstance().apply { timeInMillis = ts; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0) }.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit == null) "Pridať záznam ručne" else "Upraviť záznam") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == TYPE_CAR, { type = TYPE_CAR }, { Text("🚗 Jazda") })
                    FilterChip(type == TYPE_WALK, { type = TYPE_WALK }, { Text("🚶 Chôdza") })
                }
                if (type == TYPE_CAR && vehicles.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vehicles.forEach { v -> FilterChip(vehicle == v.uuid, { vehicle = v.uuid }, { Text(v.label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        pickDate(ctx, start) { d ->
                            val dc = Calendar.getInstance().apply { timeInMillis = d }
                            val ns = Calendar.getInstance().apply { timeInMillis = start; set(dc.get(Calendar.YEAR), dc.get(Calendar.MONTH), dc.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
                            end += ns - start; start = ns
                        }
                    }) { Text(formatDate(start)) }
                    OutlinedButton(onClick = { pickTime(start) { start = it } }) { Text(formatTime(start)) }
                    OutlinedButton(onClick = { pickTime(end) { e -> end = if (e <= start && e + 86_400_000L - start < 86_400_000L) e + 86_400_000L else e } }) { Text(formatTime(end)) }
                }
                Text("Trvanie: " + if (end > start) formatDuration(end - start) else "–", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(from, { from = it }, label = { Text("Odkiaľ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(to, { to = it }, label = { Text("Kam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (places.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.forEach { p -> TextButton(onClick = { if (from.isBlank()) from = p else to = p }) { Text(p) } }
                }
                OutlinedTextField(km, { km = it }, label = { Text("Vzdialenosť (km)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (type == TYPE_WALK) OutlinedTextField(steps, { steps = it.filter(Char::isDigit) }, label = { Text("Kroky (nepovinné)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (type == TYPE_CAR) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!business, { business = false }, { Text("Súkromná") })
                    FilterChip(business, { business = true }, { Text("Služobná") })
                }
                OutlinedTextField(note, { note = it }, label = { Text("Účel / poznámka") }, modifier = Modifier.fillMaxWidth())
                if (type == TYPE_CAR) PurposeChips(note, to.ifBlank { null }) { note = it }
                Text(if (edit == null) "Ručný záznam nemá trasu na mape; v knihe jázd, štatistikách a náhradách sa počíta normálne."
                    else "Trasa GPS ostane, ako bola. Zmenené km a časy sa použijú v knihe jázd, štatistikách aj náhradách; zmena sa odošle na server.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = km.trim().replace(',', '.').toDoubleOrNull()
                error = when {
                    k == null || k <= 0 -> "Zadajte vzdialenosť v km"
                    end <= start -> "Koniec musí byť po začiatku"
                    end > System.currentTimeMillis() + 60_000 -> "Záznam nemôže byť v budúcnosti"
                    k > 3000 -> "Vzdialenosť je príliš veľká"
                    else -> null
                }
                if (error != null) return@TextButton
                if (edit != null) scope.launch {
                    val s = c.settings.current()
                    val walk = type == TYPE_WALK
                    val dist = if (km.trim() == kmInit) edit.distanceM else k!! * 1000   // nezmenené km ostanú presné
                    val h = (end - start) / 3_600_000.0
                    val cat = if (!walk && business) CATEGORY_BUSINESS else CATEGORY_PRIVATE
                    val updated = edit.copy(
                        type = type, startedAt = start, endedAt = end, distanceM = dist,
                        avgSpeedKmh = if (h > 0) dist / 1000 / h else 0.0,
                        steps = if (walk) (steps.toIntOrNull() ?: edit.steps.takeIf { edit.isWalk } ?: ((dist * 100) / s.strideCm.coerceAtLeast(1)).roundToInt()) else 0,
                        vehicleUuid = if (walk) null else vehicle, category = cat, note = note.trim(),
                        // zmenený text „odkiaľ / kam“ prepíše adresu aj názov miesta
                        startAddress = if (from.trim() != fromInit) from.trim().ifBlank { null } else edit.startAddress,
                        startPlace = if (from.trim() != fromInit) null else edit.startPlace,
                        endAddress = if (to.trim() != toInit) to.trim().ifBlank { null } else edit.endAddress,
                        endPlace = if (to.trim() != toInit) null else edit.endPlace,
                        score = if (walk) null else edit.score, maxSpeedKmh = if (walk) 0.0 else edit.maxSpeedKmh,
                        synced = false,
                    )
                    c.trips.updateTripRaw(updated)
                    if (!walk && cat != edit.category) c.settings.update { sk.cartracker.data.TripClassifier.learn(it, edit, cat) }
                    if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                    Toast.makeText(ctx, "Záznam upravený", Toast.LENGTH_SHORT).show()
                    onDone(edit.id)
                } else scope.launch {
                    val s = c.settings.current()
                    val walk = type == TYPE_WALK
                    val st = steps.toIntOrNull() ?: if (walk) ((k!! * 100_000) / s.strideCm.coerceAtLeast(1)).roundToInt() else 0
                    val id = c.trips.addManual(TripEntity(
                        uuid = "", userId = 0, startedAt = start, endedAt = end, distanceM = k!! * 1000,
                        type = type, steps = if (walk) st else 0, vehicleUuid = if (walk) null else vehicle,
                        category = if (!walk && business) CATEGORY_BUSINESS else CATEGORY_PRIVATE, note = note.trim(),
                        startAddress = from.trim().ifBlank { null }, endAddress = to.trim().ifBlank { null },
                    ))
                    if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                    Toast.makeText(ctx, "Záznam pridaný", Toast.LENGTH_SHORT).show()
                    onDone(id)
                }
            }) { Text(if (edit == null) "Pridať" else "Uložiť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}

/** Rozdelenie jazdy na dve – posuvníkom sa vyberie miesto (predvolene najdlhšie státie). */
@Composable
fun SplitTripDialog(trip: TripEntity, points: List<PointEntity>, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    if (points.size < 4) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Rozdeliť jazdu") },
            text = { Text("Na rozdelenie treba záznam s trasou (aspoň 4 body GPS). Ručne pridaný záznam sa rozdeliť nedá.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
        return
    }
    // kumulatívna vzdialenosť a najdlhšie státie ako návrh
    val cum = remember(points) {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val r = FloatArray(1)
            android.location.Location.distanceBetween(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng, r)
            out[i] = out[i - 1] + r[0]
        }
        out
    }
    val suggested = remember(points) {
        var best = points.size / 2; var bestGap = 0L; var stopStart = -1
        for (i in 1 until points.size - 1) {
            val slow = (points[i].speedKmh ?: 0f) < 3f
            if (slow && stopStart < 0) stopStart = i
            if ((!slow || i == points.size - 2) && stopStart >= 0) {
                val gap = points[i].ts - points[stopStart].ts
                if (gap > bestGap && stopStart >= 2 && i <= points.size - 3) { bestGap = gap; best = (stopStart + i) / 2 }
                stopStart = -1
            }
            val dt = points[i].ts - points[i - 1].ts
            if (dt > bestGap && i >= 2 && i <= points.size - 3) { bestGap = dt; best = i }
        }
        best to bestGap
    }
    var idx by remember { mutableFloatStateOf(suggested.first.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    val i = idx.roundToInt().coerceIn(2, points.size - 2)
    val p = points[i]
    val total = cum.last().coerceAtLeast(1.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rozdeliť jazdu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Posuňte bod rozdelenia. Prvá časť skončí pred ním, druhá začne v ňom.", style = MaterialTheme.typography.bodySmall)
                if (suggested.second > 60_000) Text("Návrh: najdlhšie státie (${formatDuration(suggested.second)})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("Rozdeliť o ${formatTime(p.ts)}", fontWeight = FontWeight.Bold)
                Slider(value = idx, onValueChange = { idx = it }, valueRange = 2f..(points.size - 2).toFloat())
                Text("1. časť: ${formatTime(trip.startedAt)}–${formatTime(points[i - 1].ts)} · ~${formatDistance(trip.distanceM * cum[i - 1] / total)}")
                Text("2. časť: ${formatTime(p.ts)}–${formatTime(trip.endedAt ?: points.last().ts)} · ~${formatDistance(trip.distanceM * (total - cum[i]) / total)}")
                Text("Rýchlosť v bode: ${(p.speedKmh ?: 0f).toInt()} km/h", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { c.trips.split(trip, p.ts, ctx) }
                        .onSuccess { id ->
                            val s = c.settings.current()
                            if (s.autoSync) SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
                            Toast.makeText(ctx, "Jazda bola rozdelená na dve", Toast.LENGTH_SHORT).show()
                            onDone(id)
                        }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Rozdelenie zlyhalo", Toast.LENGTH_LONG).show(); busy = false }
                }
            }) { Text("Rozdeliť") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
