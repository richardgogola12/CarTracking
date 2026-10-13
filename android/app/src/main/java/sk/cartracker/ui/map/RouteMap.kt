package sk.cartracker.ui.map

import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.TilesOverlay
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.MapStyle
import sk.cartracker.data.ThemeMode
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity
import sk.cartracker.ui.formatDateTime
import sk.cartracker.ui.formatTime

/** Jedna trasa na mape. */
data class MapRoute(val points: List<PointEntity>, val startLabel: String? = null, val endLabel: String? = null)

private val tripPalette = listOf(
    AColor.rgb(0x15, 0x65, 0xC0), AColor.rgb(0x00, 0x89, 0x7B), AColor.rgb(0x8E, 0x24, 0xAA),
    AColor.rgb(0xEF, 0x6C, 0x00), AColor.rgb(0xC6, 0x28, 0x28), AColor.rgb(0x2E, 0x7D, 0x32),
    AColor.rgb(0x6D, 0x4C, 0x41), AColor.rgb(0x37, 0x47, 0x4F),
)

/**
 * Profesionálna mapa trasy:
 *  - podklady CARTO / OSM / terén / satelit (prepínač vrstiev),
 *  - trasa s obrysom a farbou podľa rýchlosti (alebo farbou jazdy pri viacerých jazdách),
 *  - značky štart A / cieľ B a udalosti štýlu jazdy s popisom po ťuknutí,
 *  - mierka, atribúcia, vycentrovanie trasy, režim na celú obrazovku a legenda rýchlosti.
 */
@Composable
fun RouteMap(
    routes: List<MapRoute>,
    modifier: Modifier = Modifier,
    colorBySpeed: Boolean = routes.size == 1,
    showEvents: Boolean = true,
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val systemDark = isSystemInDarkTheme()
    val appDark = when (s.themeMode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    var fitRequest by remember { mutableIntStateOf(0) }
    var layersOpen by remember { mutableStateOf(false) }

    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(7.5)
            controller.setCenter(GeoPoint(48.67, 19.70)) // Slovensko
            // Mapa v posúvateľnej obrazovke: gestá patria mape, nie stránke
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }

    // Životný cyklus MapView
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }

    // Podklad
    LaunchedEffect(s.mapStyle, appDark, s.offlineMaps) {
        map.setUseDataConnection(!s.offlineMaps)   // offline: iba uložené dlaždice
        map.setTileSource(MapTiles.source(s.mapStyle, appDark))
        map.invalidate()
    }

    // Obsah mapy
    LaunchedEffect(routes, colorBySpeed, showEvents, s.mapStyle, appDark) {
        buildOverlays(map, routes, colorBySpeed, showEvents, MapTiles.isDarkBase(s.mapStyle, appDark), MapTiles.labels(s.mapStyle, appDark))
        fitRoutes(map, routes, animate = false)
    }
    LaunchedEffect(fitRequest) { if (fitRequest > 0) fitRoutes(map, routes, animate = true) }

    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())

        // Ovládacie prvky
        Column(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                MapButton(Icons.Filled.Layers, "Typ mapy") { layersOpen = true }
                DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                    MapStyle.entries.forEach { st ->
                        DropdownMenuItem(
                            text = { Text(st.label) },
                            leadingIcon = { if (s.mapStyle == st) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                            onClick = { layersOpen = false; scope.launch { c.settings.update { it.copy(mapStyle = st) } } },
                        )
                    }
                }
            }
            MapButton(Icons.Filled.CenterFocusStrong, "Zobraziť celú trasu") { fitRequest++ }
            if (onToggleFullscreen != null) {
                MapButton(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Celá obrazovka", onToggleFullscreen)
            }
        }

        if (colorBySpeed && routes.any { it.points.isNotEmpty() }) {
            SpeedLegend(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 28.dp))
        }
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { Icon(icon, desc) }
}

