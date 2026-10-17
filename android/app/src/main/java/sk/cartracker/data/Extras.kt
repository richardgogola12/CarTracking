package sk.cartracker.data

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import sk.cartracker.data.local.AppDatabase
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.local.PlaceEntity
import sk.cartracker.data.local.RefuelEntity
import sk.cartracker.data.local.ReminderEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.remote.ApiProvider
import sk.cartracker.data.remote.ItemDto
import sk.cartracker.data.remote.ItemSyncRequest
import sk.cartracker.security.SessionStore
import java.util.Calendar
import java.util.UUID

// =====================================================================
// Miesta (Domov, Práca, Klient…)
// =====================================================================

class PlacesRepository(private val db: AppDatabase, private val session: SessionStore) {
    private val dao = db.extrasDao()
    private val trips = db.tripDao()
    private fun uid() = session.user()?.id

    fun places(): Flow<List<PlaceEntity>> = uid()?.let { dao.placesFlow(it) } ?: emptyFlow()
    suspend fun list(): List<PlaceEntity> = uid()?.let { dao.places(it) } ?: emptyList()

    suspend fun save(p: PlaceEntity): PlaceEntity {
        val u = uid() ?: return p
        val v = p.copy(userId = u, synced = false, updatedAt = System.currentTimeMillis())
        return if (p.id == 0L) v.copy(id = dao.insertPlace(v)) else v.also { dao.updatePlace(it) }
    }

    suspend fun delete(p: PlaceEntity) = save(p.copy(deleted = true))

    fun newPlace(name: String, kind: String, lat: Double, lng: Double) =
        PlaceEntity(uuid = UUID.randomUUID().toString(), userId = uid() ?: 0, name = name, kind = kind, lat = lat, lng = lng)

    /** Najbližšie miesto, v ktorého okruhu bod leží. */
    fun match(places: List<PlaceEntity>, lat: Double?, lng: Double?): PlaceEntity? {
        if (lat == null || lng == null) return null
        val d = FloatArray(1)
        return places.mapNotNull { p ->
            Location.distanceBetween(lat, lng, p.lat, p.lng, d)
            if (d[0] <= p.radiusM) p to d[0] else null
        }.minByOrNull { it.second }?.first
    }

    /**
     * Doplní názvy miest k jazde. Typ jazdy (súkromná/služobná) a účel sa menia len ak ich používateľ
     * nezmenil ručne – t. j. pri novej jazde alebo keď [force].
     */
    fun applyToTrip(t: TripEntity, places: List<PlaceEntity>, s: AppSettings, setCategory: Boolean): TripEntity {
        val a = match(places, t.startLat, t.startLng)
        val b = match(places, t.endLat, t.endLng)
        var out = t.copy(startPlace = a?.name, endPlace = b?.name)
        if (!t.isWalk && setCategory) {
            val cats = listOfNotNull(a?.category, b?.category).filter { it.isNotEmpty() }
            val cat = when {
                CATEGORY_BUSINESS in cats -> CATEGORY_BUSINESS
                CATEGORY_PRIVATE in cats -> CATEGORY_PRIVATE
                else -> null
            }
            if (cat != null) out = out.copy(category = cat)
            if (out.note.isBlank() && (a != null || b != null) && a?.uuid != b?.uuid) {
                out = out.copy(note = "${a?.name ?: t.startAddress?.substringAfterLast(", ") ?: "?"} → ${b?.name ?: t.endAddress?.substringAfterLast(", ") ?: "?"}")
            }
        }
        return out
    }

    /** Prepočíta názvy miest pri všetkých jazdách (napr. po pridaní nového miesta). */
    suspend fun applyToAll(s: AppSettings, setCategory: Boolean): Int = withContext(Dispatchers.IO) {
        val u = uid() ?: return@withContext 0
        val places = dao.places(u)
        var changed = 0
        for (t in trips.allTrips(u).filter { it.finished }) {
            val n = applyToTrip(t, places, s, setCategory)
            if (n != t) { trips.updateTrip(n.copy(synced = false)); changed++ }
        }
        changed
    }
}

// =====================================================================
// Tankovanie a skutočná spotreba
// =====================================================================

data class ConsumptionPoint(val refuel: RefuelEntity, val per100: Double, val km: Int)
data class ConsumptionStats(
    val points: List<ConsumptionPoint>,
    val average: Double?,       // l (kWh) / 100 km z plných nádrží
    val totalUnits: Double,
    val totalPrice: Double,
    val pricePerKm: Double?,
)

