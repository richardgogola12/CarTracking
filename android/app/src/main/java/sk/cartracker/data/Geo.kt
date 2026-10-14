package sk.cartracker.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

object Geo {
    /**
     * Adresa z GPS súradníc „ulica číslo, obec“. Vyžaduje internet; pri chybe vráti null.
     *  - obec z OpenStreetMap (hranice obcí na Slovensku sú presné – dedina nebude nahradená okresným mestom),
     *  - ulica a číslo z Google geokódera v telefóne (ak ich nemá, z OpenStreetMap),
     *  - bez OpenStreetMap: obec z Google, ak je blízko bodu; inak aspoň „okres …“ – nikdy nie okres vydávaný za obec.
     */
    suspend fun address(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val osm = runCatching { osm(lat, lng) }.getOrNull()
        val list = if (Geocoder.isPresent()) runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale("sk", "SK")).getFromLocation(lat, lng, 5)
        }.getOrNull().orEmpty() else emptyList()
        val first = list.firstOrNull()
        val street = first?.let { listOfNotNull(it.thoroughfare, it.subThoroughfare).joinToString(" ").ifBlank { null } } ?: osm?.first
        val place = osm?.second ?: run {
            val town = list.firstOrNull { !it.locality.isNullOrBlank() }
            town?.takeIf { it === first || !it.hasLatitude() || dist(lat, lng, it.latitude, it.longitude) < 6000f }?.locality
                ?: first?.subAdminArea?.let { if (it.lowercase().startsWith("okres")) it else "okres $it" }
        }
        listOfNotNull(street, place).joinToString(", ").ifBlank { first?.getAddressLine(0) }
    }

    private val osmCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<String?, String?>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<String?, String?>>?) = size > 500
    })

    /** (ulica, obec) z OpenStreetMap; výsledky sa pamätajú po štvorcoch ~100 m. */
    private suspend fun osm(lat: Double, lng: Double): Pair<String?, String?>? {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lng)
        osmCache[key]?.let { return it }
        return osmFetch(lat, lng)?.also { if (it.second != null) osmCache[key] = it }
    }

    private suspend fun osmFetch(lat: Double, lng: Double): Pair<String?, String?>? {
        val o = RoadInfoProvider.reverse(lat, lng, 18) ?: return null
        val a = o.optJSONObject("address") ?: return null
        fun f(k: String) = a.optString(k).takeIf { it.isNotBlank() }
        val place = f("village") ?: f("town") ?: f("city") ?: f("municipality") ?: f("hamlet") ?: f("suburb")
        val road = f("road")?.let { r -> f("house_number")?.let { "$r $it" } ?: r }
        return road to place
    }

    private fun dist(a: Double, b: Double, c: Double, d: Double): Float {
        val r = FloatArray(1); android.location.Location.distanceBetween(a, b, c, d, r); return r[0]
    }

    /** Bod vhodný na zistenie adresy: najpresnejší z prvých (resp. posledných) bodov trasy. */
    fun bestStart(pts: List<sk.cartracker.data.local.PointEntity>) = pickBest(pts.take(15))
    fun bestEnd(pts: List<sk.cartracker.data.local.PointEntity>) = pickBest(pts.takeLast(15).reversed())
    private fun pickBest(pts: List<sk.cartracker.data.local.PointEntity>) =
        pts.firstOrNull { (it.accuracyM ?: 99f) <= 25f } ?: pts.minByOrNull { it.accuracyM ?: 99f }
}

object DrivingScore {
    const val BRAKE_MS2 = -3.5   // prudké brzdenie (m/s²)
    const val ACCEL_MS2 = 3.0    // prudké zrýchlenie (m/s²)

    /**
     * Skóre 0–100. Udalosti sa prepočítavajú na 10 km, aby krátke a dlhé jazdy boli porovnateľné;
     * prekračovanie rýchlosti sa penalizuje podľa podielu času jazdy.
     */
    fun compute(distanceM: Double, durationMs: Long, brakes: Int, accels: Int, speedingSec: Int): Int {
        val per10km = (distanceM / 10_000.0).coerceAtLeast(1.0)
        val eventPenalty = (brakes * 6 + accels * 4) / per10km
        val speedingShare = if (durationMs > 0) speedingSec * 1000.0 / durationMs else 0.0
        val speedingPenalty = (speedingShare * 100.0).coerceAtMost(40.0)
        return (100.0 - eventPenalty - speedingPenalty).coerceIn(0.0, 100.0).roundToInt()
    }

    fun label(score: Int) = when {
        score >= 90 -> "Výborná"
        score >= 75 -> "Dobrá"
        score >= 55 -> "Priemerná"
        else -> "Riziková"
    }
}
