package sk.cartracker.data

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.cartracker.container
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatTime

/**
 * Kontrola jázd: nájde pravdepodobné chyby záznamu a navrhne opravu.
 *  - dlhé státie v jazde (zabudnutý koniec, čakanie pred odjazdom, zastávka uprostred),
 *  - jazda rozdelená na dve (krátka prestávka, ktorá sa nezlúčila),
 *  - výpadok GPS (trasa doplnená priamou čiarou),
 *  - nereálna rýchlosť (chyba GPS), „jazda“ pod 1 km trvajúca vyše 20 min.
 */
object TripCheck {
    enum class Kind { STOP_END, STOP_START, STOP_MIDDLE, SPLIT, GPS_GAP, SPEED, NOT_A_TRIP }
    enum class Fix(val label: String) { TRIM_END("Ukončiť v čase zastavenia"), TRIM_START("Začať pri rozjazde"),
        SPLIT_STOP("Rozdeliť v mieste státia"), MERGE("Zlúčiť do jednej"), DELETE("Zmazať") }

    data class Issue(
        val key: String, val kind: Kind, val trip: TripEntity, val other: TripEntity? = null,
        val title: String, val detail: String, val fix: Fix? = null,
        val fromTs: Long = 0, val toTs: Long = 0,
    )

    private const val STOP_RADIUS_M = 150f
    private const val STOP_MIN_MS = 15 * 60_000L
    private const val DAYS = 30

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("trip_check", Context.MODE_PRIVATE)
    fun dismissed(ctx: Context): Set<String> = prefs(ctx).getStringSet("dismissed", emptySet()) ?: emptySet()
    fun dismiss(ctx: Context, key: String) {
        val set = (dismissed(ctx) + key).toList().takeLast(300).toSet()
        prefs(ctx).edit().putStringSet("dismissed", set).apply()
    }