class RefuelRepository(private val db: AppDatabase, private val session: SessionStore) {
    private val dao = db.extrasDao()
    private fun uid() = session.user()?.id

    fun refuels(vehicleUuid: String): Flow<List<RefuelEntity>> = uid()?.let { dao.refuelsFlow(it, vehicleUuid) } ?: emptyFlow()
    fun allRefuels(): Flow<List<RefuelEntity>> = uid()?.let { dao.allRefuelsFlow(it) } ?: emptyFlow()

    suspend fun save(r: RefuelEntity): RefuelEntity {
        val u = uid() ?: return r
        val v = r.copy(userId = u, synced = false, updatedAt = System.currentTimeMillis())
        return if (r.id == 0L) v.copy(id = dao.insertRefuel(v)) else v.also { dao.updateRefuel(it) }
    }

    suspend fun delete(r: RefuelEntity) = save(r.copy(deleted = true))

    companion object {
        /**
         * Metóda „plná–plná“: spotreba = všetko natankované od predchádzajúcej plnej nádrže
         * po túto plnú nádrž / prejdené km (podľa tachometra).
         */
        fun stats(refuels: List<RefuelEntity>): ConsumptionStats {
            val sorted = refuels.filter { !it.deleted }.sortedWith(compareBy({ it.odometerKm }, { it.ts }))
            val points = mutableListOf<ConsumptionPoint>()
            var lastFull: RefuelEntity? = null
            var units = 0.0
            for (r in sorted) {
                val lf = lastFull
                if (lf == null) { if (r.fullTank) lastFull = r; continue }
                units += r.liters
                if (r.fullTank) {
                    val km = r.odometerKm - lf.odometerKm
                    if (km > 0) points += ConsumptionPoint(r, units / km * 100.0, km)
                    lastFull = r
                    units = 0.0
                }
            }
            val km = points.sumOf { it.km }
            val avg = if (km > 0) points.sumOf { it.per100 * it.km / 100.0 } / km * 100.0 else null
            val totalKm = if (sorted.size >= 2) sorted.last().odometerKm - sorted.first().odometerKm else 0
            // cena na km: všetko okrem prvého tankovania (to pokrýva km pred prvým záznamom)
            val priceAfterFirst = sorted.drop(1).sumOf { it.totalPrice }
            return ConsumptionStats(
                points = points.sortedByDescending { it.refuel.ts },
                average = avg,
                totalUnits = sorted.sumOf { it.liters },
                totalPrice = sorted.sumOf { it.totalPrice },
                pricePerKm = if (totalKm > 0) priceAfterFirst / totalKm else null,
            )
        }
    }
}

// =====================================================================
// Pripomienky (STK, EK, olej, známka…)
// =====================================================================

enum class ReminderState(val label: String) { OK("V poriadku"), SOON("Blíži sa"), OVERDUE("Po termíne"), DONE("Vybavené") }

data class ReminderStatus(val state: ReminderState, val daysLeft: Long?, val kmLeft: Int?)

