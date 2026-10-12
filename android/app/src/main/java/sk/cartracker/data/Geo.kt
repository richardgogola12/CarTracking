package sk.cartracker.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

object Geo {
    /** Adresa z GPS súradníc (ulica + mesto). Vyžaduje internet; pri chybe vráti null. */
    suspend fun address(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale("sk", "SK")).getFromLocation(lat, lng, 1)?.firstOrNull()?.let(::format)
        }.getOrNull()
    }

    private fun format(a: Address): String? {
        val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null }
        val city = a.locality ?: a.subAdminArea ?: a.adminArea
        val parts = listOfNotNull(street, city)
        return if (parts.isNotEmpty()) parts.joinToString(", ") else a.getAddressLine(0)
    }
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