    private fun dist(a: PointEntity, b: PointEntity): Float {
        val r = FloatArray(1)
        Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, r)
        return r[0]
    }

    /** Úseky, kde auto stálo na mieste aspoň 15 min (indexy prvého a posledného bodu). */
    private fun stops(pts: List<PointEntity>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < pts.size - 1) {
            var j = i
            while (j + 1 < pts.size && dist(pts[i], pts[j + 1]) < STOP_RADIUS_M) j++
            if (pts[j].ts - pts[i].ts >= STOP_MIN_MS) { out.add(i to j); i = j + 1 } else i++
        }
        return out
    }

    suspend fun scan(ctx: Context): List<Issue> = withContext(Dispatchers.IO) {
        val c = ctx.container
        val now = System.currentTimeMillis()
        val trips = c.trips.tripsBetween(now - DAYS * 86_400_000L, now + 86_400_000L).filter { !it.isWalk && it.endedAt != null }
        val skip = dismissed(ctx)
        val out = ArrayList<Issue>()

        for (t in trips) {
            val end = t.endedAt ?: continue
            val dur = end - t.startedAt
            if (t.distanceM < 1000 && dur > 20 * 60_000L) {
                out += Issue("nt:${t.uuid}", Kind.NOT_A_TRIP, t, title = "Pravdepodobne to nebola jazda",
                    detail = "${formatDistance(t.distanceM)} za ${formatDuration(dur)} – skôr státie alebo chyba detekcie.", fix = Fix.DELETE)
                continue
            }
            if (t.maxSpeedKmh > 220 || (dur > 60_000 && t.avgSpeedKmh > 160)) {
                out += Issue("sp:${t.uuid}", Kind.SPEED, t, title = "Nereálna rýchlosť",
                    detail = "max. ${t.maxSpeedKmh.toInt()} km/h, Ø ${t.avgSpeedKmh.toInt()} km/h – pravdepodobne chyba GPS. Skontrolujte trasu.")
            }
            val pts = c.trips.points(t.id).filter { (it.accuracyM ?: 0f) <= 60f }
            if (pts.size < 4) continue
            for ((i, j) in stops(pts)) {
                val from = pts[i].ts; val to = pts[j].ts
                val len = formatDuration(to - from)
                when {
                    j >= pts.size - 2 -> out += Issue("se:${t.uuid}:$from", Kind.STOP_END, t, title = "Zabudnutý koniec jazdy",
                        detail = "Auto od ${formatTime(from)} stálo $len, jazda sa ukončila až ${formatTime(end)}.", fix = Fix.TRIM_END, fromTs = from, toTs = to)
                    i <= 1 -> out += Issue("ss:${t.uuid}:$from", Kind.STOP_START, t, title = "Dlhé čakanie pred odjazdom",
                        detail = "Jazda začala ${formatTime(t.startedAt)}, auto sa pohlo až ${formatTime(to)} ($len státia).", fix = Fix.TRIM_START, fromTs = from, toTs = to)
                    else -> out += Issue("sm:${t.uuid}:$from", Kind.STOP_MIDDLE, t, title = "Dlhá zastávka uprostred jazdy",
                        detail = "${formatTime(from)}–${formatTime(to)} ($len) na jednom mieste – možno ide o dve jazdy.", fix = Fix.SPLIT_STOP, fromTs = from, toTs = to)
                }
            }
            for (k in 1 until pts.size) {
                val gap = pts[k].ts - pts[k - 1].ts
                val d = dist(pts[k - 1], pts[k])
                if (gap > 3 * 60_000L && d > 1000f) {
                    out += Issue("gg:${t.uuid}:${pts[k - 1].ts}", Kind.GPS_GAP, t, title = "Výpadok GPS",
                        detail = "${formatTime(pts[k - 1].ts)}–${formatTime(pts[k].ts)} bez polohy, ${formatDistance(d.toDouble())} doplnených priamou čiarou (tunel, slabý signál alebo úspora batérie telefónu).")
                    break
                }
            }
        }
        // Rozdelené jazdy: rovnaké auto, prestávka do 30 min, koniec ↔ začiatok do 1 km
        val sorted = trips.sortedBy { it.startedAt }
        for (k in 1 until sorted.size) {
            val a = sorted[k - 1]; val b = sorted[k]
            val gap = b.startedAt - (a.endedAt ?: continue)
            if (gap < 0 || gap > 30 * 60_000L) continue
            if (a.vehicleUuid != null && b.vehicleUuid != null && a.vehicleUuid != b.vehicleUuid) continue
            val al = a.endLat; val ag = a.endLng; val bl = b.startLat; val bg = b.startLng
            if (al == null || ag == null || bl == null || bg == null) continue
            val r = FloatArray(1)
            Location.distanceBetween(al, ag, bl, bg, r)
            if (r[0] > 1000f) continue
            out += Issue("mg:${a.uuid}:${b.uuid}", Kind.SPLIT, a, b, title = "Jazda rozdelená na dve",
                detail = "${formatTime(a.startedAt)}–${formatTime(a.endedAt!!)} a ${formatTime(b.startedAt)}–${formatTime(b.endedAt ?: b.startedAt)}, prestávka ${formatDuration(gap)}.",
                fix = Fix.MERGE)
        }
        out.filter { it.key !in skip }.sortedByDescending { it.trip.startedAt }
    }

    /** Vykoná navrhnutú opravu. @return text pre používateľa */
    suspend fun apply(ctx: Context, issue: Issue): String = withContext(Dispatchers.IO) {
        val c = ctx.container
        val t = c.db.tripDao().getTrip(issue.trip.id) ?: return@withContext "Jazda už neexistuje"
        val msg = when (issue.fix) {
            Fix.DELETE -> { c.trips.delete(t); "Jazda zmazaná" }
            Fix.MERGE -> {
                val o = issue.other?.let { c.db.tripDao().getTrip(it.id) } ?: return@withContext "Druhá jazda už neexistuje"
                c.trips.merge(listOf(t, o)); "Jazdy zlúčené"
            }
            Fix.TRIM_END -> { trim(ctx, t, t.startedAt, issue.fromTs); "Koniec jazdy posunutý na ${formatTime(issue.fromTs)}" }
            Fix.TRIM_START -> { trim(ctx, t, issue.toTs, t.endedAt ?: issue.toTs); "Začiatok jazdy posunutý na ${formatTime(issue.toTs)}" }
            Fix.SPLIT_STOP -> {
                val newId = c.trips.split(t, issue.toTs, ctx)
                c.db.tripDao().getTrip(t.id)?.let { first -> trim(ctx, first, first.startedAt, issue.fromTs) }
                c.db.tripDao().getTrip(newId)?.let { second -> c.places.applyToTrip(second, c.places.list(), c.settings.current(), setCategory = false).let { c.trips.updateTripRaw(it.copy(synced = false)) } }
                "Jazda rozdelená na dve"
            }
            null -> "Bez opravy"
        }
        dismiss(ctx, issue.key)
        val s = c.settings.current()
        if (s.autoSync) sk.cartracker.sync.SyncWorker.enqueueNow(ctx, s.syncWifiOnly)
        DiagLog.log(ctx, DiagLog.APP, "Kontrola jázd: $msg")
        msg
    }

    /** Zmení začiatok / koniec jazdy (body mimo času ostanú – sú na tom istom mieste). */
    private suspend fun trim(ctx: Context, t: TripEntity, start: Long, end: Long) {
        val c = ctx.container
        val pts = c.trips.points(t.id)
        val inside = pts.filter { it.ts in start..end }
        val a = inside.firstOrNull(); val b = inside.lastOrNull()
        val h = (end - start) / 3_600_000.0
        c.trips.updateTripRaw(t.copy(
            startedAt = start, endedAt = end,
            startLat = a?.lat ?: t.startLat, startLng = a?.lng ?: t.startLng,
            endLat = b?.lat ?: t.endLat, endLng = b?.lng ?: t.endLng,
            avgSpeedKmh = if (h > 0) t.distanceM / 1000.0 / h else t.avgSpeedKmh,
            synced = false,
        ))
    }
}
