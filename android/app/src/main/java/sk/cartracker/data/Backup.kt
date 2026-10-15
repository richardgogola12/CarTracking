package sk.cartracker.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.cartracker.container
import sk.cartracker.data.local.PlaceEntity
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.RefuelEntity
import sk.cartracker.data.local.ReminderEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Záloha všetkých údajov do jedného súboru (JSON komprimovaný gzip, prípona .ctbak).
 * Obnova údaje PRIDÁ – čo už v telefóne je (podľa UUID), sa preskočí, takže obnovu
 * možno spustiť aj opakovane a aj do iného účtu.
 */
object Backup {
    private const val FORMAT = "cartracking-backup"
    private val gson = Gson()

    data class Result(val trips: Int, val points: Int, val vehicles: Int, val places: Int, val refuels: Int, val reminders: Int, val skipped: Int) {
        override fun toString() =
            "Jazdy: $trips (body trasy: $points)\nVozidlá: $vehicles\nMiesta: $places\nTankovania: $refuels\nPripomienky: $reminders" +
                if (skipped > 0) "\nPreskočené (už existujú): $skipped" else ""
    }

    suspend fun export(ctx: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val c = ctx.container
        val user = c.session.user() ?: error("Nie ste prihlásený")
        val tripDao = c.db.tripDao()
        val ex = c.db.extrasDao()
        val vehicles = tripDao.allVehicles(user.id)
        val places = ex.allPlaces(user.id).filter { !it.deleted }
        val refuels = ex.allRefuels(user.id).filter { !it.deleted }
        val reminders = ex.allReminders(user.id).filter { !it.deleted }
        val trips = tripDao.allTrips(user.id).filter { it.finished }
        var points = 0

        val out = ctx.contentResolver.openOutputStream(uri) ?: error("Súbor sa nedá vytvoriť")
        JsonWriter(OutputStreamWriter(GZIPOutputStream(out), Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("format").value(FORMAT)
            w.name("version").value(1)
            w.name("created").value(System.currentTimeMillis())
            w.name("user").value(user.email)
            w.name("settings"); gson.toJson(c.settings.current(), AppSettings::class.java, w)
            w.name("vehicles"); gson.toJson(vehicles, List::class.java, w)
            w.name("places"); gson.toJson(places, List::class.java, w)
            w.name("refuels"); gson.toJson(refuels, List::class.java, w)
            w.name("reminders"); gson.toJson(reminders, List::class.java, w)
            w.name("trips").beginArray()
            for (t in trips) {
                val pts = tripDao.points(t.id)
                points += pts.size
                w.beginObject()
                w.name("trip"); gson.toJson(t, TripEntity::class.java, w)
                w.name("points"); gson.toJson(pts, List::class.java, w)
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        Result(trips.size, points, vehicles.size, places.size, refuels.size, reminders.size, 0)
    }

    suspend fun restore(ctx: Context, uri: Uri, restoreSettings: Boolean): Result = withContext(Dispatchers.IO) {
        val c = ctx.container
        val user = c.session.user() ?: error("Nie ste prihlásený")
        val db = c.db
        val tripDao = db.tripDao()
        val ex = db.extrasDao()
        var nTrips = 0; var nPoints = 0; var nVeh = 0; var nPlaces = 0; var nRef = 0; var nRem = 0; var skipped = 0

        val input = ctx.contentResolver.openInputStream(uri) ?: error("Súbor sa nedá otvoriť")
        val stream = runCatching { GZIPInputStream(input) }.getOrElse { error("Toto nie je záloha aplikácie Trasník") }
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.beginObject()
            var formatOk = false
            while (r.hasNext()) {
                when (r.nextName()) {
                    "format" -> formatOk = r.nextString() == FORMAT
                    "settings" -> {
                        val s: AppSettings = gson.fromJson(r, AppSettings::class.java)
                        if (restoreSettings) {
                            // adresu servera, biometriu a aktívne vozidlo nechávame podľa tohto telefónu
                            c.settings.update { cur -> s.copy(serverUrl = cur.serverUrl, biometricEnabled = cur.biometricEnabled, activeVehicleUuid = cur.activeVehicleUuid) }
                        }
                    }
                    "vehicles" -> readArray(r, VehicleEntity::class.java) { v ->
                        if (tripDao.vehicle(v.uuid) != null) skipped++
                        else { tripDao.insertVehicle(v.copy(id = 0, userId = user.id, synced = false)); nVeh++ }
                    }
                    "places" -> readArray(r, PlaceEntity::class.java) { p ->
                        if (ex.place(p.uuid) != null) skipped++
                        else { ex.insertPlace(p.copy(id = 0, userId = user.id, synced = false)); nPlaces++ }
                    }
                    "refuels" -> readArray(r, RefuelEntity::class.java) { x ->
                        if (ex.refuel(x.uuid) != null) skipped++
                        else { ex.insertRefuel(x.copy(id = 0, userId = user.id, synced = false)); nRef++ }
                    }
                    "reminders" -> readArray(r, ReminderEntity::class.java) { x ->
                        if (ex.reminder(x.uuid) != null) skipped++
                        else { ex.insertReminder(x.copy(id = 0, userId = user.id, synced = false)); nRem++ }
                    }
                    "trips" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var trip: TripEntity? = null
                            var pts: List<PointEntity> = emptyList()
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "trip" -> trip = gson.fromJson(r, TripEntity::class.java)
                                    "points" -> {
                                        val list = mutableListOf<PointEntity>()
                                        r.beginArray(); while (r.hasNext()) list += gson.fromJson<PointEntity>(r, PointEntity::class.java); r.endArray()
                                        pts = list
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            val t = trip ?: continue
                            if (tripDao.tripByUuid(t.uuid) != null) { skipped++; continue }
                            db.withTransaction {
                                val id = tripDao.insertTrip(t.copy(id = 0, userId = user.id, synced = false, uploaded = false, finished = true))
                                pts.chunked(500).forEach { chunk -> tripDao.insertPoints(chunk.map { it.copy(id = 0, tripId = id, synced = false) }) }
                            }
                            nTrips++; nPoints += pts.size
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!formatOk) error("Toto nie je záloha aplikácie Trasník")
        }
        Result(nTrips, nPoints, nVeh, nPlaces, nRef, nRem, skipped)
    }

    private suspend fun <T> readArray(r: JsonReader, cls: Class<T>, each: suspend (T) -> Unit) {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return }
        r.beginArray()
        while (r.hasNext()) each(gson.fromJson(r, cls))
        r.endArray()
    }
}
