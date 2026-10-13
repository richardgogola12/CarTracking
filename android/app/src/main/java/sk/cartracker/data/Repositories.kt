package sk.cartracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import sk.cartracker.data.local.AppDatabase
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.remote.ApiProvider
import sk.cartracker.data.remote.AuthRequest
import sk.cartracker.data.remote.PointDto
import sk.cartracker.data.remote.SyncRequest
import sk.cartracker.data.remote.TripDto
import sk.cartracker.data.remote.VehicleDto
import sk.cartracker.data.remote.VehicleSyncRequest
import sk.cartracker.detection.ActivityDetectionManager
import sk.cartracker.security.SessionStore
import sk.cartracker.sync.SyncWorker

class AuthRepository(
    private val ctx: Context,
    private val api: ApiProvider,
    private val session: SessionStore,
    private val settings: SettingsRepository,
) {
    suspend fun login(email: String, password: String): Result<Unit> = call {
        val r = api.get().login(AuthRequest(email.trim(), password))
        session.save(r.token, SessionStore.User(r.user.id, r.user.name, r.user.email))
    }

    suspend fun register(name: String, email: String, password: String): Result<Unit> = call {
        val r = api.get().register(AuthRequest(email.trim(), password, name = name.trim()))
        session.save(r.token, SessionStore.User(r.user.id, r.user.name, r.user.email))
    }

    /** Prihlásenie odtlačkom – token z biometrického úložiska overíme na serveri. */
    suspend fun loginWithToken(token: String): Result<Unit> {
        session.saveToken(token)
        return try {
            val me = api.get().me().user
            session.save(token, SessionStore.User(me.id, me.name, me.email))
            onLoggedIn()
            Result.success(Unit)
        } catch (e: HttpException) {
            session.clearSession()
            if (e.code() == 401) session.clearBiometricToken()
            Result.failure(Exception(if (e.code() == 401) "Platnosť prihlásenia vypršala, prihláste sa heslom." else api.errorMessage(e)))
        } catch (e: Exception) {
            // Offline: token je lokálne platný, appka môže fungovať a synchronizovať neskôr
            val prev = session.user()
            if (prev != null) Result.success(Unit) else { session.clearSession(); Result.failure(Exception(api.errorMessage(e))) }
        }
    }

    /**
     * @param forget true = token sa zruší aj na serveri a zmaže sa biometrický token.
     *               false = iba odhlásenie z appky, prihlásenie odtlačkom ostane funkčné.
     */
    suspend fun logout(forget: Boolean) {
        if (forget) {
            runCatching { api.get().logout() }
            session.clearBiometricToken()
            settings.update { it.copy(biometricEnabled = false) }
        }
        session.clearSession()
        ActivityDetectionManager.unregister(ctx)
    }

    private suspend fun call(block: suspend () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            block(); onLoggedIn(); Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(api.errorMessage(e)))
        }
    }

    private suspend fun onLoggedIn() {
        val s = settings.current()
        if (s.autoDetection || s.walkTracking) ActivityDetectionManager.register(ctx)
        SyncWorker.schedulePeriodic(ctx, s.syncWifiOnly)
    }
}

class TripRepository(
    private val db: AppDatabase,
    private val api: ApiProvider,
    private val session: SessionStore,
) {
    private val dao = db.tripDao()

    fun trips(): Flow<List<TripEntity>> = session.user()?.let { dao.finishedTrips(it.id) } ?: emptyFlow()
    fun pendingCount(): Flow<Int> = session.user()?.let { dao.pendingSyncCount(it.id) } ?: emptyFlow()
    fun trip(id: Long) = dao.tripFlow(id)
    suspend fun points(tripId: Long) = dao.points(tripId)

    suspend fun delete(trip: TripEntity) {
        dao.deleteTrip(trip.id)
        if (trip.uploaded) runCatching { api.get().deleteTrip(trip.uuid) }
    }

    /** Zmena kategórie / poznámky – jazda sa znova odošle na server. */
    suspend fun updateMeta(trip: TripEntity, category: String, note: String) {
        dao.updateTrip(trip.copy(category = category, note = note.trim(), synced = false))
    }

    suspend fun tripsBetween(from: Long, to: Long): List<TripEntity> =
        session.user()?.let { dao.tripsBetween(it.id, from, to) } ?: emptyList()

    /** Priradenie jazdy inému vozidlu. */
    suspend fun setVehicle(trip: TripEntity, vehicleUuid: String) {
        dao.updateTrip(trip.copy(vehicleUuid = vehicleUuid, synced = false))
    }

    /** Stav tachometra vozidla v čase [ts]: zadaný stav + km tohto vozidla od dátumu zadania. */
    suspend fun odometerAt(ts: Long, v: VehicleEntity?): Double? {
        val user = session.user() ?: return null
        if (v == null || v.odometerKm <= 0 || v.odometerDate <= 0) return null
        return if (ts >= v.odometerDate) v.odometerKm + dao.vehicleDistanceBetween(user.id, v.uuid, v.odometerDate, ts) / 1000.0
        else v.odometerKm - dao.vehicleDistanceBetween(user.id, v.uuid, ts, v.odometerDate) / 1000.0
    }

    suspend fun deleteAllLocal() {
        session.user()?.let { dao.deleteAllForUser(it.id) }
    }

    /** Pošle na server všetky ukončené jazdy, ktoré tam ešte nie sú (po dávkach bodov). */
    suspend fun syncAll(): Int = withContext(Dispatchers.IO) {
        val user = session.user() ?: return@withContext 0
        // najprv vozidlá (jazdy sa na ne odkazujú)
        val vehicles = dao.vehiclesToSync(user.id)
        if (vehicles.isNotEmpty()) {
            val res = api.get().syncVehicles(VehicleSyncRequest(vehicles.map { it.toDto() }))
            vehicles.filter { it.uuid in res.synced }.forEach { dao.updateVehicle(it.copy(synced = true)) }
        }
        var count = 0
        for (trip in dao.tripsToSync(user.id)) {
            while (true) {
                val batch = dao.unsyncedPoints(trip.id, 1000)
                val dto = trip.toDto(batch.map { PointDto(it.ts, it.lat, it.lng, it.speedKmh, it.accuracyM, it.bearing, it.altitudeM, it.event) })
                val res = api.get().sync(SyncRequest(listOf(dto)))
                if (trip.uuid !in res.synced) throw IllegalStateException("Server jazdu neprijal")
                if (batch.isNotEmpty()) dao.markPointsSynced(batch.map { it.id })
                if (batch.size < 1000) break
            }
            dao.updateTrip(trip.copy(synced = true, uploaded = true))
            count++
        }
        count
    }

    private fun TripEntity.toDto(points: List<PointDto>) = TripDto(
        uuid, startedAt, endedAt, distanceM, maxSpeedKmh, avgSpeedKmh,
        startLat, startLng, endLat, endLng, triggerSource, points,
        category, note, startAddress, endAddress, score, hardBrakes, hardAccels, speedingSec,
        type, steps, vehicleUuid,
    )

    private fun VehicleEntity.toDto() = VehicleDto(
        uuid, name, plate, fuelType, consumptionX10 / 10.0, fuelPriceCents / 100.0,
        odometerKm, odometerDate, btName, archived, updatedAt,
        limitUrban, limitRural, limitHighway,
    )
}

