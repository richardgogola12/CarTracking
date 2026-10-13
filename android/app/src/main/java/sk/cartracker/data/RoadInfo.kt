package sk.cartracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import sk.cartracker.data.local.VehicleEntity
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Typ cesty podľa slovenských pravidiel: obec 50, mimo obce 90, diaľnica / rýchlostná cesta 130. */
enum class RoadZone(val label: String) { URBAN("v obci"), RURAL("mimo obce"), HIGHWAY("diaľnica") }

data class RoadInfo(
    val zone: RoadZone,
    val signLimit: Int?,        // limit zo značky v OpenStreetMap (maxspeed), ak je zadaný
    val roadName: String?,
)

/**
 * Typ cesty a limit z OpenStreetMap cez Nominatim (bez API kľúča, ~0,5 s).
 *  1. reverse zoom 17 → najbližšia cesta: typ (motorway, residential…) a maxspeed
 *  2. reverse zoom 18 → najbližší objekt: dom s číslom do 80 m = zastavaná oblasť (obec)
 * Výsledky sa ukladajú do pamäte po štvorcoch ~200 m. Podmienky Nominatim: max. 1 požiadavka/s, vlastný User-Agent.
 */
object RoadInfoProvider {
    private const val BASE = "https://nominatim.openstreetmap.org/reverse?format=jsonv2"
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, RoadInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RoadInfo>?) = size > 300
    }
    private val lock = Mutex()
    private var lastRequestAt = 0L

    suspend fun lookup(lat: Double, lng: Double): RoadInfo? = withContext(Dispatchers.IO) {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lng)
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        lock.withLock {
            try {
                val road = get("$BASE&lat=$lat&lon=$lng&zoom=17&extratags=1&addressdetails=1") ?: return@withLock null
                val near = get("$BASE&lat=$lat&lon=$lng&zoom=18&addressdetails=1")
                classify(lat, lng, road, near).also { synchronized(cache) { cache[key] = it } }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun get(url: String): JSONObject? {
        val wait = 1100 - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
        val req = Request.Builder().url(url)
            .header("User-Agent", "CarTracking/1.3 (Android; github.com/richardgogola12/CarTracking)")
            .header("Accept-Language", "sk")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return JSONObject(r.body?.string() ?: return null)
        }
    }

    private fun classify(lat: Double, lng: Double, road: JSONObject, near: JSONObject?): RoadInfo {
        val type = road.optString("type")
        val isRoad = road.optString("category") == "highway"
        val extra = road.optJSONObject("extratags")
        val maxspeedRaw = extra?.optString("maxspeed").orEmpty()
        val signLimit = maxspeedRaw.trim().takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it in 5..150 }
        val tagHint = (maxspeedRaw + " " + extra?.optString("source:maxspeed").orEmpty() + " " +
            extra?.optString("maxspeed:type").orEmpty()).lowercase()

        // Najbližší objekt: dom s číslom / budova blízko = zastavaná časť obce
        val nearAddr = near?.optJSONObject("address")
        val nearDist = near?.let {
            val la = it.optString("lat").toDoubleOrNull(); val lo = it.optString("lon").toDoubleOrNull()
            if (la != null && lo != null) distance(lat, lng, la, lo) else null
        }
        val nearIsBuilding = near != null && (near.optString("category") == "building" || nearAddr?.has("house_number") == true)
        val addr = road.optJSONObject("address")
        val urbanAddress = listOf("suburb", "city_district", "quarter", "neighbourhood").any { addr?.has(it) == true || nearAddr?.has(it) == true }

        val zone = when {
            isRoad && (type == "motorway" || type == "motorway_link" || type == "trunk" || type == "trunk_link") -> RoadZone.HIGHWAY
            "motorway" in tagHint -> RoadZone.HIGHWAY
            "urban" in tagHint -> RoadZone.URBAN
            "rural" in tagHint -> RoadZone.RURAL
            isRoad && (type == "residential" || type == "living_street" || type == "pedestrian") -> RoadZone.URBAN
            nearIsBuilding && (nearDist ?: Double.MAX_VALUE) <= 80.0 -> RoadZone.URBAN
            signLimit != null && signLimit <= 50 -> RoadZone.URBAN
            urbanAddress -> RoadZone.URBAN
            else -> RoadZone.RURAL
        }
        return RoadInfo(zone, signLimit, road.optString("name").ifBlank { null })
    }

    private fun distance(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(c - a); val dLon = Math.toRadians(d - b)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

/**
 * Platný limit:
 *  - bez značky = limit vozidla pre daný typ cesty (obec / mimo obce / diaľnica),
 *  - so značkou = značka; ak má vozidlo pre tento typ cesty nižší limit než je všeobecný
 *    (napr. dodávka s prívesom 80 mimo obce), platí nižší z nich.
 */
fun speedLimitFor(zone: RoadZone, signLimit: Int?, vehicle: VehicleEntity?, s: AppSettings): Int {
    val general = when (zone) { RoadZone.URBAN -> 50; RoadZone.RURAL -> 90; RoadZone.HIGHWAY -> 130 }
    val vehicleLimit = when (zone) {
        RoadZone.URBAN -> vehicle?.limitUrban
        RoadZone.RURAL -> vehicle?.limitRural
        RoadZone.HIGHWAY -> vehicle?.limitHighway
    } ?: general
    if (!s.useRoadSigns || signLimit == null) return vehicleLimit
    return if (vehicleLimit < general) minOf(signLimit, vehicleLimit) else signLimit
}
