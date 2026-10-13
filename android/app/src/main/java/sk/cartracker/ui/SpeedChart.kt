package sk.cartracker.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sk.cartracker.data.SpeedProfile
import sk.cartracker.data.SpeedSample
import sk.cartracker.data.local.PointEntity
import sk.cartracker.ui.map.MapIcons
import sk.cartracker.ui.map.SpeedColors
import java.util.Locale
import kotlin.math.abs

private val BAND_COLORS = listOf(Color(0xFF43A047), Color(0xFFFDD835), Color(0xFFFB8C00), Color(0xFFC62828))

/**
 * Graf rýchlosti jazdy: krivka farbená podľa rýchlosti (rovnaké farby ako trasa na mape),
 * priemer, maximum, udalosti štýlu jazdy, ťuknutím / ťahaním sa zobrazí hodnota v danom mieste.
 * Os X sa dá prepnúť medzi časom a vzdialenosťou.
 */
@Composable
fun SpeedChart(points: List<PointEntity>, tripMaxKmh: Double, modifier: Modifier = Modifier) {
    val samples = remember(points) { SpeedProfile.build(points) }
    if (samples.size < 2) {
        Text("Málo bodov GPS na graf rýchlosti.", modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val summary = remember(samples) { SpeedProfile.summary(samples, tripMaxKmh) }
    var byDistance by rememberSaveable { mutableStateOf(false) }
    var cursor by remember(samples) { mutableStateOf<Int?>(null) }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.primary

    val t0 = samples.first().ts
    val totalMs = (samples.last().ts - t0).coerceAtLeast(1)
    val totalM = samples.last().distM.coerceAtLeast(1.0)
    fun xFrac(s: SpeedSample): Float =
        if (byDistance) (s.distM / totalM).toFloat() else (s.ts - t0) / totalMs.toFloat()

    Column(modifier) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !byDistance, onClick = { byDistance = false; cursor = null }, label = { Text("Podľa času") })
            FilterChip(selected = byDistance, onClick = { byDistance = true; cursor = null }, label = { Text("Podľa km") })
        }

        // Hodnota pod kurzorom / nápoveda
        val sel = cursor?.let { samples.getOrNull(it) }
        Text(
            if (sel != null) "${sel.kmh.toInt()} km/h · ${formatTime(sel.ts)} · ${formatDistance(sel.distM)}" +
                (when (sel.event) { "brake" -> " · prudké brzdenie"; "accel" -> " · prudké zrýchlenie"; "speeding" -> " · nad limitom"; else -> "" })
            else "Ťuknite alebo ťahajte po grafe",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (sel != null) FontWeight.Bold else FontWeight.Normal,
            color = if (sel != null) onSurface else muted,
        )

        val (top, step) = remember(summary.maxKmh) { SpeedProfile.axis(summary.maxKmh) }
        val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 8.dp)
                .pointerInput(samples, byDistance) {
                    fun pick(x: Float) {
                        val left = 34.dp.toPx(); val right = size.width - 8.dp.toPx()
                        val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
                        cursor = samples.indices.minByOrNull { abs(xFrac(samples[it]) - f) }
                    }
                    detectTapGestures(onPress = { pick(it.x) })
                }
                .pointerInput(samples, byDistance) {
                    fun pick(x: Float) {
                        val left = 34.dp.toPx(); val right = size.width - 8.dp.toPx()
                        val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
                        cursor = samples.indices.minByOrNull { abs(xFrac(samples[it]) - f) }
                    }
                    detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { change, _ -> pick(change.position.x) }
                },
        ) {
            val left = 34.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val plotTop = 10.dp.toPx()
            val plotBottom = size.height - 20.dp.toPx()
            val w = right - left
            val h = plotBottom - plotTop
            fun x(s: SpeedSample) = left + xFrac(s) * w
            fun y(kmh: Float) = plotBottom - (kmh / top).coerceIn(0f, 1f) * h

            labelPaint.textSize = 10.dp.toPx()
            labelPaint.typeface = Typeface.DEFAULT
            // mriežka a os Y
            var v = 0f
            while (v <= top + 0.1f) {
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(right, yy), strokeWidth = 1f)
                labelPaint.color = muted.toArgb(); labelPaint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText("${v.toInt()}", left - 6.dp.toPx(), yy + 3.5f.dp.toPx(), labelPaint)
                v += step
            }
            // os X: začiatok, stred, koniec
            labelPaint.textAlign = Paint.Align.CENTER
            for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val label = if (byDistance) {
                    val km = totalM * f / 1000.0
                    if (totalM < 10_000) String.format(Locale("sk"), "%.1f km", km) else "${km.toInt()} km"
                } else formatTime(t0 + (totalMs * f).toLong())
                labelPaint.textAlign = when (f) { 0f -> Paint.Align.LEFT; 1f -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                drawContext.canvas.nativeCanvas.drawText(label, left + f * w, size.height - 4.dp.toPx(), labelPaint)
            }

            // farba podľa rýchlosti = vertikálny prechod podľa osi Y
            val stops = buildList {
                add(0f to Color(SpeedColors.color(top)))
                SpeedColors.stops.filter { it.first < top }.forEach { add((1f - it.first / top) to Color(it.second)) }
            }.sortedBy { it.first }.toTypedArray()
            val lineBrush = Brush.verticalGradient(*stops, startY = plotTop, endY = plotBottom)
            val fillBrush = Brush.verticalGradient(*stops.map { it.first to it.second.copy(alpha = 0.22f) }.toTypedArray(), startY = plotTop, endY = plotBottom)

            val line = Path()
            samples.forEachIndexed { i, s -> if (i == 0) line.moveTo(x(s), y(s.kmh)) else line.lineTo(x(s), y(s.kmh)) }
            val area = Path().apply {
                addPath(line)
                lineTo(x(samples.last()), plotBottom); lineTo(x(samples.first()), plotBottom); close()
            }
            drawPath(area, fillBrush)
            drawPath(line, lineBrush, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // priemer v pohybe
            val avgY = y(summary.movingAvgKmh.toFloat())
            drawLine(muted, Offset(left, avgY), Offset(right, avgY), strokeWidth = 1.2f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            labelPaint.color = muted.toArgb(); labelPaint.textAlign = Paint.Align.RIGHT; labelPaint.typeface = Typeface.DEFAULT_BOLD
            drawContext.canvas.nativeCanvas.drawText("Ø ${summary.movingAvgKmh.toInt()}", right - 2.dp.toPx(), avgY - 4.dp.toPx(), labelPaint)

            // udalosti
            samples.forEach { s ->
                val c = when (s.event) { "brake" -> MapIcons.BRAKE; "accel" -> MapIcons.ACCEL; "speeding" -> MapIcons.SPEEDING; else -> null } ?: return@forEach
                drawCircle(Color.White, 4.5f.dp.toPx(), Offset(x(s), y(s.kmh)))
                drawCircle(Color(c), 3.2f.dp.toPx(), Offset(x(s), y(s.kmh)))
            }

            // maximum
            val m = samples[summary.maxAt]
            val mx = x(m); val my = y(m.kmh)
            drawCircle(surface, 5.dp.toPx(), Offset(mx, my))
            drawCircle(Color(SpeedColors.color(m.kmh)), 3.5f.dp.toPx(), Offset(mx, my))
            labelPaint.color = onSurface.toArgb()
            labelPaint.textAlign = if (mx > right - 40.dp.toPx()) Paint.Align.RIGHT else if (mx < left + 40.dp.toPx()) Paint.Align.LEFT else Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText("max ${m.kmh.toInt()}", mx, (my - 8.dp.toPx()).coerceAtLeast(plotTop + 8.dp.toPx()), labelPaint)
            labelPaint.typeface = Typeface.DEFAULT

            // kurzor
            cursor?.let { samples.getOrNull(it) }?.let { s ->
                val cx = x(s)
                drawLine(accent, Offset(cx, plotTop), Offset(cx, plotBottom), strokeWidth = 1.5f.dp.toPx())
                drawCircle(surface, 6.dp.toPx(), Offset(cx, y(s.kmh)))
                drawCircle(accent, 4.dp.toPx(), Offset(cx, y(s.kmh)))
            }
        }

        // Súhrn
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            MiniStat("Maximum", "${summary.maxKmh.toInt()} km/h", Modifier.weight(1f))
            MiniStat("Ø v pohybe", "${summary.movingAvgKmh.toInt()} km/h", Modifier.weight(1f))
            MiniStat("V pohybe", formatDuration(summary.movingMs), Modifier.weight(1f))
            MiniStat("Státie", formatDuration(summary.stoppedMs), Modifier.weight(1f))
        }

        // Rozdelenie času podľa rýchlosti
        Text("Čas v pohybe podľa rýchlosti", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = muted)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(12.dp).clip(RoundedCornerShape(6.dp))) {
            summary.bands.forEachIndexed { i, f ->
                if (f > 0.001f) Box(Modifier.weight(f).height(12.dp).background(BAND_COLORS[i]))
            }
            if (summary.bands.all { it <= 0.001f }) Box(Modifier.weight(1f).height(12.dp).background(grid))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            summary.bands.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(BAND_COLORS[i], CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("${SpeedProfile.BAND_LABELS[i]}: ${(f * 100).toInt()} %", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