class ReminderRepository(
    private val db: AppDatabase,
    private val session: SessionStore,
    private val trips: TripRepository,
    private val vehicles: VehicleRepository,
) {
    private val dao = db.extrasDao()
    private fun uid() = session.user()?.id

    fun reminders(): Flow<List<ReminderEntity>> = uid()?.let { dao.remindersFlow(it) } ?: emptyFlow()
    suspend fun active(): List<ReminderEntity> = uid()?.let { dao.activeReminders(it) } ?: emptyList()

    suspend fun save(r: ReminderEntity): ReminderEntity {
        val u = uid() ?: return r
        val v = r.copy(userId = u, synced = false, updatedAt = System.currentTimeMillis())
        return if (r.id == 0L) v.copy(id = dao.insertReminder(v)) else v.also { dao.updateReminder(it) }
    }

    suspend fun delete(r: ReminderEntity) = save(r.copy(deleted = true))

    /** Vybavené: označí a pri opakovaní vytvorí ďalší termín (od dnes / od aktuálneho tachometra). */
    suspend fun complete(r: ReminderEntity) {
        save(r.copy(done = true))
        if (r.repeatMonths > 0 || r.repeatKm > 0) {
            val nextDate = if (r.repeatMonths > 0) Calendar.getInstance().apply { add(Calendar.MONTH, r.repeatMonths) }.timeInMillis else null
            val odo = odometerNow(r.vehicleUuid)
            val nextKm = if (r.repeatKm > 0) ((odo ?: r.dueKm?.toDouble() ?: 0.0) + r.repeatKm).toInt() else null
            save(r.copy(id = 0, uuid = UUID.randomUUID().toString(), done = false, dueDate = nextDate, dueKm = nextKm, lastNotifiedAt = 0))
        }
    }

    /** Odhad aktuálneho tachometra: z jázd a z posledného tankovania (vyššia hodnota). */
    suspend fun odometerNow(vehicleUuid: String?): Double? {
        val v: VehicleEntity = vehicles.byUuid(vehicleUuid) ?: return null
        val fromTrips = trips.odometerAt(System.currentTimeMillis(), v)
        val u = uid() ?: return fromTrips
        val fromRefuel = dao.allRefuels(u).filter { it.vehicleUuid == v.uuid && !it.deleted }.maxOfOrNull { it.odometerKm }?.toDouble()
        return listOfNotNull(fromTrips, fromRefuel).maxOrNull()
    }

    suspend fun status(r: ReminderEntity, now: Long = System.currentTimeMillis()): ReminderStatus {
        if (r.done) return ReminderStatus(ReminderState.DONE, null, null)
        val days = r.dueDate?.let { Math.floorDiv(it - now, 86_400_000L) }
        val kmLeft = r.dueKm?.let { due -> odometerNow(r.vehicleUuid)?.let { (due - it).toInt() } }
        val state = when {
            (days != null && days < 0) || (kmLeft != null && kmLeft < 0) -> ReminderState.OVERDUE
            (days != null && days <= r.notifyDays) || (kmLeft != null && kmLeft <= r.notifyKm) -> ReminderState.SOON
            else -> ReminderState.OK
        }
        return ReminderStatus(state, days, kmLeft)
    }
}

class ExpenseRepository(private val db: AppDatabase, private val session: SessionStore) {
    private val dao = db.extrasDao()
    private fun uid() = session.user()?.id

    fun expenses(vehicleUuid: String): Flow<List<sk.cartracker.data.local.ExpenseEntity>> = uid()?.let { dao.expensesFlow(it, vehicleUuid) } ?: emptyFlow()
    suspend fun all(): List<sk.cartracker.data.local.ExpenseEntity> = uid()?.let { u -> dao.allExpenses(u).filter { !it.deleted } } ?: emptyList()

    suspend fun save(e: sk.cartracker.data.local.ExpenseEntity): sk.cartracker.data.local.ExpenseEntity {
        val u = uid() ?: return e
        val v = e.copy(userId = u, synced = false, updatedAt = System.currentTimeMillis())
        return if (e.id == 0L) v.copy(id = dao.insertExpense(v)) else v.also { dao.updateExpense(it) }
    }

    suspend fun delete(e: sk.cartracker.data.local.ExpenseEntity) = save(e.copy(deleted = true))
}

// =====================================================================
// Synchronizácia miest, tankovaní a pripomienok na server (generická tabuľka)
// =====================================================================

class ItemsSync(private val db: AppDatabase, private val api: ApiProvider, private val session: SessionStore) {
    private val dao = db.extrasDao()

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        val u = session.user()?.id ?: return@withContext
        val places = dao.allPlaces(u).filter { !it.synced }
        val refuels = dao.allRefuels(u).filter { !it.synced }
        val reminders = dao.allReminders(u).filter { !it.synced }
        val expenses = dao.allExpenses(u).filter { !it.synced }
        val items = expenses.map { ItemDto("expense", it.uuid, it.updatedAt, it.deleted, it) } + places.map { ItemDto("place", it.uuid, it.updatedAt, it.deleted, it) } +
            refuels.map { ItemDto("refuel", it.uuid, it.updatedAt, it.deleted, it) } +
            reminders.map { ItemDto("reminder", it.uuid, it.updatedAt, it.deleted, it) }
        if (items.isEmpty()) return@withContext
        val ok = items.chunked(200).flatMap { api.get().syncItems(ItemSyncRequest(it)).synced }.toSet()
        places.filter { it.uuid in ok }.forEach { dao.updatePlace(it.copy(synced = true)) }
        refuels.filter { it.uuid in ok }.forEach { dao.updateRefuel(it.copy(synced = true)) }
        reminders.filter { it.uuid in ok }.forEach { dao.updateReminder(it.copy(synced = true)) }
        expenses.filter { it.uuid in ok }.forEach { dao.updateExpense(it.copy(synced = true)) }
    }
}
