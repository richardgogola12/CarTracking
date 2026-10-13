package sk.cartracker.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val CATEGORY_PRIVATE = "private"
const val CATEGORY_BUSINESS = "business"
const val TYPE_CAR = "car"
const val TYPE_WALK = "walk"

/** Vozidlo používateľa. Spotreba a cena paliva sú pri každom vozidle zvlášť (benzín vs. nafta…). */
@Entity(tableName = "vehicles", indices = [Index("uuid", unique = true), Index("userId")])
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val userId: Int,
    val name: String,
    val plate: String = "",
    val fuelType: String = "petrol",     // petrol | diesel | lpg | hybrid | electric
    val consumptionX10: Int = 65,        // l/100 km (pri elektromobile kWh/100 km) × 10
    val fuelPriceCents: Int = 160,       // €/l (€/kWh) × 100
    val odometerKm: Int = 0,
    val odometerDate: Long = 0,
    val btAddress: String = "",
    val btName: String = "",
    val archived: Boolean = false,
    val synced: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    // v4 – maximálna rýchlosť podľa typu cesty (napr. dodávka s vozíkom má nižšie)
    val limitUrban: Int = 50,
    val limitRural: Int = 90,
    val limitHighway: Int = 130,
    // v6 – sadzba základnej náhrady €/km × 1000 (cestovné náhrady) a zdieľanie
    val ratePerKmMilli: Int = 0,
    /** Meno vlastníka, ak je vozidlo zdieľané od iného používateľa (null = moje). */
    val ownerName: String? = null,
) {
    val isShared: Boolean get() = ownerName != null
    val ratePerKm: Double get() = ratePerKmMilli / 1000.0
    val label: String get() = if (plate.isBlank()) name else "$name · $plate"
    fun liters(distanceM: Double) = distanceM / 1000.0 / 100.0 * consumptionX10 / 10.0
    fun cost(distanceM: Double) = liters(distanceM) * fuelPriceCents / 100.0
    val unit: String get() = if (fuelType == "electric") "kWh" else "l"
}

val FUEL_TYPES = listOf("petrol" to "Benzín", "diesel" to "Nafta", "lpg" to "LPG", "hybrid" to "Hybrid", "electric" to "Elektro")

@Entity(tableName = "trips", indices = [Index("uuid", unique = true), Index("userId")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val userId: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val triggerSource: String = "auto",
    val finished: Boolean = false,
    val synced: Boolean = false,     // všetko je na serveri
    val uploaded: Boolean = false,   // server o jazde vie (aspoň raz odoslaná)
    // v2 – kniha jázd a štýl jazdy
    val category: String = CATEGORY_PRIVATE,
    val note: String = "",
    val startAddress: String? = null,
    val endAddress: String? = null,
    val score: Int? = null,
    val hardBrakes: Int = 0,
    val hardAccels: Int = 0,
    val speedingSec: Int = 0,
    // v3 – vozidlá a chôdza
    val type: String = TYPE_CAR,
    val steps: Int = 0,
    val vehicleUuid: String? = null,
    // v5 – pomenované miesta (Domov, Práca…)
    val startPlace: String? = null,
    val endPlace: String? = null,
) {
    val isWalk: Boolean get() = type == TYPE_WALK
}

// ---------------- v5: miesta, tankovanie, pripomienky ----------------

const val PLACE_HOME = "home"
const val PLACE_WORK = "work"
const val PLACE_CLIENT = "client"
const val PLACE_OTHER = "other"
val PLACE_KINDS = listOf(PLACE_HOME to "Domov", PLACE_WORK to "Práca", PLACE_CLIENT to "Klient", PLACE_OTHER to "Iné")

/** Pomenované miesto. [category]: "" = nemeniť typ jazdy, inak private / business. */
@Entity(tableName = "places", indices = [Index("uuid", unique = true), Index("userId")])
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val userId: Int,
    val name: String,
    val kind: String = PLACE_OTHER,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 150,
    val category: String = "",
    val deleted: Boolean = false,
    val synced: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "refuels", indices = [Index("uuid", unique = true), Index("userId"), Index("vehicleUuid")])
