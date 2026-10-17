package sk.cartracker.ui.screens

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.SpeedLimits
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity
import sk.cartracker.ui.formatTime

/** Smer jazdy v bode – z GPS, inak z polohy susedných bodov. */
private fun bearingAt(points: List<PointEntity>, p: PointEntity): Int? {
    p.bearing?.let { return it.toInt() }
    val i = points.indexOf(p)
    val a = points.getOrNull(i - 2) ?: points.getOrNull(i - 1) ?: return null
    val b = points.getOrNull(i + 2) ?: points.getOrNull(i + 1) ?: return null
    val r = FloatArray(2)
    android.location.Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, r)
    return ((r[1] + 360) % 360).toInt()
}

/**
 * Prekročenia rýchlosti v jazde s možnosťou opraviť limit („Moje limity“) – napr. zóna 30 alebo úsek so 70 v obci,
 * ktoré mapa nepozná. Oprava platí pre toto miesto (do 200 m) a smer jazdy pri ďalších jazdách.
 */
@Composable
fun SpeedingCard(points: List<PointEntity>) {
    val sp = remember(points) { points.filter { it.event == DriveEvent.SPEEDING } }
    if (sp.isEmpty()) return
    var fix by remember { mutableStateOf<PointEntity?>(null) }
    SectionCard("Prekročenia rýchlosti (${sp.size})") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            sp.take(30).forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatTime(p.ts)} · ${p.speedKmh?.toInt() ?: "?"} km/h", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { fix = p }) { Text("Opraviť limit") }
                }
            }
            Text("Bol tu iný limit (zóna 30, úsek so 70…)? Opravte ho – aplikácia si ho zapamätá pre toto miesto a smer jazdy a pri ďalších jazdách ho použije.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
    fix?.let { p -> LimitFixDialog(p, bearingAt(points, p)) { fix = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitFixDialog(p: PointEntity, bearing: Int?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var limit by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Skutočný limit v tomto mieste") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatTime(p.ts)} · išli ste ${p.speedKmh?.toInt() ?: "?"} km/h", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 130).forEach { v -> FilterChip(limit == v, { limit = v }, { Text("$v") }) }
                }
                Text("Platí do 200 m od miesta" + (if (bearing != null) " a iba v tomto smere jazdy." else ".") +
                    " Zoznam opráv nájdete v Nastavenia → Štýl jazdy a rýchlosť → Moje limity.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = limit != null, onClick = {
                val v = limit ?: return@TextButton
                scope.launch {
                    c.settings.update { s ->
                        val r = FloatArray(1)
                        // nahradí staršiu opravu na tom istom mieste a v tom istom smere
                        val rest = SpeedLimits.parseCustom(s.customLimits).filterNot { e ->
                            android.location.Location.distanceBetween(e.lat, e.lng, p.lat, p.lng, r)
                            r[0] < 60f && (e.bearing == null || bearing == null || kotlin.math.abs(((e.bearing - bearing) % 360 + 540) % 360 - 180) <= 45)
                        }
                        s.copy(customLimits = SpeedLimits.formatCustom((rest + SpeedLimits.Custom(p.lat, p.lng, v, bearing)).takeLast(300)))
                    }
                    Toast.makeText(ctx, "Limit $v km/h uložený pre toto miesto", Toast.LENGTH_SHORT).show()
                    onClose()
                }
            }) { Text("Uložiť") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Zrušiť") } },
    )
}
