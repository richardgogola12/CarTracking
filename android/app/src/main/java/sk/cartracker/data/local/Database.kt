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
)

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

@Database(entities = [TripEntity::class, PointEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "car_tracking.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