data class RefuelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val userId: Int,
    val vehicleUuid: String,
    val ts: Long,
    val liters: Double,          // pri elektromobile kWh
    val totalPrice: Double,
    val odometerKm: Int,
    val fullTank: Boolean = true,
    val note: String = "",
    val deleted: Boolean = false,
    val synced: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val pricePerUnit: Double get() = if (liters > 0) totalPrice / liters else 0.0
}

val REMINDER_KINDS = listOf(
    "stk" to "STK", "ek" to "Emisná kontrola", "oil" to "Výmena oleja", "vignette" to "Diaľničná známka",
    "insurance" to "PZP / havarijné poistenie", "tires" to "Prezutie pneumatík", "service" to "Servisná prehliadka", "custom" to "Iné",
)

@Entity(tableName = "reminders", indices = [Index("uuid", unique = true), Index("userId")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val userId: Int,
    val vehicleUuid: String? = null,
    val kind: String = "custom",
    val title: String,
    val dueDate: Long? = null,
    val dueKm: Int? = null,
    val notifyDays: Int = 14,
    val notifyKm: Int = 500,
    val repeatMonths: Int = 0,
    val repeatKm: Int = 0,
    val done: Boolean = false,
    val lastNotifiedAt: Long = 0,
    val note: String = "",
    val deleted: Boolean = false,
    val synced: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ExtrasDao {
    @Insert suspend fun insertPlace(p: PlaceEntity): Long
    @Update suspend fun updatePlace(p: PlaceEntity)
    @Query("SELECT * FROM places WHERE userId = :userId AND deleted = 0 ORDER BY name") fun placesFlow(userId: Int): Flow<List<PlaceEntity>>
    @Query("SELECT * FROM places WHERE userId = :userId AND deleted = 0") suspend fun places(userId: Int): List<PlaceEntity>
    @Query("SELECT * FROM places WHERE userId = :userId") suspend fun allPlaces(userId: Int): List<PlaceEntity>
    @Query("SELECT * FROM places WHERE uuid = :uuid") suspend fun place(uuid: String): PlaceEntity?

    @Insert suspend fun insertRefuel(r: RefuelEntity): Long
    @Update suspend fun updateRefuel(r: RefuelEntity)
    @Query("SELECT * FROM refuels WHERE userId = :userId AND vehicleUuid = :vehicle AND deleted = 0 ORDER BY ts DESC")
    fun refuelsFlow(userId: Int, vehicle: String): Flow<List<RefuelEntity>>
    @Query("SELECT * FROM refuels WHERE userId = :userId AND deleted = 0 ORDER BY ts DESC") fun allRefuelsFlow(userId: Int): Flow<List<RefuelEntity>>
    @Query("SELECT * FROM refuels WHERE userId = :userId") suspend fun allRefuels(userId: Int): List<RefuelEntity>
    @Query("SELECT * FROM refuels WHERE uuid = :uuid") suspend fun refuel(uuid: String): RefuelEntity?

    @Insert suspend fun insertReminder(r: ReminderEntity): Long
    @Update suspend fun updateReminder(r: ReminderEntity)
    @Query("SELECT * FROM reminders WHERE userId = :userId AND deleted = 0 ORDER BY done, COALESCE(dueDate, 9223372036854775807)")
    fun remindersFlow(userId: Int): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE userId = :userId AND deleted = 0 AND done = 0") suspend fun activeReminders(userId: Int): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE userId = :userId") suspend fun allReminders(userId: Int): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE uuid = :uuid") suspend fun reminder(uuid: String): ReminderEntity?
}

/** Udalosti štýlu jazdy uložené pri bode trasy. */
object DriveEvent {
    const val BRAKE = "brake"
    const val ACCEL = "accel"
    const val SPEEDING = "speeding"
}

@Entity(
    tableName = "points",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("synced")]
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val ts: Long,
    val lat: Double,
    val lng: Double,
    val speedKmh: Float?,
    val accuracyM: Float?,
    val bearing: Float?,
    val altitudeM: Double?,
    val synced: Boolean = false,
    val event: String? = null,
)

