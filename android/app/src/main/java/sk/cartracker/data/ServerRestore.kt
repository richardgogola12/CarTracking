package sk.cartracker.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.cartracker.container
import sk.cartracker.data.local.PlaceEntity
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.RefuelEntity
import sk.cartracker.data.local.ReminderEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.remote.PointsRequest
import sk.cartracker.data.remote.SettingsBody

/**
 * Obnova údajov zo servera po prihlásení (nový telefón, preinštalovaná aplikácia).
 * Stiahne nastavenia, vozidlá, miesta, tankovania, pripomienky a všetky jazdy aj s trasami.
 * Údaje sa PRIDÁVAJÚ – čo už v telefóne je (podľa UUID), sa preskočí, takže sa dá spustiť opakovane.
 */
object ServerRestore {
    data class Summary(val trips: Int, val points: Int, val vehicles: Int, val items: Int, val settings: Boolean, val nothing: Boolean = false) {
        override fun toString() = buildString {
            append("Jazdy a chôdza: $trips (body trasy: $points), vozidlá: $vehicles, miesta/tankovania/pripomienky: $items")
            if (settings) append(", nastavenia obnovené")
        }
    }

    /** Nastavenia, ktoré sa nesynchronizujú (patria k tomuto telefónu). */
    private val LOCAL_ONLY = setOf("serverUrl", "biometricEnabled")
    private val plain = Gson()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    /** Kým beží prvá obnova, nevytvára sa automaticky predvolené vozidlo (aby nevzniklo navyše). */
    fun isPending(ctx: Context) = prefs(ctx).getBoolean("restore_pending", false)
    fun setPending(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("restore_pending", v).apply()

    private fun settingsKey(userId: Int) = "settings_ready_$userId"

    /** Nastavenia sa smú odosielať až keď sa najprv stiahli zo servera – inak by predvolené prepísali uložené. */
    fun settingsReady(ctx: Context, userId: Int) = prefs(ctx).getBoolean(settingsKey(userId), false)

    fun settingsJson(s: AppSettings): JsonObject =
        plain.toJsonTree(s).asJsonObject.also { o -> LOCAL_ONLY.forEach { o.remove(it) } }

    suspend fun run(ctx: Context, progress: suspend (String, Int, Int) -> Unit): Summary = withContext(Dispatchers.IO) {
        val c = ctx.container
        val user = c.session.user() ?: error("Nie ste prihlásený")
        val api = c.api.get()
        val g = c.api.gson
        val db = c.db
        val tripDao = db.tripDao()
        val ex = db.extrasDao()

        // 0) má používateľ na serveri vôbec niečo? Ak nie, nič sa nesťahuje ani nezobrazuje.
        val status = runCatching { api.backupStatus() }.getOrNull()   // starší server túto adresu nemá → pokračujeme
        if (status != null && status.isEmpty) {
            prefs(ctx).edit().putBoolean(settingsKey(user.id), true).apply()   // súčasné nastavenia sa odošlú ako prvá záloha
            return@withContext Summary(0, 0, 0, 0, false, nothing = true)
        }

        // 1) nastavenia – zlúčime so súčasnými (nové položky, ktoré server nepozná, ostanú)
        progress("Nastavenia", 0, 0)
        var settingsRestored = false
        val remoteSettings = if (status?.settings == false) null else runCatching { api.settings() }.getOrNull()
        val rs = remoteSettings?.settings?.takeIf { it.isJsonObject }?.asJsonObject
        if (rs != null && (remoteSettings?.updatedAt ?: 0L) > 0L) {
            c.settings.update { cur ->
                val merged = plain.toJsonTree(cur).asJsonObject
                rs.entrySet().forEach { (k, v) -> if (k !in LOCAL_ONLY && merged.has(k)) merged.add(k, v) }
                runCatching { plain.fromJson(merged, AppSettings::class.java) }.getOrNull()
                    ?.copy(serverUrl = cur.serverUrl, biometricEnabled = cur.biometricEnabled) ?: cur
            }
            settingsRestored = true
        }
        // server nastavenia mal → sú zhodné; nemal → hash nenastavíme, aby sa súčasné nastavenia hneď odoslali
        prefs(ctx).edit().putBoolean(settingsKey(user.id), true).apply()
        if (settingsRestored) prefs(ctx).edit()
            .putString("settings_pushed_${user.id}", settingsJson(c.settings.current()).toString().hashCode().toString()).apply()

        // 2) vozidlá (vlastné) + zdieľané
        progress("Vozidlá", 0, 0)
        var nVeh = 0
        for (r in api.vehicles().vehicles.filter { it.isOwner }) {
            if (tripDao.vehicle(r.uuid) != null) continue
            tripDao.insertVehicle(VehicleEntity(
                uuid = r.uuid, userId = user.id, name = r.name ?: "Vozidlo", plate = r.plate ?: "", fuelType = r.fuelType ?: "petrol",
                consumptionX10 = Math.round(r.consumption * 10).toInt(), fuelPriceCents = Math.round(r.fuelPrice * 100).toInt(),
                odometerKm = r.odometerKm, odometerDate = r.odometerDate, btAddress = r.btAddress ?: "", btName = r.btName ?: "",
                archived = r.archived, synced = true, updatedAt = r.updatedAt,
                limitUrban = r.limitUrban, limitRural = r.limitRural, limitHighway = r.limitHighway,
                ratePerKmMilli = Math.round(r.ratePerKm * 1000).toInt(),
            ))
            nVeh++
        }
        runCatching { c.vehicles.pullShared() }

        // 3) miesta, tankovania, pripomienky
        progress("Miesta, tankovania, pripomienky", 0, 0)
        var nItems = 0
        for (it in api.items().items) {
            val data = it.data?.takeIf { d -> d.isJsonObject } ?: continue
            if (it.deleted != 0) continue
            when (it.kind) {
                "place" -> if (ex.place(it.uuid) == null) {
                    g.fromJson(data, PlaceEntity::class.java)?.let { p -> ex.insertPlace(p.copy(id = 0, uuid = it.uuid, userId = user.id, synced = true)); nItems++ }
                }
                "refuel" -> if (ex.refuel(it.uuid) == null) {
                    g.fromJson(data, RefuelEntity::class.java)?.let { r -> ex.insertRefuel(r.copy(id = 0, uuid = it.uuid, userId = user.id, synced = true)); nItems++ }
                }
                "expense" -> if (ex.expense(it.uuid) == null) {
                    g.fromJson(data, sk.cartracker.data.local.ExpenseEntity::class.java)?.let { e -> ex.insertExpense(e.copy(id = 0, uuid = it.uuid, userId = user.id, synced = true)); nItems++ }
                }
                "reminder" -> if (ex.reminder(it.uuid) == null) {
                    g.fromJson(data, ReminderEntity::class.java)?.let { r -> ex.insertReminder(r.copy(id = 0, uuid = it.uuid, userId = user.id, synced = true)); nItems++ }
                }
            }
        }

        // 4) jazdy a chôdza – najprv zoznam, potom trasy po 50 jazdách
        progress("Kroky", 0, 0)
        runCatching { c.steps.restore(System.currentTimeMillis() - 400L * 86_400_000L) }
        progress("Zoznam jázd", 0, 0)
        val missing = mutableListOf<sk.cartracker.data.remote.RemoteTrip>()
        var offset = 0
        while (true) {
            val page = api.trips(1000, offset).trips
            page.filter { tripDao.tripByUuid(it.uuid) == null }.forEach { missing += it }
            if (page.size < 1000) break
            offset += page.size
        }
        var nTrips = 0; var nPoints = 0
        for (chunk in missing.sortedBy { it.startedAt }.chunked(50)) {
            progress("Jazdy a trasy", nTrips, missing.size)
            val pts = api.tripPoints(PointsRequest(chunk.map { it.uuid })).points
            db.withTransaction {
                for (r in chunk) {
                    if (tripDao.tripByUuid(r.uuid) != null) continue
                    val id = tripDao.insertTrip(TripEntity(
                        uuid = r.uuid, userId = user.id, startedAt = r.startedAt, endedAt = r.endedAt ?: r.startedAt,
                        distanceM = r.distanceM ?: 0.0, maxSpeedKmh = r.maxSpeedKmh ?: 0.0, avgSpeedKmh = r.avgSpeedKmh ?: 0.0,
                        startLat = r.startLat, startLng = r.startLng, endLat = r.endLat, endLng = r.endLng,
                        triggerSource = r.triggerSource ?: "auto", finished = true, synced = true, uploaded = true,
                        category = r.category ?: "private", note = r.note ?: "", startAddress = r.startAddress, endAddress = r.endAddress,
                        score = r.score, hardBrakes = r.hardBrakes ?: 0, hardAccels = r.hardAccels ?: 0, speedingSec = r.speedingSec ?: 0,
                        type = r.type ?: "car", steps = r.steps ?: 0, vehicleUuid = r.vehicleUuid, startPlace = r.startPlace, endPlace = r.endPlace,
                    ))
                    val list = pts[r.uuid].orEmpty()
                    list.chunked(500).forEach { part ->
                        tripDao.insertPoints(part.map { p ->
                            PointEntity(tripId = id, ts = p.ts, lat = p.lat, lng = p.lng, speedKmh = p.speedKmh, accuracyM = p.accuracyM,
                                bearing = p.bearing, altitudeM = p.altitudeM, synced = true, event = p.event)
                        })
                    }
                    nTrips++; nPoints += list.size
                }
            }
        }
        progress("Hotovo", nTrips, missing.size)

        // aktívne vozidlo musí existovať
        val s = c.settings.current()
        if (s.activeVehicleUuid.isNotEmpty() && tripDao.vehicle(s.activeVehicleUuid) == null) c.vehicles.setActive("")
        Summary(nTrips, nPoints, nVeh, nItems, settingsRestored)
    }

    /** Odošle nastavenia na server, ak sa zmenili (a ak sa už predtým stiahli zo servera). */
    suspend fun pushSettings(ctx: Context) = withContext(Dispatchers.IO) {
        val c = ctx.container
        val user = c.session.user() ?: return@withContext
        if (!settingsReady(ctx, user.id)) return@withContext
        val json = settingsJson(c.settings.current())
        val hash = json.toString().hashCode().toString()
        val key = "settings_pushed_${user.id}"
        if (prefs(ctx).getString(key, null) == hash) return@withContext
        c.api.get().saveSettings(SettingsBody(json, System.currentTimeMillis()))
        prefs(ctx).edit().putString(key, hash).apply()
    }
}
