package sk.cartracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.cartracker.container
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.formatDate
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Opakovaná trasa (napr. Domov → Práca) a jej jazdy. */
data class FrequentRoute(val from: String, val to: String, val trips: List<TripEntity>) {
    val durations get() = trips.map { (it.endedAt ?: it.startedAt) - it.startedAt }
    val avgMs get() = durations.average().toLong()
    val avgKm get() = trips.map { it.distanceM }.average() / 1000.0
}

private class Cluster(var lat: Double, var lng: Double, var n: Int, val names: MutableMap<String, Int> = mutableMapOf())

private fun dist(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLng - aLng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

private fun shortName(place: String?, address: String?): String? =
    place ?: address?.split(",")?.map { it.trim() }?.let { parts ->
        // „Ulica 5, 811 01 Mesto, Slovensko“ → „Ulica 5, Mesto“
        listOfNotNull(parts.getOrNull(0), parts.getOrNull(1)?.replace(Regex("^\\d{3} ?\\d{2} "), "")).joinToString(", ")
    }

/**
 * Zoskupí jazdy podľa začiatku a cieľa (do 350 m) – smer sa rozlišuje (Domov → Práca ≠ Práca → Domov).
 * Vráti trasy s aspoň [minTrips] jazdami, najčastejšie prvé.
 */
fun frequentRoutes(trips: List<TripEntity>, minTrips: Int = 3): List<FrequentRoute> {
    val clusters = mutableListOf<Cluster>()
    fun clusterOf(lat: Double, lng: Double, name: String?): Int {
        val idx = clusters.indexOfFirst { dist(it.lat, it.lng, lat, lng) < 350 }
        val c = if (idx >= 0) clusters[idx] else Cluster(lat, lng, 0).also { clusters += it }
        c.lat = (c.lat * c.n + lat) / (c.n + 1); c.lng = (c.lng * c.n + lng) / (c.n + 1); c.n++
        if (name != null) c.names[name] = (c.names[name] ?: 0) + 1
        return if (idx >= 0) idx else clusters.lastIndex
    }
    val groups = linkedMapOf<Pair<Int, Int>, MutableList<TripEntity>>()
    for (t in trips.filter { !it.isWalk && it.distanceM >= 1000 && it.endedAt != null }.sortedBy { it.startedAt }) {
        val sLat = t.startLat ?: continue; val sLng = t.startLng ?: continue
        val eLat = t.endLat ?: continue; val eLng = t.endLng ?: continue
        val a = clusterOf(sLat, sLng, shortName(t.startPlace, t.startAddress))
        val b = clusterOf(eLat, eLng, shortName(t.endPlace, t.endAddress))
        if (a == b) continue // okruh
        groups.getOrPut(a to b) { mutableListOf() } += t
    }
    fun label(i: Int) = clusters[i].names.maxByOrNull { it.value }?.key
        ?: String.format(java.util.Locale.US, "%.4f, %.4f", clusters[i].lat, clusters[i].lng)
    return groups.filter { it.value.size >= minTrips }
        .map { (k, l) -> FrequentRoute(label(k.first), label(k.second), l) }
        .sortedByDescending { it.trips.size }
}

/** Priemerné trvanie podľa hodiny odchodu (len hodiny s jazdami). */
fun byDepartureHour(r: FrequentRoute): List<Triple<Int, Long, Int>> =
    r.trips.groupBy { java.util.Calendar.getInstance().apply { timeInMillis = it.startedAt }.get(java.util.Calendar.HOUR_OF_DAY) }
        .map { (h, l) -> Triple(h, l.map { (it.endedAt ?: it.startedAt) - it.startedAt }.average().toLong(), l.size) }
        .sortedBy { it.first }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(onBack: () -> Unit, onOpenTrip: (Long) -> Unit) {
    val c = LocalContext.current.container
    val all by remember { c.trips.trips() }.collectAsStateWithLifecycle(emptyList())
    var minTrips by rememberSaveable { mutableStateOf(3) }
    val routes = remember(all, minTrips) { frequentRoutes(all, minTrips) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Časté trasy") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Jazdy s rovnakým začiatkom a cieľom (do 350 m) sa spoja do trasy. Názvy sú z Miest alebo z adries.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Aspoň", style = MaterialTheme.typography.bodySmall)
                    listOf(2, 3, 5, 10).forEach { n -> FilterChip(minTrips == n, { minTrips = n }, { Text("$n×") }) }
                }
            }
            if (routes.isEmpty()) item {
                Text("Zatiaľ žiadne opakované trasy. Objavia sa, keď rovnakú cestu prejdete aspoň $minTrips-krát.",
                    Modifier.padding(vertical = 24.dp), textAlign = TextAlign.Center)
            }
            items(routes, key = { it.from + "→" + it.to }) { r ->
                val key = r.from + "→" + r.to
                val expanded = open == key
                Card(Modifier.fillMaxWidth().clickable { open = if (expanded) null else key }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${r.from}  →  ${r.to}", fontWeight = FontWeight.SemiBold)
                        Text("${r.trips.size}× · Ø ${formatDuration(r.avgMs)} · Ø ${formatDistance(r.avgKm * 1000)}",
                            style = MaterialTheme.typography.bodyMedium)
                        val d = r.durations
                        Text("najrýchlejšie ${formatDuration(d.min())} · najpomalšie ${formatDuration(d.max())} · naposledy ${formatDate(r.trips.last().startedAt)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val hours = byDepartureHour(r)
                        val best = hours.filter { it.third >= 2 }.minByOrNull { it.second } ?: hours.minByOrNull { it.second }
                        if (best != null && hours.size > 1) Text("💡 Najrýchlejšie pri odchode %02d:00–%02d:59 (Ø %s)".format(best.first, best.first, formatDuration(best.second)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (expanded) {
                            Text("Ø trvanie podľa hodiny odchodu", style = MaterialTheme.typography.labelMedium)
                            HourChart(hours, best?.first)
                            r.trips.sortedBy { (it.endedAt ?: it.startedAt) - it.startedAt }.take(1).forEach {
                                Text("Najrýchlejšia jazda: ${formatDate(it.startedAt)}", Modifier.clickable { onOpenTrip(it.id) },
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            r.trips.takeLast(5).reversed().forEach { t ->
                                Row(Modifier.fillMaxWidth().clickable { onOpenTrip(t.id) }.padding(vertical = 4.dp)) {
                                    Text(sk.cartracker.ui.formatDateTime(t.startedAt), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(formatDuration((t.endedAt ?: t.startedAt) - t.startedAt), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourChart(hours: List<Triple<Int, Long, Int>>, best: Int?) {
    if (hours.isEmpty()) return
    val maxMs = hours.maxOf { it.second }.coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val bestColor = Color(0xFF2E7D32)
    Column {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val slot = size.width / hours.size
            val bw = slot * 0.6f
            hours.forEachIndexed { i, (h, ms, _) ->
                val bh = (ms.toFloat() / maxMs) * size.height
                drawRoundRect(if (h == best) bestColor else bar, Offset(i * slot + (slot - bw) / 2, size.height - bh), Size(bw, bh), CornerRadius(4f, 4f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            hours.forEach { (h, ms, n) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$h h", fontSize = 10.sp)
                    Text("${ms / 60_000} min", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${n}×", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
