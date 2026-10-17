package sk.cartracker.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.RuntimeState
import sk.cartracker.data.ThemeMode
import sk.cartracker.ui.map.MapTiles
import java.io.File
import java.util.Locale
import kotlin.math.min

private const val MAX_TILES = 15_000

/** Uloženie oblasti mapy do telefónu na použitie bez internetu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val systemDark = isSystemInDarkTheme()
    val dark = when (s.themeMode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val canDownload = MapTiles.canDownload(s.mapStyle, dark)
    val maxSourceZoom = MapTiles.maxZoom(s.mapStyle, dark)

    var maxZoom by remember { mutableIntStateOf(15) }
    var tiles by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        cacheBytes = withContext(Dispatchers.IO) {
            Configuration.getInstance().osmdroidTileCache.walkTopDown().filter(File::isFile).sumOf { it.length() }
        }
    }

    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 5.0
            controller.setZoom(12.0)
            val p = RuntimeState.parking(ctx)
            controller.setCenter(if (p != null) GeoPoint(p.lat, p.lng) else GeoPoint(48.15, 17.11))
        }
    }
    LaunchedEffect(s.mapStyle, dark) { MapTiles.apply(map, s.mapStyle, dark); map.setUseDataConnection(true) }
    LaunchedEffect(Unit) { currentLocation(ctx)?.let { (lat, lng) -> map.controller.setCenter(GeoPoint(lat, lng)) } }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }

    fun recount() {
        val bb = map.boundingBox ?: return
        val zMin = map.zoomLevelDouble.toInt().coerceAtLeast(5)
        val zMax = min(maxZoom, maxSourceZoom).coerceAtLeast(zMin)
        tiles = runCatching { CacheManager(map).possibleTilesInArea(bb, zMin, zMax) }.getOrDefault(0) *
            (if (MapTiles.labels(s.mapStyle, dark) != null) 2 else 1)
    }
    DisposableEffect(map) {
        val l = object : org.osmdroid.events.MapListener {
            override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean { recount(); return false }
            override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean { recount(); return false }
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l) }
    }
    LaunchedEffect(maxZoom) { recount() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Offline mapy") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                AndroidView({ map }, Modifier.fillMaxSize())
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Posuňte a priblížte mapu na oblasť, ktorú chcete mať bez internetu (napr. mesto alebo trasa do práce). " +
                    "Uloží sa od aktuálneho priblíženia po zvolenú podrobnosť.", style = MaterialTheme.typography.bodySmall)
                Text("Podklad: ${MapTiles.label(s.mapStyle, dark)}", fontWeight = FontWeight.SemiBold)
                if (!canDownload) Text("Pravidlá OpenStreetMap a OpenTopoMap nepovoľujú hromadné sťahovanie celých oblastí, preto je tlačidlo vypnuté. " +
                    "Mapy sa však ukladajú automaticky pri prezeraní: oblasť, ktorú si raz pozriete (napr. trasu do práce pri rôznych priblíženiach), " +
                    "bude 30 dní dostupná aj bez internetu a v režime offline.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                Text("Podrobnosť (max. priblíženie)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(13 to "Mestá", 14 to "Ulice", 15 to "Detail", 16 to "Max.").forEach { (z, l) ->
                        FilterChip(maxZoom == z, { maxZoom = z }, { Text(l) })
                    }
                }
                val mb = tiles * 18 / 1024.0
                Text(String.format(Locale("sk"), "Dlaždíc: %,d (cca %.0f MB)", tiles, mb) +
                    if (tiles > MAX_TILES) " – príliš veľa, priblížte mapu alebo znížte podrobnosť (max. %,d)".format(MAX_TILES) else "",
                    color = if (tiles > MAX_TILES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                progress?.let { (done, total) ->
                    LinearProgressIndicator(progress = { if (total > 0) done / total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Sťahujem $done / $total…", style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Button(
                    enabled = canDownload && progress == null && tiles in 1..MAX_TILES,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val bb = map.boundingBox
                        val zMin = map.zoomLevelDouble.toInt().coerceAtLeast(5)
                        val zMax = min(maxZoom, maxSourceZoom).coerceAtLeast(zMin)
                        message = null
                        val sources = listOfNotNull(MapTiles.source(s.mapStyle, dark), MapTiles.labels(s.mapStyle, dark))
                        progress = 0 to tiles
                        var finished = 0
                        var doneBefore = 0
                        sources.forEach { src ->
                            val cm = CacheManager(src, SqlTileWriter(), zMin, zMax)
                            cm.downloadAreaAsyncNoUI(ctx, bb, zMin, zMax, object : CacheManager.CacheManagerCallback {
                                private var total = 0
                                override fun onTaskComplete() = done(null)
                                override fun onTaskFailed(errors: Int) = done(errors)
                                override fun updateProgress(p: Int, z: Int, a: Int, b: Int) { progress = (doneBefore + p) to tiles }
                                override fun downloadStarted() {}
                                override fun setPossibleTilesInArea(total: Int) { this.total = total }
                                private fun done(errors: Int?) {
                                    doneBefore += total
                                    finished++
                                    if (finished == sources.size) {
                                        progress = null
                                        message = if (errors != null && errors > 0) "Uložené, $errors dlaždíc sa nepodarilo stiahnuť." else "Oblasť je uložená ✓"
                                        tick++
                                    }
                                }
                            })
                        }
                    },
                ) { Text("Uložiť túto oblasť") }

                Text(String.format(Locale("sk"), "Uložené mapy v telefóne: %.0f MB (limit %d MB)", cacheBytes / 1048576.0, s.mapCacheMb),
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(s.offlineMaps, { scope.launch { c.settings.update { it.copy(offlineMaps = !it.offlineMaps) } } },
                        { Text(if (s.offlineMaps) "Režim offline: zapnutý" else "Režim offline: vypnutý") })
                    OutlinedButton(enabled = progress == null, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { SqlTileWriter().purgeCache() } }
                            message = "Uložené mapy boli vymazané"
                            tick++
                        }
                    }) { Text("Vymazať") }
                }
                Text("V režime offline mapy nesťahujú nič z internetu – zobrazia sa iba uložené časti (šetrí dáta v roamingu). " +
                    "Aj bez neho sa každá prezretá časť mapy ukladá a zobrazí sa, keď nie je signál.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
