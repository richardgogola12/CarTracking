package sk.cartracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Skutočné rýchlostné limity z OpenStreetMap (Overpass API).
 * Cesty v okolí sa stiahnu po štvorcoch ~1 × 0,7 km (aj s tvarom), a pri každom bode GPS sa lokálne nájde cesta,
 * po ktorej auto ide (vzdialenosť do 25 m a súhlasný smer). Limit sa číta z tagov maxspeed (aj :forward/:backward
 * podľa smeru jazdy), slovenských kódov (SK:urban = 50 …) a zón (zone:maxspeed = SK:30).
 * Stiahnuté štvorce sa ukladajú na disk (30 dní) – opakované trasy fungujú aj bez internetu.
 * Bez internetu alebo bez údajov vráti null – použije sa limit podľa typu cesty.
 */
object SpeedLimits {
    private const val TILE = 0.01           // stupne (~1,1 km × 0,75 km na Slovensku)
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Way(val id: Long, val tags: Map<String, String>, val lat: DoubleArray, val lng: DoubleArray) {
        val oneway get() = tags["oneway"] == "yes" || tags["highway"] == "motorway" || tags["junction"] == "roundabout"
    }
    data class Match(val limit: Int?, val way: Way, val forward: Boolean)

    private val tiles = ConcurrentHashMap<String, List<Way>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private var lastWay: Long = 0

    private fun key(lat: Double, lng: Double) = "${floor(lat / TILE).toLong()}:${floor(lng / TILE).toLong()}"

    /** Stiahne štvorec s bodom a susedný v smere jazdy (na pozadí, bez čakania). */
    fun prefetch(lat: Double, lng: Double, bearing: Float?) {
        load(key(lat, lng), lat, lng)
        if (bearing != null) {
            val r = Math.toRadians(bearing.toDouble())
            val la = lat + cos(r) * TILE * 0.7; val lo = lng + kotlin.math.sin(r) * TILE * 0.7
            load(key(la, lo), la, lo)
        }
    }

    /** Priečinok s uloženými štvorcami (nastaví aplikácia pri štarte). Štvorec platí 30 dní. */
    @Volatile var cacheDir: File? = null
    private const val DISK_TTL_MS = 30L * 86_400_000

