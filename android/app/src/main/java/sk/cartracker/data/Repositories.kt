package sk.cartracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import sk.cartracker.data.local.RefuelEntity
import sk.cartracker.data.local.AppDatabase
import androidx.room.withTransaction
import sk.cartracker.data.local.CATEGORY_BUSINESS
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
        // údaje a nastavenia zo servera (nový telefón / preinštalovanie); čo v telefóne už je, sa preskočí
        sk.cartracker.sync.RestoreWorker.start(ctx)
        val s = settings.current()
        if (s.autoDetection || s.walkTracking) ActivityDetectionManager.register(ctx)
        SyncWorker.schedulePeriodic(ctx, s.syncWifiOnly)
    }
}

class TripRepository(
    private val db: AppDatabase,
    private val api: ApiProvider,
    private val session: SessionStore,
    /** Jazdy zmazané v telefóne, ktoré treba zmazať aj na serveri (pri výpadku siete sa to dokončí pri synchronizácii). */
    private val pendingDeletes: android.content.SharedPreferences,
) {
    private val dao = db.tripDao()

    fun trips(): Flow<List<TripEntity>> = session.user()?.let { dao.finishedTrips(it.id) } ?: emptyFlow()
    fun pendingCount(): Flow<Int> = session.user()?.let { dao.pendingSyncCount(it.id) } ?: emptyFlow()
    fun trip(id: Long) = dao.tripFlow(id)
    suspend fun points(tripId: Long) = dao.points(tripId)

    suspend fun delete(trip: TripEntity) {
        dao.deleteTrip(trip.id)
        if (trip.uploaded) deleteRemote(listOf(trip.uuid))
    }

    private suspend fun deleteRemote(uuids: List<String>) {
        val failed = uuids.filterNot { uuid ->
            try { api.get().deleteTrip(uuid); true }
            catch (e: HttpException) { e.code() == 404 }   // na serveri už nie je = hotovo
            catch (e: Exception) { false }
        }
        synchronized(pendingDeletes) {
            val set = pendingDeletes.getStringSet("uuids", emptySet())!!.toMutableSet()
            set.removeAll(uuids.toSet()); set.addAll(failed)
            pendingDeletes.edit().putStringSet("uuids", set).apply()
        }
    }

    suspend fun updateTripRaw(t: TripEntity) = dao.updateTrip(t)

    suspend fun nextTrip(t: TripEntity): TripEntity? = session.user()?.let { dao.nextTrip(it.id, t.type, t.startedAt) }

    /**
     * Kalibrácia km podľa tachometra: porovná rozdiel tachometra medzi prvým a posledným tankovaním
     * s km jázd vozidla v tom istom období. @return (pomer tachometer/aplikácia, km tachometra, km jázd) alebo null
     */
    suspend fun odometerRatio(vehicleUuid: String, refuels: List<RefuelEntity>): Triple<Double, Double, Double>? {
        val user = session.user() ?: return null
        val r = refuels.filter { it.odometerKm > 0 }.sortedBy { it.ts }
        if (r.size < 2) return null
        val odoKm = (r.last().odometerKm - r.first().odometerKm).toDouble()
        val appKm = dao.vehicleDistanceBetween(user.id, vehicleUuid, r.first().ts, r.last().ts) / 1000.0
        if (odoKm < 100 || appKm < 50) return null
        return Triple(odoKm / appKm, odoKm, appKm)
    }

    /** Prepočíta km všetkých jázd vozidla koeficientom (pri oprave podľa tachometra aj spätne). */
    suspend fun rescaleVehicleTrips(vehicleUuid: String, ratio: Double): Int = withContext(Dispatchers.IO) {
        val user = session.user() ?: return@withContext 0
        // ručne zadané jazdy (bez trasy) majú km od používateľa – tie sa neprepočítavajú
        val list = dao.allTrips(user.id).filter { !it.isWalk && it.vehicleUuid == vehicleUuid && it.finished && dao.pointCount(it.id) > 0 }
        db.withTransaction {
            list.forEach { t ->
                val d = t.distanceM * ratio
                val h = ((t.endedAt ?: t.startedAt) - t.startedAt) / 3_600_000.0
                dao.updateTrip(t.copy(distanceM = d, avgSpeedKmh = if (h > 0) d / 1000 / h else t.avgSpeedKmh, synced = false))
            }
        }
        list.size
    }

    /** Ručne pridaná jazda / chôdza (zabudnutá, bez trasy). @return id */
    suspend fun addManual(t: TripEntity): Long = withContext(Dispatchers.IO) {
        val user = session.user() ?: error("Nie ste prihlásený")
        val hours = ((t.endedAt ?: t.startedAt) - t.startedAt) / 3_600_000.0
        dao.insertTrip(t.copy(
            id = 0, uuid = java.util.UUID.randomUUID().toString(), userId = user.id, triggerSource = "manual",
            avgSpeedKmh = if (hours > 0) t.distanceM / 1000.0 / hours else 0.0, finished = true, synced = false, uploaded = false,
        ))
    }

    /**
     * Rozdelí jazdu v čase [splitTs] na dve. Body od tohto času patria novej jazde; vzdialenosť, rýchlosti,
     * kroky (podľa času) a udalosti sa prepočítajú pre obe časti. Na serveri sa pôvodná jazda nahradí dvoma novými.
     * @return id druhej (novej) jazdy
     */
    suspend fun split(t: TripEntity, splitTs: Long, ctx: android.content.Context): Long = withContext(Dispatchers.IO) {
        val pts = dao.points(t.id)
        val a = pts.filter { it.ts < splitTs }
        val b = pts.filter { it.ts >= splitTs }
        require(a.size >= 2 && b.size >= 2) { "Každá časť musí mať aspoň 2 body trasy" }
        val end = t.endedAt ?: pts.last().ts
        fun dist(list: List<sk.cartracker.data.local.PointEntity>): Double {
            var d = 0.0
            for (i in 1 until list.size) {
                val r = FloatArray(1)
                android.location.Location.distanceBetween(list[i - 1].lat, list[i - 1].lng, list[i].lat, list[i].lng, r)
                if (r[0] > 3f) d += r[0]   // ignoruj GPS „tancovanie“ na mieste
            }
            return d
        }
        val dA = dist(a); val dB = dist(b)
        val total = (dA + dB).coerceAtLeast(1.0)
        // celková vzdialenosť jazdy (napr. podľa krokov či opravy tachometra) sa rozdelí v pomere GPS úsekov
        val distA = t.distanceM * dA / total; val distB = t.distanceM * dB / total
        val endA = a.last().ts; val startB = b.first().ts
        val fracA = ((endA - t.startedAt).toDouble() / (end - t.startedAt).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        fun avg(dm: Double, from: Long, to: Long) = ((to - from) / 3_600_000.0).let { h -> if (h > 0) dm / 1000.0 / h else 0.0 }
        val addrA = runCatching { Geo.address(ctx, a.last().lat, a.last().lng) }.getOrNull()
        val addrB = runCatching { Geo.address(ctx, b.first().lat, b.first().lng) }.getOrNull()
        val oldUuid = t.uuid
        val newId = db.withTransaction {
            val second = t.copy(
                id = 0, uuid = java.util.UUID.randomUUID().toString(), startedAt = startB, endedAt = end,
                distanceM = distB, maxSpeedKmh = b.mapNotNull { it.speedKmh }.maxOrNull()?.toDouble() ?: 0.0, avgSpeedKmh = avg(distB, startB, end),
                startLat = b.first().lat, startLng = b.first().lng, startAddress = addrB, startPlace = null,
                steps = (t.steps * (1 - fracA)).toInt(),
                hardBrakes = b.count { it.event == "brake" }, hardAccels = b.count { it.event == "accel" },
                speedingSec = (t.speedingSec * (1 - fracA)).toInt(), synced = false, uploaded = false,
            )
            val id = dao.insertTrip(second)
            dao.movePointsFrom(t.id, id, splitTs)
            dao.updateTrip(t.copy(
                uuid = java.util.UUID.randomUUID().toString(), endedAt = endA, distanceM = distA,
                maxSpeedKmh = a.mapNotNull { it.speedKmh }.maxOrNull()?.toDouble() ?: 0.0, avgSpeedKmh = avg(distA, t.startedAt, endA),
                endLat = a.last().lat, endLng = a.last().lng, endAddress = addrA, endPlace = null,
                steps = (t.steps * fracA).toInt(),
                hardBrakes = a.count { it.event == "brake" }, hardAccels = a.count { it.event == "accel" },
                speedingSec = (t.speedingSec * fracA).toInt(), synced = false, uploaded = false,
            ))
            dao.markPointsUnsynced(t.id)
            id
        }
        if (t.uploaded) deleteRemote(listOf(oldUuid))
        newId
    }

    /**
     * Zlúči viac jázd (rovnakého typu) do jednej – do najstaršej z nich.
     * Vzdialenosť, kroky a udalosti sa sčítajú, čas je od začiatku prvej po koniec poslednej,
     * skóre je vážený priemer podľa km. Ak je niektorá služobná, výsledok je služobný.
     * @return id zlúčenej jazdy
     */
    suspend fun merge(list: List<TripEntity>): Long = withContext(Dispatchers.IO) {
        require(list.size >= 2) { "Vyberte aspoň dve jazdy" }
        require(list.map { it.type }.distinct().size == 1) { "Zlúčiť možno iba jazdy rovnakého typu" }
        val sorted = list.sortedBy { it.startedAt }
        val target = sorted.first()
        val others = sorted.drop(1)
        val last = sorted.maxBy { it.endedAt ?: it.startedAt }

        db.withTransaction {
            dao.movePoints(others.map { it.id }, target.id)
            val dist = sorted.sumOf { it.distanceM }
            val end = sorted.maxOf { it.endedAt ?: it.startedAt }
            val hours = (end - target.startedAt) / 3_600_000.0
            val scored = sorted.filter { it.score != null }
            val score = if (scored.isEmpty()) null
                else (scored.sumOf { it.score!! * it.distanceM.coerceAtLeast(1.0) } / scored.sumOf { it.distanceM.coerceAtLeast(1.0) }).toInt()
            dao.updateTrip(
                target.copy(
                    endedAt = end, distanceM = dist,
                    maxSpeedKmh = sorted.maxOf { it.maxSpeedKmh },
                    avgSpeedKmh = if (hours > 0) dist / 1000.0 / hours else 0.0,
                    endLat = last.endLat, endLng = last.endLng, endAddress = last.endAddress ?: target.endAddress,
                    endPlace = last.endPlace,
                    steps = sorted.sumOf { it.steps },
                    hardBrakes = sorted.sumOf { it.hardBrakes }, hardAccels = sorted.sumOf { it.hardAccels },
                    speedingSec = sorted.sumOf { it.speedingSec }, score = score,
                    category = if (sorted.any { it.category == CATEGORY_BUSINESS }) CATEGORY_BUSINESS else target.category,
                    note = sorted.map { it.note.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("; ").take(500),
                    finished = true, synced = false,
                )
            )
            others.forEach { dao.deleteTrip(it.id) }
        }
        val uploaded = others.filter { it.uploaded }.map { it.uuid }
        if (uploaded.isNotEmpty()) deleteRemote(uploaded)
        target.id
    }

    /**
     * Znova zistí adresy začiatku a cieľa jázd za posledných [days] dní – z najpresnejších bodov trasy
     * a s obcou z OpenStreetMap (oprava napr. „Komárno“ namiesto „Imeľ“). @return počet zmenených jázd
     */
    suspend fun refreshAddresses(ctx: android.content.Context, days: Int, progress: suspend (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val user = session.user() ?: return@withContext 0
        val now = System.currentTimeMillis()
        val list = dao.tripsBetween(user.id, now - days * 86_400_000L, now + 86_400_000L)
        var changed = 0
        list.forEachIndexed { i, t ->
            progress(i, list.size)
            val pts = dao.points(t.id)
            val a = Geo.bestStart(pts); val b = Geo.bestEnd(pts)
            val sLat = a?.lat ?: t.startLat; val sLng = a?.lng ?: t.startLng
            val eLat = b?.lat ?: t.endLat; val eLng = b?.lng ?: t.endLng
            val sa = if (sLat != null && sLng != null) Geo.address(ctx, sLat, sLng) else null
            val ea = if (eLat != null && eLng != null) Geo.address(ctx, eLat, eLng) else null
            val n = t.copy(startLat = sLat, startLng = sLng, endLat = eLat, endLng = eLng,
                startAddress = sa ?: t.startAddress, endAddress = ea ?: t.endAddress)
            if (n != t) { dao.updateTrip(n.copy(synced = false)); changed++ }
        }
        progress(list.size, list.size)
        changed
    }

    /** Zmena kategórie / poznámky – jazda sa znova odošle na server. */
    suspend fun updateMeta(trip: TripEntity, category: String, note: String) {
        dao.updateTrip(trip.copy(category = category, note = note.trim(), synced = false))
    }

    suspend fun tripsBetween(from: Long, to: Long): List<TripEntity> =
        session.user()?.let { dao.tripsBetween(it.id, from, to) } ?: emptyList()

    /** Práve zaznamenávaná jazda autom (ak beží), inak posledná dokončená. */
    suspend fun currentOrLastCarTrip(includeRunning: Boolean): TripEntity? {
        val user = session.user() ?: return null
        if (includeRunning) dao.unfinishedTrip(user.id)?.takeIf { !it.isWalk }?.let { return it }
        return dao.previousTrip(user.id, sk.cartracker.data.local.TYPE_CAR, Long.MAX_VALUE)
    }

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
        // nedokončené mazania zo servera
        val pending = synchronized(pendingDeletes) { pendingDeletes.getStringSet("uuids", emptySet())!!.toList() }
        if (pending.isNotEmpty()) deleteRemote(pending)
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
        type, steps, vehicleUuid, startPlace, endPlace,
    )

    private fun VehicleEntity.toDto() = VehicleDto(
        uuid, name, plate, fuelType, consumptionX10 / 10.0, fuelPriceCents / 100.0,
        odometerKm, odometerDate, btName, archived, updatedAt,
        limitUrban, limitRural, limitHighway, ratePerKm, btAddress,
    )
}

/** Vozidlá používateľa a aktívne vozidlo. */
class VehicleRepository(
    private val db: AppDatabase,
    private val session: SessionStore,
    private val settings: SettingsRepository,
    private val api: sk.cartracker.data.remote.ApiProvider,
    private val ctx: Context,
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

    // ---------------- zdieľané vozidlá ----------------

    /** Stiahne zo servera vozidlá, ktoré mi zdieľajú iní používatelia, a odstráni tie, ktoré už zdieľané nie sú. */
    suspend fun pullShared() = withContext(Dispatchers.IO) {
        val user = session.user() ?: return@withContext
        val remote = api.get().vehicles().vehicles.filter { !it.isOwner }
        val local = dao.allVehicles(user.id).filter { it.ownerName != null }.associateBy { it.uuid }
        for (r in remote) {
            val e = VehicleEntity(
                id = local[r.uuid]?.id ?: 0, uuid = r.uuid, userId = user.id, name = r.name ?: "Vozidlo",
                plate = r.plate ?: "", fuelType = r.fuelType ?: "petrol",
                consumptionX10 = (r.consumption * 10).toInt(), fuelPriceCents = (r.fuelPrice * 100).toInt(),
                odometerKm = r.odometerKm, odometerDate = r.odometerDate,
                btAddress = local[r.uuid]?.btAddress ?: "", btName = r.btName ?: "",
                archived = r.archived, synced = true, updatedAt = r.updatedAt,
                limitUrban = r.limitUrban, limitRural = r.limitRural, limitHighway = r.limitHighway,
                ratePerKmMilli = (r.ratePerKm * 1000).toInt(), ownerName = r.ownerName ?: "?",
            )
            if (e.id == 0L) dao.insertVehicle(e) else dao.updateVehicle(e)
        }
        val keep = remote.map { it.uuid }.toSet()
        local.values.filter { it.uuid !in keep }.forEach { dao.deleteVehicle(it.id) }
    }

    suspend fun shares(uuid: String) = api.get().shares(uuid).shares
    suspend fun share(uuid: String, email: String) = api.get().share(uuid, sk.cartracker.data.remote.ShareRequest(email.trim())).share
    suspend fun unshare(uuid: String, userId: Int) { api.get().unshare(uuid, userId) }

    /** Opustiť vozidlo, ktoré mi niekto zdieľal. */
    suspend fun leaveShared(v: VehicleEntity) {
        val me = session.user() ?: return
        api.get().unshare(v.uuid, me.id)
        dao.deleteVehicle(v.id)
        if (settings.current().activeVehicleUuid == v.uuid) setActive(all().firstOrNull { !it.archived }?.uuid ?: "")
    }

    /** Spoločná kniha jázd – jazdy všetkých vodičov vozidla zo servera. */
    suspend fun sharedTrips(uuid: String, from: Long, to: Long) = api.get().vehicleTrips(uuid, from, to).trips

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
        if (dao.allVehicles(user.id).any { it.ownerName == null }) return
        if (sk.cartracker.data.ServerRestore.isPending(ctx)) return   // vozidlá sa ešte sťahujú zo servera
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
