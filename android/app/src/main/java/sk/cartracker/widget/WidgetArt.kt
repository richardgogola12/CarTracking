package sk.cartracker.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import sk.cartracker.data.SpeedProfile
import sk.cartracker.data.SpeedSample
import sk.cartracker.ui.formatTime
import sk.cartracker.ui.map.SpeedColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Grafika widgetov (bitmapy): stĺpcové grafy, krúžok krokov, náčrt trasy, rýchlosť, dopravná značka. */
object WidgetArt {
    val GREEN = Color.rgb(0x22, 0xC5, 0x5E)
    val GREEN_L = Color.rgb(0x86, 0xEF, 0xAC)
    val BLUE = Color.rgb(0x3B, 0x82, 0xF6)
    val BLUE_L = Color.rgb(0x93, 0xC5, 0xFD)
    val AMBER = Color.rgb(0xF5, 0x9E, 0x0B)
    val VIOLET = Color.rgb(0xA7, 0x8B, 0xFA)
    val RED = Color.rgb(0xEF, 0x44, 0x44)
    val SLATE = Color.rgb(0x94, 0xA3, 0xB8)
    private val SK = Locale("sk")

    fun compact(n: Int): String = when {
        n >= 10_000 -> String.format(SK, "%.1fk", n / 1000.0)
        else -> String.format(SK, "%,d", n)
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /**
     * Stĺpcový graf so zaoblenými stĺpcami. Posledný stĺpec (dnes) a maximum sú zvýraznené farbou [accent] a hodnotou.
     * [labelEvery] = popisok pod každým n-tým stĺpcom (prázdny text = bez popisku).
     */
    fun bars(
        data: List<Pair<String, Double>>, w: Int, h: Int, d: Float, accent: Int, accentLight: Int,
        format: (Double) -> String = { if (it >= 100) "${it.toInt()}" else String.format(SK, "%.0f", it) },
    ): Bitmap {
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
        if (data.isEmpty()) return bmp
        val c = Canvas(bmp)
        val labelH = 13 * d; val valueH = 13 * d
        val maxV = max(data.maxOf { it.second }, 0.0001)
        val slot = w / data.size.toFloat()
        val bw = min(slot * 0.58f, 22 * d)
        val area = h - labelH - valueH
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 140); textSize = 9.5f * d; textAlign = Paint.Align.CENTER }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9.5f * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 26); strokeWidth = 1f * d }
        c.drawLine(0f, valueH + area + 0.5f * d, w.toFloat(), valueH + area + 0.5f * d, base)
        val maxIdx = data.indices.maxBy { data[it].second }
        data.forEachIndexed { i, (name, v) ->
            val cx = i * slot + slot / 2
            val bh = if (v > 0) max((v / maxV * area).toFloat(), 4 * d) else 2.5f * d
            val top = valueH + area - bh
            val hi = i == data.lastIndex
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = if (hi) LinearGradient(0f, top, 0f, valueH + area, accentLight, accent, Shader.TileMode.CLAMP)
                else LinearGradient(0f, top, 0f, valueH + area, alpha(Color.WHITE, 90), alpha(Color.WHITE, 40), Shader.TileMode.CLAMP)
            val r = min(bw / 2, 6 * d)
            c.drawRoundRect(RectF(cx - bw / 2, top, cx + bw / 2, valueH + area), r, r, p)
            if (name.isNotEmpty()) {
                label.color = if (hi) Color.WHITE else alpha(Color.WHITE, 140)
                label.typeface = if (hi) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                c.drawText(name, cx, h - 2 * d, label)
            }
            if ((hi || i == maxIdx) && v > 0) c.drawText(format(v), cx, top - 3 * d, value)
        }
        return bmp
    }

    /** Krúžok postupu (kroky k cieľu) s farebným prechodom a hodnotou v strede. */
    fun ring(value: Int, goal: Int, size: Int, d: Float, c1: Int = BLUE_L, c2: Int = BLUE, sub: String? = null): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = 9 * d
        val r = RectF(stroke / 2 + 2 * d, stroke / 2 + 2 * d, size - stroke / 2 - 2 * d, size - stroke / 2 - 2 * d)
        c.drawArc(r, 0f, 360f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = alpha(Color.WHITE, 30) })
        val done = goal in 1..value
        val frac = if (goal > 0) min(value / goal.toFloat(), 1f) else 0f
        if (frac > 0) {
            val a = if (done) GREEN_L else c1; val b = if (done) GREEN else c2
            val sweep = SweepGradient(size / 2f, size / 2f, intArrayOf(a, b, a), floatArrayOf(0f, 0.5f, 1f))
            val m = android.graphics.Matrix(); m.setRotate(-90f, size / 2f, size / 2f); sweep.setLocalMatrix(m)
            c.drawArc(r, -90f, 360f * frac, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; shader = sweep
            })
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size * 0.2f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 170); textSize = size * 0.105f; textAlign = Paint.Align.CENTER }
        val pct = if (goal > 0) (value * 100L / goal).toInt() else 0
        c.drawText(if (goal > 0) "$pct %" else compact(value), size / 2f, size / 2f + size * 0.05f, big)
        c.drawText(sub ?: if (done) "cieľ ✓" else "z ${compact(goal)}", size / 2f, size / 2f + size * 0.2f, small)
        return bmp
    }

    /**
     * Náčrt trasy jazdy farbený podľa rýchlosti (bez mapového podkladu) so štartom a cieľom.
     * Zachováva pomer strán (zemepisná dĺžka sa koriguje kosínusom šírky).
     */
    fun route(pts: List<RoutePt>, w: Int, h: Int, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // jemná mriežka bodiek ako „mapa“
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 22) }
        var gy = 8 * d
        while (gy < h) { var gx = 8 * d; while (gx < w) { c.drawCircle(gx, gy, 0.9f * d, dot); gx += 12 * d }; gy += 12 * d }
        if (pts.size < 2) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 120); textSize = 10 * d; textAlign = Paint.Align.CENTER }
            c.drawText("bez trasy", w / 2f, h / 2f, p); return bmp
        }
        val lat0 = pts.sumOf { it.lat } / pts.size
        val kx = cos(Math.toRadians(lat0))
        val minX = pts.minOf { it.lng * kx }; val maxX = pts.maxOf { it.lng * kx }
        val minY = pts.minOf { it.lat }; val maxY = pts.maxOf { it.lat }
        val pad = 10 * d
        val spanX = max(maxX - minX, 1e-6); val spanY = max(maxY - minY, 1e-6)
        val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
        val ox = (w - spanX * scale) / 2; val oy = (h - spanY * scale) / 2
        fun x(p: RoutePt) = (ox + (p.lng * kx - minX) * scale).toFloat()
        fun y(p: RoutePt) = (h - oy - (p.lat - minY) * scale).toFloat()
        // tieň / žiara pod trasou
        val path = Path(); pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 7 * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            color = alpha(Color.BLACK, 90); maskFilter = BlurMaskFilter(3 * d, BlurMaskFilter.Blur.NORMAL)
        })
        val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeCap = Paint.Cap.ROUND }
        for (i in 1 until pts.size) {
            seg.color = SpeedColors.color(pts[i].kmh)
            c.drawLine(x(pts[i - 1]), y(pts[i - 1]), x(pts[i]), y(pts[i]), seg)
        }
        fun pin(p: RoutePt, col: Int) {
            c.drawCircle(x(p), y(p), 5.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(x(p), y(p), 3.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        }
        pin(pts.first(), GREEN); pin(pts.last(), RED)
        return bmp
    }

    /** Krivka rýchlosti jazdy farbená podľa rýchlosti, priemer a maximum. */
    fun speedChart(samples: List<SpeedSample>, maxKmh: Float, avg: Double, w: Int, h: Int, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(Color.WHITE, 150); textSize = 8.5f * d }
        if (samples.size < 2) {
            label.textAlign = Paint.Align.CENTER; label.textSize = 10 * d
            c.drawText("Graf sa zobrazí po prvej jazde", w / 2f, h / 2f, label)
            return bmp
        }
        val (top, step) = SpeedProfile.axis(maxKmh)
        val left = 18 * d; val plotTop = 9 * d; val plotBottom = h - 11 * d
        val pw = w - left - 2 * d; val ph = plotBottom - plotTop
        val t0 = samples.first().ts
        val span = (samples.last().ts - t0).coerceAtLeast(1).toFloat()
        fun x(s: SpeedSample) = left + (s.ts - t0) / span * pw
        fun y(v: Float) = plotBottom - (v / top).coerceIn(0f, 1f) * ph
        val gridP = Paint().apply { color = alpha(Color.WHITE, 30); strokeWidth = 1f }
        label.textAlign = Paint.Align.RIGHT
        var g = 0f
        while (g <= top + 0.1f) {
            c.drawLine(left, y(g), w.toFloat(), y(g), gridP)
            if (g > 0) c.drawText("${g.toInt()}", left - 3 * d, y(g) + 3 * d, label)
            g += step * if (top / step > 4) 2 else 1
        }
        label.textAlign = Paint.Align.LEFT; c.drawText(formatTime(t0), left, h - 1.5f * d, label)
        label.textAlign = Paint.Align.RIGHT; c.drawText(formatTime(samples.last().ts), w.toFloat(), h - 1.5f * d, label)
        val stops = SpeedColors.stops.filter { it.first < top }
        val colors = (listOf(SpeedColors.color(top)) + stops.reversed().map { it.second }).toIntArray()
        val pos = (listOf(0f) + stops.reversed().map { 1f - it.first / top }).toFloatArray()
        val line = Path()
        samples.forEachIndexed { i, s -> if (i == 0) line.moveTo(x(s), y(s.kmh)) else line.lineTo(x(s), y(s.kmh)) }
        val area = Path(line).apply { lineTo(x(samples.last()), plotBottom); lineTo(x(samples.first()), plotBottom); close() }
        c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, plotTop, 0f, plotBottom, colors.map { alpha(it, 70) }.toIntArray(), pos, Shader.TileMode.CLAMP)
        })
        c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(0f, plotTop, 0f, plotBottom, colors, pos, Shader.TileMode.CLAMP)
        })
        val ay = y(avg.toFloat())
        c.drawLine(left, ay, w.toFloat(), ay, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = alpha(Color.WHITE, 150); strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
        })
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8.5f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
        c.drawText("Ø ${avg.toInt()}", w - 2 * d, ay - 2.5f * d, bold)
        val mi = samples.indices.maxByOrNull { samples[it].kmh } ?: 0
        val mx = x(samples[mi]); val my = y(samples[mi].kmh)
        c.drawCircle(mx, my, 3 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        bold.textAlign = if (mx > w - 30 * d) Paint.Align.RIGHT else if (mx < left + 30 * d) Paint.Align.LEFT else Paint.Align.CENTER
        c.drawText("max ${maxKmh.toInt()}", mx, (my - 4 * d).coerceAtLeast(8 * d), bold)
        return bmp
    }

    /** Dopravná značka s limitom; pri prekročení červené pozadie a žiara. */
    fun limitSign(limit: Int, over: Boolean, d: Float): Bitmap {
        val s = (54 * d).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        if (over) c.drawCircle(cx, cx, cx - 1 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(RED, 110); maskFilter = BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(cx, cx, cx - 4 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD3, 0x2F, 0x2F) })
        c.drawCircle(cx, cx, cx - 10 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (over) Color.rgb(0xFF, 0xCD, 0xD2) else Color.WHITE })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            textSize = (if (limit >= 100) 15 else 19) * d
        }
        c.drawText("$limit", cx, cx - (t.descent() + t.ascent()) / 2, t)
        return bmp
    }
}