    private fun load(k: String, lat: Double, lng: Double) {
        if (tiles.containsKey(k) || !loading.add(k)) return
        if (System.currentTimeMillis() - (failedAt[k] ?: 0) < 60_000) { loading.remove(k); return }
        scope.launch {
            try {
                // 1. z disku (bez internetu, rýchlo)
                val f = cacheDir?.let { File(it, "$k.json") }
                if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_TTL_MS) {
                    val ways = runCatching { parseWays(JSONObject(f.readText())) }.getOrNull()
                    if (ways != null) { put(k, ways); return@launch }
                }
                // 2. z Overpass API
                val s = floor(lat / TILE) * TILE; val w = floor(lng / TILE) * TILE
                val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", s - 0.0005, w - 0.0005, s + TILE + 0.0005, w + TILE + 0.0005)
                val q = "[out:json][timeout:20];way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"]($bbox);out tags geom;"
                val json = fetch(q) ?: run {
                    // bez internetu aj starší uložený štvorec je lepší ako nič
                    if (f != null && f.exists()) runCatching { parseWays(JSONObject(f.readText())) }.getOrNull()?.let { put(k, it); return@launch }
                    failedAt[k] = System.currentTimeMillis(); return@launch
                }
                val ways = parseWays(json)
                put(k, ways)
                if (f != null) runCatching {
                    f.parentFile?.mkdirs()
                    f.writeText(toJson(ways).toString())
                    trimDisk(f.parentFile)
                }
            } catch (_: Exception) {
                failedAt[k] = System.currentTimeMillis()
            } finally { loading.remove(k) }
        }
    }

    private fun put(k: String, ways: List<Way>) {
        if (tiles.size > 60) tiles.keys.take(20).forEach { tiles.remove(it) }
        tiles[k] = ways
    }

    /** Najviac ~1500 štvorcov na disku (rádovo desiatky MB); najstaršie sa zmažú. */
    private fun trimDisk(dir: File?) {
        val files = dir?.listFiles()?.takeIf { it.size > 1500 } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - 1200).forEach { it.delete() }
    }

    /** Odpoveď Overpass (aj vlastný uložený formát – je rovnaký) → cesty s potrebnými tagmi. */
    fun parseWays(json: JSONObject): List<Way> {
        val els = json.optJSONArray("elements") ?: return emptyList()
        val ways = ArrayList<Way>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val tj = e.optJSONObject("tags") ?: continue
            val tags = HashMap<String, String>()
            tj.keys().forEach { t -> if (t.startsWith("maxspeed") || t.startsWith("zone:") || t in KEEP) tags[t] = tj.optString(t) }
            ways += Way(e.optLong("id"), tags, DoubleArray(g.length()) { g.getJSONObject(it).optDouble("lat") }, DoubleArray(g.length()) { g.getJSONObject(it).optDouble("lon") })
        }
        return ways
    }

    fun toJson(ways: List<Way>): JSONObject {
        val els = JSONArray()
        for (w in ways) {
            val g = JSONArray()
            for (i in w.lat.indices) g.put(JSONObject().put("lat", Math.round(w.lat[i] * 1e6) / 1e6).put("lon", Math.round(w.lng[i] * 1e6) / 1e6))
            els.put(JSONObject().put("id", w.id).put("tags", JSONObject(w.tags as Map<*, *>)).put("geometry", g))
        }
        return JSONObject().put("elements", els)
    }

    internal fun putForTest(lat: Double, lng: Double, ways: List<Way>) { tiles[key(lat, lng)] = ways }

    /** Zmaže uložené štvorce (napr. po oprave mapy v OSM). */
    fun clearDisk() { cacheDir?.listFiles()?.forEach { it.delete() }; tiles.clear() }
    fun diskCount() = cacheDir?.listFiles()?.size ?: 0

    private val KEEP = setOf("highway", "oneway", "junction", "name", "ref", "source:maxspeed")

    private fun fetch(q: String): JSONObject? {
        for (url in ENDPOINTS) {
            val res = runCatching {
                val req = Request.Builder().url(url)
                    .post(okhttp3.FormBody.Builder().add("data", q).build())
                    .header("User-Agent", "Trasnik/2.0 (Android; github.com/richardgogola12/CarTracking)").build()
                client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { JSONObject(it) } else null }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    /**
     * Cesta pod autom a jej limit. [bearing] = smer jazdy (stupne), pri nízkej rýchlosti null.
     * Pri viacerých blízkych cestách vyhrá tá so zhodným smerom; aktuálna cesta má malú prednosť (bez preskakovania na križovatkách).
     */
    fun match(lat: Double, lng: Double, bearing: Float?): Match? {
        val ways = tiles[key(lat, lng)] ?: return null
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 110_540.0
        var best: Match? = null; var bestScore = Double.MAX_VALUE
        for (w in ways) {
            for (i in 1 until w.lat.size) {
                // vzdialenosť bodu od úseku v metroch (lokálna rovina)
                val ax = (w.lng[i - 1] - lng) * kx; val ay = (w.lat[i - 1] - lat) * ky
                val bx = (w.lng[i] - lng) * kx; val by = (w.lat[i] - lat) * ky
                val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
                val t = if (len2 > 0) ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                val px = ax + t * dx; val py = ay + t * dy
                val d = sqrt(px * px + py * py)
                if (d > 25) continue
                val segBearing = (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
                var forward = true
                var penalty = 0.0
                if (bearing != null && len2 > 1) {
                    val diff = angle(bearing.toDouble(), segBearing)
                    forward = diff <= 90
                    val along = if (forward) diff else 180 - diff
                    if (along > 50) continue                       // kolmá cesta (križovatka)
                    if (!forward && w.oneway) continue            // proti smeru jednosmerky nie
                    penalty = along * 0.3
                }
                val score = d + penalty - (if (w.id == lastWay) 6.0 else 0.0)
                if (score < bestScore) { bestScore = score; best = Match(limitOf(w.tags, forward), w, forward) }
            }
        }
        best?.let { lastWay = it.way.id }
        return best
    }

    private fun angle(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }

    /** Limit z OSM tagov; null = v mape nie je. */
    fun limitOf(tags: Map<String, String>, forward: Boolean): Int? {
        val dir = tags[if (forward) "maxspeed:forward" else "maxspeed:backward"]
        return parse(dir) ?: parse(tags["maxspeed"]) ?: parse(tags["zone:maxspeed"]) ?: parse(tags["maxspeed:type"])
    }

    fun parse(v: String?): Int? {
        val s = v?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s == "none" || s == "signals" || s == "variable") return null
        s.takeWhile { it.isDigit() }.toIntOrNull()?.let { n -> return if (s.contains("mph")) (n * 1.609).toInt() else n.takeIf { it in 5..150 } }
        return when {
            s.endsWith(":urban") -> 50
            s.endsWith(":rural") -> 90
            s.endsWith(":motorway") -> 130
            s.endsWith(":trunk") -> 130
            s.endsWith(":living_street") -> 20
            s.endsWith(":zone30") || s.endsWith(":30") -> 30
            s.endsWith(":zone20") || s.endsWith(":20") -> 20
            s == "walk" -> 5
            else -> s.substringAfterLast(':').takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it in 5..150 }
        }
    }

    // ---------------- Moje limity (opravy používateľa) ----------------

    data class Custom(val lat: Double, val lng: Double, val limit: Int, val bearing: Int?)

    fun parseCustom(s: String): List<Custom> = s.split(';').mapNotNull { e ->
        val p = e.split(',')
        if (p.size < 3) null else runCatching { Custom(p[0].toDouble(), p[1].toDouble(), p[2].toInt(), p.getOrNull(3)?.toIntOrNull()) }.getOrNull()
    }

    fun formatCustom(list: List<Custom>) = list.joinToString(";") {
        String.format(Locale.US, "%.5f,%.5f,%d", it.lat, it.lng, it.limit) + (it.bearing?.let { b -> ",$b" } ?: "")
    }

    /** Vlastný limit platí do 200 m od bodu a v rovnakom smere jazdy (±45°), ak bol smer zadaný. */
    fun custom(list: List<Custom>, lat: Double, lng: Double, bearing: Float?): Int? {
        return list.filter { c ->
            GeoMath.distanceM(lat, lng, c.lat, c.lng) <= 200.0 && (c.bearing == null || bearing == null || angle(bearing.toDouble(), c.bearing.toDouble()) <= 45)
        }.minByOrNull { c -> GeoMath.distanceM(lat, lng, c.lat, c.lng) }?.limit
    }
}