@Composable
private fun SpeedLegend(modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("Rýchlosť (km/h)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.padding(top = 3.dp).width(150.dp).height(8.dp).background(
                    Brush.horizontalGradient(SpeedColors.stops.map { Color(it.second) }), RoundedCornerShape(4.dp)
                )
            )
            Row(Modifier.width(150.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "50", "90", "130", "150+").forEach { Text(it, fontSize = 9.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(Color(MapIcons.BRAKE), "brzdenie")
                LegendDot(Color(MapIcons.ACCEL), "zrýchlenie")
                LegendDot(Color(MapIcons.SPEEDING), "limit")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(" $label", fontSize = 9.sp)
    }
}

// ---------------- kreslenie ----------------

private fun buildOverlays(
    map: MapView, routes: List<MapRoute>, colorBySpeed: Boolean, showEvents: Boolean, darkBase: Boolean, labels: ITileSource?,
) {
    val ctx = map.context
    val d = ctx.resources.displayMetrics.density
    map.overlays.filterIsInstance<TilesOverlay>().forEach { it.onDetach(map) }
    map.overlays.clear()

    // Popisky (ulice, obce) nad šedým podkladom
    if (labels != null) {
        map.overlays.add(TilesOverlay(MapTileProviderBasic(ctx, labels), ctx).apply {
            loadingBackgroundColor = AColor.TRANSPARENT
            loadingLineColor = AColor.TRANSPARENT
        })
    }

    routes.forEachIndexed { idx, route ->
        val pts = route.points
        if (pts.isEmpty()) return@forEachIndexed
        val geo = pts.map { GeoPoint(it.lat, it.lng) }

        // Obrys (casing) – trasa je čitateľná na každom podklade
        map.overlays.add(styledLine(map, geo, if (darkBase) AColor.argb(220, 10, 10, 10) else AColor.WHITE, 11f * d))

        if (colorBySpeed) {
            // Úseky s rovnakou rýchlostnou triedou (po 10 km/h) – málo objektov, plynulé farby
            var segment = ArrayList<GeoPoint>()
            var bucket = -1
            pts.forEachIndexed { i, p ->
                val b = ((p.speedKmh ?: 0f) / 10f).toInt().coerceIn(0, 16)
                if (bucket == -1) bucket = b
                segment.add(geo[i])
                if (b != bucket && segment.size > 1) {
                    map.overlays.add(styledLine(map, segment, SpeedColors.color(bucket * 10f + 5f), 6.5f * d))
                    segment = arrayListOf(geo[i])
                    bucket = b
                }
            }
            if (segment.size > 1) map.overlays.add(styledLine(map, segment, SpeedColors.color(bucket * 10f + 5f), 6.5f * d))
        } else {
            map.overlays.add(styledLine(map, geo, tripPalette[idx % tripPalette.size], 5.5f * d))
        }

        if (showEvents && colorBySpeed) {
            pts.filter { it.event != null }.forEach { p ->
                val (color, symbol, title) = when (p.event) {
                    DriveEvent.BRAKE -> Triple(MapIcons.BRAKE, "!", "Prudké brzdenie")
                    DriveEvent.ACCEL -> Triple(MapIcons.ACCEL, "↑", "Prudké zrýchlenie")
                    else -> Triple(MapIcons.SPEEDING, "»", "Prekročený limit")
                }
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(p.lat, p.lng)
                    icon = MapIcons.dot(ctx, color, symbol)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    this.title = title
                    snippet = "${formatTime(p.ts)} · ${(p.speedKmh ?: 0f).toInt()} km/h"
                })
            }
        }

        val single = routes.size == 1
        map.overlays.add(Marker(map).apply {
            position = geo.first()
            icon = MapIcons.pin(ctx, MapIcons.START, if (single) "A" else "${idx + 1}", if (single) 34f else 26f)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Štart"
            snippet = listOfNotNull(route.startLabel, formatDateTime(pts.first().ts)).joinToString("\n")
        })
        if (single) map.overlays.add(Marker(map).apply {
            position = geo.last()
            icon = MapIcons.pin(ctx, MapIcons.END, "B")
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Cieľ"
            snippet = listOfNotNull(route.endLabel, formatDateTime(pts.last().ts)).joinToString("\n")
        })
    }

    map.overlays.add(ScaleBarOverlay(map).apply {
        setCentred(false)
        setScaleBarOffset((12 * d).toInt(), (10 * d).toInt())
        setAlignBottom(true)
        setTextSize(10 * d)
    })
    map.overlays.add(CopyrightOverlay(ctx).apply {
        setAlignRight(true)
        setTextSize(9)
        setTextColor(if (darkBase) AColor.WHITE else AColor.DKGRAY)
    })
    map.invalidate()
}

private fun styledLine(map: MapView, pts: List<GeoPoint>, color: Int, width: Float) = Polyline(map).apply {
    setPoints(pts)
    outlinePaint.apply {
        this.color = color
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    infoWindow = null
    setOnClickListener { _, _, _ -> false }
}

private fun fitRoutes(map: MapView, routes: List<MapRoute>, animate: Boolean) {
    val all = routes.flatMap { r -> r.points.map { GeoPoint(it.lat, it.lng) } }
    if (all.isEmpty()) return
    val pad = (56 * map.context.resources.displayMetrics.density).toInt()
    val fit = {
        if (all.size == 1 || BoundingBox.fromGeoPoints(all).let { it.latitudeSpan < 0.0005 && it.longitudeSpan < 0.0005 }) {
            map.controller.setZoom(16.0); map.controller.setCenter(all.first())
        } else {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), animate, pad)
        }
    }
    if (map.width > 0 && map.height > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
}