@Dao
interface TripDao {
    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @Update suspend fun updateTrip(trip: TripEntity)
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)
    @Query("DELETE FROM trips WHERE userId = :userId") suspend fun deleteAllForUser(userId: Int)

    @Query("SELECT * FROM trips WHERE id = :id") suspend fun getTrip(id: Long): TripEntity?
    @Query("SELECT * FROM trips WHERE id = :id") fun tripFlow(id: Long): Flow<TripEntity?>
    @Query("SELECT * FROM trips WHERE userId = :userId AND finished = 1 ORDER BY startedAt DESC")
    fun finishedTrips(userId: Int): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE userId = :userId AND finished = 1 AND startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun tripsBetween(userId: Int, from: Long, to: Long): List<TripEntity>
    @Query("SELECT COALESCE(SUM(distanceM), 0) FROM trips WHERE userId = :userId AND finished = 1 AND startedAt >= :from AND startedAt < :to")
    suspend fun distanceBetween(userId: Int, from: Long, to: Long): Double
    @Query("SELECT * FROM trips WHERE userId = :userId AND finished = 0 ORDER BY startedAt DESC LIMIT 1")
    suspend fun unfinishedTrip(userId: Int): TripEntity?
    @Query("SELECT * FROM trips WHERE userId = :userId AND finished = 0")
    suspend fun unfinishedTrips(userId: Int): List<TripEntity>
    @Query("SELECT * FROM trips WHERE userId = :userId AND synced = 0 AND finished = 1")
    suspend fun tripsToSync(userId: Int): List<TripEntity>
    @Query("SELECT COUNT(*) FROM trips WHERE userId = :userId AND synced = 0 AND finished = 1")
    fun pendingSyncCount(userId: Int): Flow<Int>

    @Insert suspend fun insertPoint(p: PointEntity): Long
    @Insert suspend fun insertPoints(p: List<PointEntity>)
    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY ts") suspend fun points(tripId: Long): List<PointEntity>
    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY ts DESC LIMIT 1") suspend fun lastPoint(tripId: Long): PointEntity?
    @Query("SELECT * FROM points WHERE tripId = :tripId AND synced = 0 ORDER BY ts LIMIT :limit")
    suspend fun unsyncedPoints(tripId: Long, limit: Int): List<PointEntity>
    @Query("UPDATE points SET synced = 1 WHERE id IN (:ids)") suspend fun markPointsSynced(ids: List<Long>)
    /** Zlúčenie: body ostatných jázd sa presunú do cieľovej jazdy (a znova sa odošlú na server). */
    @Query("UPDATE points SET tripId = :target, synced = 0 WHERE tripId IN (:sources)")
    suspend fun movePoints(sources: List<Long>, target: Long)
    @Query("""SELECT * FROM trips WHERE userId = :userId AND finished = 1 AND type = :type AND startedAt > :after
              ORDER BY startedAt LIMIT 1""")
    suspend fun nextTrip(userId: Int, type: String, after: Long): TripEntity?
    @Query("""SELECT * FROM trips WHERE userId = :userId AND finished = 1 AND type = :type AND startedAt < :before
              ORDER BY startedAt DESC LIMIT 1""")
    suspend fun previousTrip(userId: Int, type: String, before: Long): TripEntity?

    @Query("""SELECT COALESCE(SUM(distanceM), 0) FROM trips WHERE userId = :userId AND finished = 1 AND type = 'car'
              AND vehicleUuid = :vehicle AND startedAt >= :from AND startedAt < :to""")
    suspend fun vehicleDistanceBetween(userId: Int, vehicle: String, from: Long, to: Long): Double
    @Query("UPDATE trips SET vehicleUuid = :vehicle, synced = 0 WHERE userId = :userId AND type = 'car' AND vehicleUuid IS NULL")
    suspend fun assignVehicleToUnassigned(userId: Int, vehicle: String)

    // ---- záloha ----
    @Query("SELECT * FROM trips WHERE userId = :userId") suspend fun allTrips(userId: Int): List<TripEntity>
    @Query("SELECT * FROM trips WHERE uuid = :uuid") suspend fun tripByUuid(uuid: String): TripEntity?
    @Query("SELECT * FROM trips WHERE userId = :userId AND finished = 1 AND type = 'car'") suspend fun carTrips(userId: Int): List<TripEntity>

    // ---- vozidlá ----
    @Insert suspend fun insertVehicle(v: VehicleEntity): Long
    @Update suspend fun updateVehicle(v: VehicleEntity)
    @Query("SELECT * FROM vehicles WHERE userId = :userId AND archived = 0 ORDER BY name")
    fun vehiclesFlow(userId: Int): Flow<List<VehicleEntity>>
    @Query("SELECT * FROM vehicles WHERE userId = :userId ORDER BY name")
    suspend fun allVehicles(userId: Int): List<VehicleEntity>
    @Query("SELECT * FROM vehicles WHERE uuid = :uuid") suspend fun vehicle(uuid: String): VehicleEntity?
    @Query("SELECT * FROM vehicles WHERE userId = :userId AND archived = 0 AND btAddress = :address COLLATE NOCASE LIMIT 1")
    suspend fun vehicleByBt(userId: Int, address: String): VehicleEntity?
    @Query("SELECT * FROM vehicles WHERE userId = :userId AND synced = 0 AND ownerName IS NULL") suspend fun vehiclesToSync(userId: Int): List<VehicleEntity>
    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun deleteVehicle(id: Long)
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN category TEXT NOT NULL DEFAULT 'private'")
        db.execSQL("ALTER TABLE trips ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE trips ADD COLUMN startAddress TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN endAddress TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN score INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN hardBrakes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN hardAccels INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN speedingSec INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE points ADD COLUMN event TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN type TEXT NOT NULL DEFAULT 'car'")
        db.execSQL("ALTER TABLE trips ADD COLUMN steps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN vehicleUuid TEXT")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS vehicles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, userId INTEGER NOT NULL,
                name TEXT NOT NULL, plate TEXT NOT NULL, fuelType TEXT NOT NULL, consumptionX10 INTEGER NOT NULL,
                fuelPriceCents INTEGER NOT NULL, odometerKm INTEGER NOT NULL, odometerDate INTEGER NOT NULL,
                btAddress TEXT NOT NULL, btName TEXT NOT NULL, archived INTEGER NOT NULL, synced INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_vehicles_uuid ON vehicles (uuid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_vehicles_userId ON vehicles (userId)")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vehicles ADD COLUMN limitUrban INTEGER NOT NULL DEFAULT 50")
        db.execSQL("ALTER TABLE vehicles ADD COLUMN limitRural INTEGER NOT NULL DEFAULT 90")
        db.execSQL("ALTER TABLE vehicles ADD COLUMN limitHighway INTEGER NOT NULL DEFAULT 130")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN startPlace TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN endPlace TEXT")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS places (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, userId INTEGER NOT NULL,
                name TEXT NOT NULL, kind TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, radiusM INTEGER NOT NULL,
                category TEXT NOT NULL, deleted INTEGER NOT NULL, synced INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_places_uuid ON places (uuid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_places_userId ON places (userId)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS refuels (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, userId INTEGER NOT NULL,
                vehicleUuid TEXT NOT NULL, ts INTEGER NOT NULL, liters REAL NOT NULL, totalPrice REAL NOT NULL,
                odometerKm INTEGER NOT NULL, fullTank INTEGER NOT NULL, note TEXT NOT NULL, deleted INTEGER NOT NULL,
                synced INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_refuels_uuid ON refuels (uuid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refuels_userId ON refuels (userId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refuels_vehicleUuid ON refuels (vehicleUuid)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, userId INTEGER NOT NULL,
                vehicleUuid TEXT, kind TEXT NOT NULL, title TEXT NOT NULL, dueDate INTEGER, dueKm INTEGER,
                notifyDays INTEGER NOT NULL, notifyKm INTEGER NOT NULL, repeatMonths INTEGER NOT NULL, repeatKm INTEGER NOT NULL,
                done INTEGER NOT NULL, lastNotifiedAt INTEGER NOT NULL, note TEXT NOT NULL, deleted INTEGER NOT NULL,
                synced INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reminders_uuid ON reminders (uuid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_userId ON reminders (userId)")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vehicles ADD COLUMN ratePerKmMilli INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vehicles ADD COLUMN ownerName TEXT")
    }
}

@Database(
    entities = [TripEntity::class, PointEntity::class, VehicleEntity::class, PlaceEntity::class, RefuelEntity::class, ReminderEntity::class],
    version = 6, exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun extrasDao(): ExtrasDao

    companion object {
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "car_tracking.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
    }
}