/** Vozidlá používateľa a aktívne vozidlo. */
class VehicleRepository(
    private val db: AppDatabase,
    private val session: SessionStore,
    private val settings: SettingsRepository,
) {
    private val dao = db.tripDao()

    fun vehicles(): Flow<List<VehicleEntity>> = session.user()?.let { dao.vehiclesFlow(it.id) } ?: emptyFlow()
    suspend fun all(): List<VehicleEntity> = session.user()?.let { dao.allVehicles(it.id) } ?: emptyList()
    suspend fun byUuid(uuid: String?): VehicleEntity? = uuid?.takeIf { it.isNotEmpty() }?.let { dao.vehicle(it) }
    suspend fun byBluetooth(address: String): VehicleEntity? = session.user()?.let { dao.vehicleByBt(it.id, address) }

    suspend fun active(): VehicleEntity? {
        ensureDefault()
        val s = settings.current()
        return byUuid(s.activeVehicleUuid)?.takeIf { !it.archived } ?: all().firstOrNull { !it.archived }
    }

    suspend fun setActive(uuid: String) = settings.update { it.copy(activeVehicleUuid = uuid) }

    suspend fun save(v: VehicleEntity): VehicleEntity {
        val user = session.user() ?: return v
        val toSave = v.copy(userId = user.id, synced = false, updatedAt = System.currentTimeMillis())
        return if (v.id == 0L) toSave.copy(id = dao.insertVehicle(toSave)) else toSave.also { dao.updateVehicle(it) }
    }

    /** Vozidlo sa nemaže (jazdy naň odkazujú), iba sa skryje. */
    suspend fun archive(v: VehicleEntity) {
        save(v.copy(archived = true))
        if (settings.current().activeVehicleUuid == v.uuid) {
            all().firstOrNull { !it.archived && it.uuid != v.uuid }?.let { setActive(it.uuid) } ?: setActive("")
        }
    }

    /**
     * Prvé vozidlo vznikne automaticky z pôvodných nastavení (verzia 1.1 mala jedno auto v nastaveniach)
     * a priradia sa mu všetky doterajšie jazdy autom.
     */
    suspend fun ensureDefault() {
        val user = session.user() ?: return
        if (dao.allVehicles(user.id).isNotEmpty()) return
        val s = settings.current()
        val v = VehicleEntity(
            uuid = java.util.UUID.randomUUID().toString(), userId = user.id,
            name = s.carName.ifBlank { "Moje auto" }, plate = s.licensePlate,
            consumptionX10 = s.fuelConsumptionX10, fuelPriceCents = s.fuelPriceCents,
            odometerKm = s.odometerKm, odometerDate = s.odometerDate,
            btAddress = s.carBtAddress, btName = s.carBtName,
        )
        dao.insertVehicle(v)
        dao.assignVehicleToUnassigned(user.id, v.uuid)
        settings.update { it.copy(activeVehicleUuid = v.uuid) }
    }
}

/** Palivo a náklady jazdy podľa jej vozidla (chôdza = 0). */
fun TripEntity.liters(v: VehicleEntity?, s: AppSettings): Double =
    if (isWalk) 0.0 else v?.liters(distanceM) ?: s.fuelLiters(distanceM)

fun TripEntity.cost(v: VehicleEntity?, s: AppSettings): Double =
    if (isWalk) 0.0 else v?.cost(distanceM) ?: s.fuelCost(distanceM)
