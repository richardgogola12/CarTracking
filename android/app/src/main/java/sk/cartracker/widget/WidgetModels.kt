package sk.cartracker.widget

import sk.cartracker.data.SpeedSample
import sk.cartracker.service.DriveState

/** Údaje pre widgety – oddelené od databázy, aby sa dali vykresliť aj v testoch (náhľady). */
data class RoutePt(val lat: Double, val lng: Double, val kmh: Float)

data class TripSummary(
    val id: Long, val uuid: String, val from: String, val to: String, val startedAt: Long, val endedAt: Long,
    val km: Double, val avgKmh: Double, val maxKmh: Double, val score: Int?, val business: Boolean, val cost: Double,
    val vehicle: String?, val route: List<RoutePt>,
)

data class MainModel(
    val loggedIn: Boolean,
    val state: DriveState,
    // živá jazda / chôdza
    val liveStartedAt: Long = 0, val liveKmh: Int = 0, val liveDistanceM: Double = 0.0, val liveSteps: Int = 0,
    val liveLimit: Int? = null, val liveCost: Double = 0.0,
    val vehicle: String? = null,
    // dnes
    val todayKm: Double = 0.0, val todayTrips: Int = 0, val todaySteps: Int = 0, val todayCost: Double = 0.0, val todayMs: Long = 0,
    // grafy
    val week: List<Pair<String, Double>> = emptyList(), val weekSteps: List<Pair<String, Double>> = emptyList(),
    val speed: List<SpeedSample> = emptyList(), val speedTitle: String = "", val speedMax: Float = 0f, val speedAvg: Double = 0.0,
    val stepGoal: Int = 8000,
    // ďalšie
    val reminder: String? = null, val reminderOverdue: Boolean = false,
    val parkingAddr: String? = null, val parkingSince: Long? = null, val parkingLat: Double? = null, val parkingLng: Double? = null,
    val lastTrip: TripSummary? = null,
)

data class StepsModel(
    val steps: Int, val goal: Int, val km: Double, val streak: Int, val best: Int, val week: List<Pair<String, Double>>,
)

data class ParkingModel(
    val driving: Boolean, val addr: String?, val since: Long?, val lat: Double?, val lng: Double?, val ticketUntil: Long?,
)

data class MonthModel(
    val monthName: String, val km: Double, val trips: Int, val cost: Double, val businessKm: Double, val steps: Int, val walkKm: Double,
    val vsPct: Int?, val days: List<Pair<String, Double>>,
)
