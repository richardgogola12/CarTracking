package sk.cartracker.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import sk.cartracker.data.MapStyle

/** Mapové podklady. Všetky sú bezplatné a nevyžadujú API kľúč. */
object MapTiles {
    private val CARTO_LIGHT = XYTileSource(
        "CartoPositron", 0, 20, 512, "@2x.png",
        arrayOf("https://a.basemaps.cartocdn.com/light_all/", "https://b.basemaps.cartocdn.com/light_all/", "https://c.basemaps.cartocdn.com/light_all/"),
        "© OpenStreetMap © CARTO",
    )
    private val CARTO_DARK = XYTileSource(
        "CartoDarkMatter", 0, 20, 512, "@2x.png",
        arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"),
        "© OpenStreetMap © CARTO",
    )
    private val CARTO_VOYAGER = XYTileSource(
        "CartoVoyager", 0, 20, 512, "@2x.png",
        arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager/", "https://b.basemaps.cartocdn.com/rastertiles/voyager/"),
        "© OpenStreetMap © CARTO",
    )
    private val TOPO = XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/"),
        "© OpenStreetMap © OpenTopoMap (CC-BY-SA)",
    )

    /** Esri používa poradie z/y/x, preto vlastná trieda. */
    private val SATELLITE = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Snímky © Esri, Maxar, Earthstar Geographics",
    ) {
        override fun getTileURLString(index: Long): String =
            baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
    }

    fun source(style: MapStyle, dark: Boolean): ITileSource = when (style) {
        MapStyle.AUTO -> if (dark) CARTO_DARK else CARTO_VOYAGER
        MapStyle.LIGHT -> CARTO_LIGHT
        MapStyle.DARK -> CARTO_DARK
        MapStyle.STREETS -> TileSourceFactory.MAPNIK
        MapStyle.TOPO -> TOPO
        MapStyle.SATELLITE -> SATELLITE
    }

    fun isDarkBase(style: MapStyle, dark: Boolean) =
        style == MapStyle.DARK || style == MapStyle.SATELLITE || (style == MapStyle.AUTO && dark)
}

/** Farba trasy podľa rýchlosti: zelená → žltá → oranžová → červená → fialová. */
object SpeedColors {
    val stops = listOf(
        0f to Color.rgb(0x2E, 0x7D, 0x32),
        30f to Color.rgb(0x43, 0xA0, 0x47),
        50f to Color.rgb(0xC0, 0xCA, 0x33),
        70f to Color.rgb(0xFD, 0xD8, 0x35),
        90f to Color.rgb(0xFB, 0x8C, 0x00),
        110f to Color.rgb(0xE5, 0x39, 0x35),
        130f to Color.rgb(0xB7, 0x1C, 0x1C),
        150f to Color.rgb(0x6A, 0x1B, 0x9A),
    )

    fun color(kmh: Float): Int {
        if (kmh <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (s1, c1) = stops[i]
            if (kmh <= s1) {
                val (s0, c0) = stops[i - 1]
                return lerp(c0, c1, (kmh - s0) / (s1 - s0))
            }
        }
        return stops.last().second
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}

/** Vlastné značky kreslené za behu (ostré na každom displeji). */
object MapIcons {
    private val cache = HashMap<String, BitmapDrawable>()

    fun pin(ctx: Context, fill: Int, label: String, sizeDp: Float = 34f): BitmapDrawable =
        cache.getOrPut("pin$fill$label$sizeDp") {
            val d = ctx.resources.displayMetrics.density
            val w = (sizeDp * d).toInt()
            val h = (sizeDp * 1.3f * d).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = w / 2f - 2 * d
            val cx = w / 2f
            val cy = r + 2 * d
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
            c.drawOval(cx - r * 0.45f, h - 6 * d, cx + r * 0.45f, h.toFloat() - 1 * d, shadow)
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
            val path = android.graphics.Path().apply {
                moveTo(cx - r * 0.55f, cy + r * 0.75f)
                lineTo(cx, h - 3 * d)
                lineTo(cx + r * 0.55f, cy + r * 0.75f)
                close()
            }
            c.drawPath(path, body)
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(cx, cy, r - 2.5f * d, body)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = r * 1.05f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
            BitmapDrawable(ctx.resources, bmp)
        }

    fun dot(ctx: Context, fill: Int, symbol: String, sizeDp: Float = 22f): BitmapDrawable =
        cache.getOrPut("dot$fill$symbol$sizeDp") {
            val d = ctx.resources.displayMetrics.density
            val s = (sizeDp * d).toInt()
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = s / 2f
            c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(r, r, r - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = r * 1.1f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            c.drawText(symbol, r, r - (text.descent() + text.ascent()) / 2, text)
            BitmapDrawable(ctx.resources, bmp)
        }

    val START = Color.rgb(0x2E, 0x7D, 0x32)
    val END = Color.rgb(0xC6, 0x28, 0x28)
    val BRAKE = Color.rgb(0xD3, 0x2F, 0x2F)
    val ACCEL = Color.rgb(0xF5, 0x7C, 0x00)
    val SPEEDING = Color.rgb(0x6A, 0x1B, 0x9A)
    val PARKING = Color.rgb(0x15, 0x65, 0xC0)
}
