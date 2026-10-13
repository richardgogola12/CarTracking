package sk.cartracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.DrivingScore
import sk.cartracker.data.Geo
import sk.cartracker.data.RoadInfo
import sk.cartracker.data.RoadInfoProvider
import sk.cartracker.data.RoadZone
import sk.cartracker.data.RuntimeState
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.data.speedLimitFor
import sk.cartracker.data.cost
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TYPE_CAR
import sk.cartracker.data.local.TYPE_WALK
import sk.cartracker.data.local.TripEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import java.util.Locale
import java.util.UUID
import kotlin.math.max

enum class DriveState { IDLE, CANDIDATE, DRIVING, WALKING }

data class LiveTrip(
    val startedAt: Long,
    val distanceM: Double,
    val speedKmh: Double,
    val maxSpeedKmh: Double,
    val points: Int,
    val lat: Double?,
    val lng: Double?,
    val source: String,
    val manual: Boolean,
    val type: String = TYPE_CAR,
    val steps: Int = 0,
    val vehicleName: String? = null,
    val limitKmh: Int? = null,
    val zone: RoadZone? = null,
    val zoneManual: Boolean = false,
    val roadName: String? = null,
)

/** Stav služby pre UI (rovnaký proces). */
object TrackingState {
    val state = MutableStateFlow(DriveState.IDLE)
    val live = MutableStateFlow<LiveTrip?>(null)
    /** Ručne zvolený typ cesty počas jazdy (null = automaticky z mapy). */
    val zoneOverride = MutableStateFlow<RoadZone?>(null)
}

/**
 * Služba v popredí, ktorá beží nezávisle od hlavného okna.
 *
 * Auto:
 *   IDLE ──(nastúpil do vozidla / BT auta)──► CANDIDATE ──(3× rýchlosť ≥ limit)──► DRIVING
 *   CANDIDATE ──(chôdza/bicykel alebo timeout)──► IDLE (nič sa neuloží)
 *   DRIVING ──(státie X min alebo vystúpenie + státie)──► IDLE / WALKING
 * Chôdza:
 *   IDLE ──(systém hlási chôdzu, v povolenom čase)──► WALKING ──(X min bez krokov)──► IDLE
 *   WALKING ──(nástup do vozidla / rýchlosť auta)──► CANDIDATE
 */
class TrackingService : LifecycleService() {

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_CANDIDATE = "sk.cartracker.action.CANDIDATE"
        const val ACTION_MANUAL_START = "sk.cartracker.action.MANUAL_START"
        const val ACTION_MANUAL_WALK = "sk.cartracker.action.MANUAL_WALK"
        const val ACTION_EXIT_SIGNAL = "sk.cartracker.action.EXIT_SIGNAL"
        const val ACTION_WALK_SIGNAL = "sk.cartracker.action.WALK_SIGNAL"
        const val ACTION_BIKE_SIGNAL = "sk.cartracker.action.BIKE_SIGNAL"
        const val ACTION_STOP = "sk.cartracker.action.STOP"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_BLUETOOTH = "bluetooth"
        const val SOURCE_MANUAL = "manual"

        private const val MOVING_KMH = 8.0          // nad touto rýchlosťou sa auto "hýbe"
        private const val WALK_MOVING_KMH = 2.5     // chôdza bez krokomera
        private const val EXIT_CONFIRM_MS = 60_000L  // po vystúpení z auta počkaj 1 min
        private const val TICK_MS = 15_000L

        fun startCandidate(ctx: Context, source: String) =
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_CANDIDATE).putExtra(EXTRA_SOURCE, source))

        fun startManual(ctx: Context) =
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_MANUAL_START))

        fun startManualWalk(ctx: Context) =
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_MANUAL_WALK))

        /** Systém hlási chôdzu – môže spustiť sledovanie chôdze aj ukončiť jazdu autom. */
        fun walkSignal(ctx: Context) =
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_WALK_SIGNAL))

        /** Signál pre bežiacu službu (ak nebeží, nič nerobí). */
        fun signal(ctx: Context, action: String) {
            if (TrackingState.state.value == DriveState.IDLE) return
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(action))
        }

        private fun startFg(ctx: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                // Android 12+: štart z pozadia môže byť zakázaný, ak appka nemá výnimku z optimalizácie batérie
                Log.e(TAG, "Službu sa nepodarilo spustiť", e)
            }
        }
    }

    private val mutex = Mutex()
    private lateinit var fused: FusedLocationProviderClient
    private val dao by lazy { container.db.tripDao() }
    private var settings = AppSettings()
    private var userId = -1

    // CANDIDATE
    private var source = SOURCE_AUTO
    private var manual = false
    private var candidateSince = 0L
    private var fastFixes = 0
    private val candidateBuffer = ArrayList<Location>()

    // Jazda / chôdza
    private var trip: TripEntity? = null
    private var distance = 0.0            // GPS vzdialenosť
    private var maxSpeed = 0.0
    private var lastSpeed = 0.0
    private var pointCount = 0
    private var prevFix: Location? = null
    private var lastGood: Location? = null
    private var lastMovingAt = 0L
    private var exitSignalAt: Long? = null
    private var pendingWalk = false       // po skončení jazdy pokračovať chôdzou
    private var vehicleName: String? = null
    private var vehicle: VehicleEntity? = null

    // Typ cesty (obec / mimo obce / diaľnica) a limit
    private var roadInfo: RoadInfo? = null
    private var roadLookupAt = 0L
    private var roadLookupLoc: Location? = null
    private var roadLookupRunning = false

    // Štýl jazdy
    private var hardBrakes = 0
    private var hardAccels = 0
    private var speedingMs = 0L
    private var speedFix: Location? = null
    private var lastEventAt = 0L
    private var wasSpeeding = false
    private var lastSpeedAlertAt = 0L

    // Kroky
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var stepListener: SensorEventListener? = null
    private var stepBase = -1f
    private var stepsOffset = 0
    private var steps = 0
    private var stepSensorActive = false

    private var locationActive = false
    private var lastNotifUpdate = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            lifecycleScope.launch { mutex.withLock { evaluate() } }
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) lifecycleScope.launch { mutex.withLock { onLocation(loc) } }
        }
    }

    private val state get() = TrackingState.state.value
    private val isWalk get() = trip?.type == TYPE_WALK

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        lifecycleScope.launch { container.settings.flow.collect { settings = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Po startForegroundService() musíme do 5 s zavolať startForeground()
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        val src = intent?.getStringExtra(EXTRA_SOURCE) ?: SOURCE_AUTO
        lifecycleScope.launch { mutex.withLock { handle(action, src) } }
        return START_STICKY // systém službu po zabití znova spustí → recover()
    }

    private suspend fun handle(action: String?, src: String) {
        settings = container.settings.current()
        userId = container.session.user()?.id ?: -1
        if (userId < 0 || !hasLocationPermission()) { shutdown(); return }
        val now = System.currentTimeMillis()

        when (action) {
            ACTION_CANDIDATE -> when (state) {
                DriveState.IDLE -> beginCandidate(src)
                DriveState.WALKING -> finishTrip(now) { beginCandidate(src) }   // z chôdze nastúpil do auta
                else -> { exitSignalAt = null; pendingWalk = false }             // znova v aute
            }
            ACTION_MANUAL_START -> when (state) {
                DriveState.IDLE -> manualCar()
                DriveState.CANDIDATE -> { manual = true; source = SOURCE_MANUAL; confirmDriving() }
                DriveState.DRIVING -> manual = true
                DriveState.WALKING -> finishTrip(now) { manualCar() }
            }
            ACTION_MANUAL_WALK -> when (state) {
                DriveState.IDLE -> beginWalk(manualWalk = true)
                DriveState.CANDIDATE -> discard { beginWalk(manualWalk = true) }
                DriveState.DRIVING -> finishTrip(now) { beginWalk(manualWalk = true) }
                DriveState.WALKING -> manual = true
            }
            ACTION_WALK_SIGNAL -> when (state) {
                DriveState.IDLE -> if (settings.walkActiveNow()) beginWalk(false) else shutdown()
                DriveState.CANDIDATE -> discard { if (settings.walkActiveNow()) beginWalk(false) else shutdown() }
                DriveState.DRIVING -> if (!manual) {
                    if (exitSignalAt == null) exitSignalAt = now
                    pendingWalk = settings.walkActiveNow()
                }
                DriveState.WALKING -> Unit
            }
            ACTION_EXIT_SIGNAL -> when (state) {
                DriveState.CANDIDATE -> discard()           // vystúpil / odpojil BT → nebola to jazda
                DriveState.DRIVING -> if (!manual && exitSignalAt == null) exitSignalAt = now
                DriveState.WALKING -> Unit
                DriveState.IDLE -> shutdown()
            }
            ACTION_BIKE_SIGNAL -> when (state) {
                DriveState.CANDIDATE -> discard()
                DriveState.DRIVING -> if (!manual && exitSignalAt == null) exitSignalAt = now
                DriveState.WALKING -> if (!manual) finishTrip(lastMovingAt)
                DriveState.IDLE -> shutdown()
            }
            ACTION_STOP -> when (state) {
                DriveState.CANDIDATE -> discard()
                DriveState.DRIVING, DriveState.WALKING -> finishTrip(now)
                DriveState.IDLE -> shutdown()
            }
            null -> recover()
        }
    }

    // ---------------- stavy ----------------

    private suspend fun manualCar() {
        manual = true
        source = SOURCE_MANUAL
        startLocation()
        startTrip(System.currentTimeMillis(), TYPE_CAR)
        Notifications.driveEvent(this, started = true, s = settings)
    }

    private suspend fun beginCandidate(src: String) {
        closeStaleTrips()
        source = src
        manual = false
        candidateSince = System.currentTimeMillis()
        fastFixes = 0
        candidateBuffer.clear()
        prevFix = null
        TrackingState.state.value = DriveState.CANDIDATE
        startLocation()
        updateNotification(force = true)
        Log.i(TAG, "Kandidát na jazdu ($src)")
    }

    private suspend fun confirmDriving() {
        val buffered = candidateBuffer.toList()
        candidateBuffer.clear()
        startTrip(buffered.firstOrNull()?.time ?: System.currentTimeMillis(), TYPE_CAR)
        var prev: Location? = null
        for (loc in buffered) { recordPoint(loc, speedOf(loc, prev)); prev = loc }
        Notifications.driveEvent(this, started = true, s = settings, summary = vehicleName?.let { "Vozidlo: $it" })
        Log.i(TAG, "Jazda potvrdená")
    }

    private suspend fun beginWalk(manualWalk: Boolean) {
        closeStaleTrips()
        manual = manualWalk
        source = if (manualWalk) SOURCE_MANUAL else SOURCE_AUTO
        stopLocation()
        startTrip(System.currentTimeMillis(), TYPE_WALK)
        startSteps(0)
        startLocation()
        Notifications.walkEvent(this, started = true, s = settings)
        Log.i(TAG, "Chôdza začala")
    }

    private suspend fun startTrip(startedAt: Long, type: String) {
        val vehicle = if (type == TYPE_CAR) container.vehicles.active() else null
        this.vehicle = vehicle
        vehicleName = vehicle?.label
        roadInfo = null; roadLookupLoc = null; roadLookupAt = 0L
        TrackingState.zoneOverride.value = null
        val t = TripEntity(
            uuid = UUID.randomUUID().toString(), userId = userId, startedAt = startedAt,
            triggerSource = source, type = type, vehicleUuid = vehicle?.uuid,
            category = if (type == TYPE_CAR) settings.defaultCategory else CATEGORY_PRIVATE,
        )
        trip = t.copy(id = dao.insertTrip(t))
        distance = 0.0; maxSpeed = 0.0; pointCount = 0
        lastGood = null
        hardBrakes = 0; hardAccels = 0; speedingMs = 0L; speedFix = null; wasSpeeding = false
        steps = 0; stepsOffset = 0
        lastMovingAt = System.currentTimeMillis()
        exitSignalAt = null
        pendingWalk = false
        TrackingState.state.value = if (type == TYPE_WALK) DriveState.WALKING else DriveState.DRIVING
        publishLive(null)
        updateNotification(force = true)
    }

    private suspend fun recover() {
        if (state != DriveState.IDLE) return
        val t = dao.unfinishedTrip(userId)
        val last = t?.let { dao.lastPoint(it.id) }
        if (t == null || System.currentTimeMillis() - (last?.ts ?: t.startedAt) > 30 * 60_000L) {
            closeStaleTrips(); shutdown(); return
        }
        trip = t
        source = t.triggerSource
        manual = t.triggerSource == SOURCE_MANUAL
        distance = if (t.isWalk && t.steps > 0) 0.0 else t.distanceM
        maxSpeed = t.maxSpeedKmh
        hardBrakes = t.hardBrakes; hardAccels = t.hardAccels; speedingMs = t.speedingSec * 1000L
        speedFix = null; wasSpeeding = false
        vehicle = container.vehicles.byUuid(t.vehicleUuid)
        vehicleName = vehicle?.label
        pointCount = dao.points(t.id).size
        lastGood = last?.let { Location("db").apply { latitude = it.lat; longitude = it.lng; time = it.ts } }
        lastMovingAt = System.currentTimeMillis()
        exitSignalAt = null
        TrackingState.state.value = if (t.isWalk) DriveState.WALKING else DriveState.DRIVING
        steps = t.steps
        if (t.isWalk) startSteps(t.steps)
        startLocation()
        publishLive(null)
        updateNotification(force = true)
        Log.i(TAG, "Záznam obnovený po reštarte služby")
    }

    private suspend fun onLocation(loc: Location) {
        if (state == DriveState.IDLE) return
        val speed = speedOf(loc, prevFix)
        prevFix = loc
        lastSpeed = speed
        when (state) {
            DriveState.CANDIDATE -> {
                candidateBuffer.add(loc)
                if (candidateBuffer.size > 600) candidateBuffer.removeAt(0)
                if (loc.accuracy <= 40f && speed >= settings.minDriveSpeedKmh) fastFixes++
                val btOk = !settings.bluetoothRequired || RuntimeState.isCarBtConnected(this)
                if (fastFixes >= 3 && btOk) confirmDriving()
            }
            DriveState.DRIVING -> recordPoint(loc, speed)
            DriveState.WALKING -> {
                // Rýchlosť auta počas „chôdze“ → nastúpil do vozidla
                if (loc.accuracy <= 30f && speed >= settings.minDriveSpeedKmh) fastFixes++ else if (speed < 10) fastFixes = 0
                if (fastFixes >= 3 && settings.autoDetection && !manual) {
                    finishTrip(lastMovingAt) { beginCandidate(SOURCE_AUTO) }
                    return
                }
                recordPoint(loc, speed)
            }
            DriveState.IDLE -> Unit
        }
        evaluate()
    }

    private suspend fun recordPoint(loc: Location, speed: Double) {
        val t = trip ?: return
        if (loc.accuracy > (if (t.isWalk) 50f else 100f)) return
        val now = System.currentTimeMillis()

        if (loc.accuracy <= (if (t.isWalk) 25f else 35f)) {
            val prev = lastGood
            if (prev == null) lastGood = loc
            else {
                val d = prev.distanceTo(loc)
                val jitter = if (t.isWalk) max(8f, loc.accuracy) else 5f
                if (!(d < jitter && speed < 5.0)) { distance += d; lastGood = loc } // ignoruj GPS "tancovanie" na mieste
            }
            if (!t.isWalk && speed < 300) maxSpeed = max(maxSpeed, speed)
        }

        var event: String? = null
        if (t.isWalk) {
            if (!stepSensorActive && speed >= WALK_MOVING_KMH) lastMovingAt = now
        } else {
            if (speed >= MOVING_KMH) lastMovingAt = now
            if (speed >= settings.minDriveSpeedKmh) exitSignalAt = null
            maybeLookupRoad(loc)
            event = detectEvent(loc, speed)
        }

        dao.insertPoint(
            PointEntity(
                tripId = t.id, ts = loc.time, lat = loc.latitude, lng = loc.longitude,
                speedKmh = speed.toFloat(), accuracyM = loc.accuracy,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                event = event,
            )
        )
        pointCount++
        var updated = t
        if (t.startLat == null) updated = t.copy(startLat = loc.latitude, startLng = loc.longitude)
        updated = updated.copy(
            distanceM = currentDistance(), maxSpeedKmh = maxSpeed, steps = steps,
            hardBrakes = hardBrakes, hardAccels = hardAccels, speedingSec = (speedingMs / 1000).toInt(),
        )
        // Jazda patrí aktuálne zvolenému vozidlu – prepnutie vozidla počas jazdy sa prejaví hneď
        if (!t.isWalk) {
            val active = settings.activeVehicleUuid
            if (active.isNotEmpty() && updated.vehicleUuid != active) {
                updated = updated.copy(vehicleUuid = active)
                vehicle = container.vehicles.byUuid(active)
                vehicleName = vehicle?.label
            }
        }
        trip = updated
        if (pointCount % 10 == 1) dao.updateTrip(updated) // priebežne – pre obnovu po páde
        publishLive(loc)
    }

    /** Pri chôdzi s krokomerom sa vzdialenosť počíta z krokov (funguje aj v budove), inak z GPS. */
    private fun currentDistance(): Double =
        if (isWalk && stepSensorActive && steps > 0) steps * settings.strideCm / 100.0 else distance

    /** Typ cesty sa zisťuje po ~400 m alebo každé 2 min (najviac raz za 20 s), na pozadí. */
    private fun maybeLookupRoad(loc: Location) {
        if (!settings.roadLimits || roadLookupRunning || loc.accuracy > 40f) return
        val now = System.currentTimeMillis()
        if (now - roadLookupAt < 20_000) return
        val prev = roadLookupLoc
        if (prev != null && prev.distanceTo(loc) < 400f && now - roadLookupAt < 120_000) return
        roadLookupRunning = true
        roadLookupAt = now
        roadLookupLoc = loc
        lifecycleScope.launch {
            RoadInfoProvider.lookup(loc.latitude, loc.longitude)?.let { roadInfo = it }
            roadLookupRunning = false
        }
    }

    private fun currentZone(): RoadZone? = TrackingState.zoneOverride.value ?: roadInfo?.zone

    /** Aktuálny limit; null = typ cesty zatiaľ neznámy (bez internetu) → prekročenie sa nehodnotí. */
    private fun currentLimit(): Int? {
        if (!settings.roadLimits) return settings.speedAlertKmh
        val zone = currentZone() ?: return null
        val sign = if (TrackingState.zoneOverride.value != null) null else roadInfo?.signLimit
        return speedLimitFor(zone, sign, vehicle, settings)
    }

    /** Prudké brzdenie / zrýchlenie z rozdielu GPS rýchlostí, prekročenie nastaveného limitu. */
    private fun detectEvent(loc: Location, speedKmh: Double): String? {
        if (!settings.drivingEvents) return null
        var event: String? = null
        val now = loc.time

        val limit = currentLimit()
        val speeding = limit != null && speedKmh > limit + settings.speedToleranceKmh && loc.accuracy <= 30f
        val prev = speedFix
        if (speeding && prev != null) speedingMs += (now - prev.time).coerceIn(0, 10_000)
        if (speeding && !wasSpeeding) {
            event = DriveEvent.SPEEDING
            if (settings.speedAlert && System.currentTimeMillis() - lastSpeedAlertAt > 60_000) {
                lastSpeedAlertAt = System.currentTimeMillis()
                Notifications.speedAlert(this, speedKmh.toInt(), limit ?: 0, currentZone()?.label, settings)
            }
        }
        wasSpeeding = speeding

        if (loc.hasSpeed() && loc.accuracy <= 25f) {
            if (prev != null) {
                val dt = (now - prev.time) / 1000.0
                if (dt in 1.0..6.0 && now - lastEventAt > 8_000) {
                    val a = (loc.speed - prev.speed) / dt
                    if (a <= DrivingScore.BRAKE_MS2 && prev.speed > 4f) { hardBrakes++; lastEventAt = now; event = DriveEvent.BRAKE }
                    else if (a >= DrivingScore.ACCEL_MS2) { hardAccels++; lastEventAt = now; event = DriveEvent.ACCEL }
                }
            }
            speedFix = loc
        }
        return event
    }

    private suspend fun evaluate() {
        val now = System.currentTimeMillis()
        when (state) {
            DriveState.CANDIDATE -> {
                val factor = if (source == SOURCE_BLUETOOTH) 2 else 1
                if (now - candidateSince > settings.confirmTimeoutMin * 60_000L * factor) discard()
            }
            DriveState.DRIVING -> {
                if (manual) { updateNotification(); return }
                val exit = exitSignalAt
                when {
                    exit != null && now - exit >= EXIT_CONFIRM_MS && lastSpeed < 15 -> finishTrip(lastMovingAt)
                    now - lastMovingAt >= settings.stopDelayMin * 60_000L -> finishTrip(lastMovingAt)
                    else -> updateNotification()
                }
            }
            DriveState.WALKING -> {
                publishLive(null)
                if (manual) { updateNotification(); return }
                when {
                    now - lastMovingAt >= settings.walkStopMin * 60_000L -> finishTrip(lastMovingAt)
                    settings.walkWorkHoursOnly && !settings.walkActiveNow(now) -> finishTrip(now)
                    else -> updateNotification()
                }
            }
            DriveState.IDLE -> Unit
        }
    }

    /**
     * Uloží jazdu/chôdzu. [then] = čo spustiť hneď potom (napr. chôdza po jazde);
     * bez neho sa služba ukončí.
     */
    private suspend fun finishTrip(endedAt: Long, then: (suspend () -> Unit)? = null) {
        stopLocation()
        stopSteps()
        val t = trip ?: run { if (then != null) then() else shutdown(); return }
        trip = null
        val last = dao.lastPoint(t.id)
        val dist = if (t.isWalk && steps > 0 && t.steps >= 0) steps * settings.strideCm / 100.0 else distance
        val end = max(endedAt, if (t.isWalk) endedAt else (last?.ts ?: endedAt)).coerceAtLeast(t.startedAt)
        val hours = (end - t.startedAt) / 3_600_000.0
        val avg = if (hours > 0) dist / 1000.0 / hours else 0.0
        val minDist = if (t.isWalk) settings.walkMinDistanceM else settings.minTripDistanceM

        val summary: String?
        if (!manual && dist < minDist) {
            dao.deleteTrip(t.id)
            summary = if (t.isWalk) null else "Jazda bola kratšia ako $minDist m – neuložená"
        } else {
            val startAddr = if (settings.detectAddresses && t.startLat != null) Geo.address(this, t.startLat, t.startLng!!) else null
            val endAddr = if (settings.detectAddresses && last != null) Geo.address(this, last.lat, last.lng) else null
            if (t.isWalk) {
                dao.updateTrip(
                    t.copy(
                        endedAt = end, distanceM = dist, avgSpeedKmh = avg, steps = steps,
                        endLat = last?.lat, endLng = last?.lng, finished = true,
                        startAddress = startAddr, endAddress = endAddr,
                    )
                )
                summary = "${String.format(Locale("sk"), "%,d", steps)} krokov · ${formatDistance(dist)} · ${formatDuration(end - t.startedAt)}"
            } else {
                val speedingSec = (speedingMs / 1000).toInt()
                val score = if (settings.drivingEvents) DrivingScore.compute(dist, end - t.startedAt, hardBrakes, hardAccels, speedingSec) else null
                val saved = t.copy(
                    endedAt = end, distanceM = dist, maxSpeedKmh = maxSpeed, avgSpeedKmh = avg,
                    endLat = last?.lat, endLng = last?.lng, finished = true,
                    startAddress = startAddr, endAddress = endAddr, score = score,
                    hardBrakes = hardBrakes, hardAccels = hardAccels, speedingSec = speedingSec,
                )
                dao.updateTrip(saved)
                if (last != null) RuntimeState.setParking(this, last.lat, last.lng, end, endAddr)
                val vehicle = container.vehicles.byUuid(saved.vehicleUuid)
                summary = buildString {
                    append("${formatDistance(dist)} · ${formatDuration(end - t.startedAt)} · Ø ${avg.toInt()} km/h")
                    append(" · ~").append(String.format(Locale("sk"), "%.2f €", saved.cost(vehicle, settings)))
                    if (score != null) append(" · štýl $score/100")
                    vehicle?.let { append("\n").append(it.label) }
                }
            }
            if (settings.autoSync) SyncWorker.enqueueNow(this, settings.syncWifiOnly)
        }
        if (t.isWalk) Notifications.walkEvent(this, started = false, s = settings, summary = summary)
        else Notifications.driveEvent(this, started = false, s = settings, summary = summary)
        Log.i(TAG, "Záznam ukončený: $summary")

        val walkNext: suspend () -> Unit = { beginWalk(false) }
        val next = then ?: (if (!t.isWalk && pendingWalk && settings.walkActiveNow()) walkNext else null)
        pendingWalk = false
        if (next != null) next() else shutdown()
    }

    private suspend fun discard(then: (suspend () -> Unit)? = null) {
        stopLocation()
        candidateBuffer.clear()
        Log.i(TAG, "Kandidát zahodený – nejde o jazdu autom")
        if (then != null) then() else shutdown()
    }

    /** Uzavrie záznamy, ktoré ostali otvorené (pád appky, vybitá batéria). */
    private suspend fun closeStaleTrips() {
        for (t in dao.unfinishedTrips(userId)) {
            if (t.id == trip?.id) continue
            val last = dao.lastPoint(t.id)
            val minDist = if (t.isWalk) settings.walkMinDistanceM else settings.minTripDistanceM
            if (last == null || t.distanceM < minDist) dao.deleteTrip(t.id)
            else dao.updateTrip(t.copy(endedAt = last.ts, endLat = last.lat, endLng = last.lng, finished = true))
        }
    }

    private fun shutdown() {
        TrackingState.state.value = DriveState.IDLE
        TrackingState.live.value = null
        stopLocation()
        stopSteps()
        handler.removeCallbacks(ticker)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- kroky ----------------

    private fun startSteps(offset: Int) {
        stopSteps()
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        stepBase = -1f
        stepsOffset = offset
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values[0]
                if (stepBase < 0) stepBase = v
                val n = stepsOffset + (v - stepBase).toInt()
                if (n > steps) {
                    steps = n
                    lastMovingAt = System.currentTimeMillis()
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        stepSensorActive = sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
        if (stepSensorActive) stepListener = listener
    }

    private fun stopSteps() {
        stepListener?.let { sensorManager?.unregisterListener(it) }
        stepListener = null
        stepSensorActive = false
    }

    // ---------------- poloha ----------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationActive) return
        val walking = state == DriveState.WALKING
        val intervalMs = (if (walking) settings.walkGpsIntervalSec else settings.gpsIntervalSec) * 1000L
        val priority = when {
            walking -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            settings.highAccuracy -> Priority.PRIORITY_HIGH_ACCURACY
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Chýba povolenie polohy", e)
        }
        acquireWakeLock()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun stopLocation() {
        if (!locationActive) return
        fused.removeLocationUpdates(callback)
        locationActive = false
    }

    private fun speedOf(loc: Location, prev: Location?): Double {
        if (loc.hasSpeed() && (!loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond < 4f)) return loc.speed * 3.6
        if (prev == null) return 0.0
        val dt = (loc.time - prev.time) / 1000.0
        return if (dt > 0) prev.distanceTo(loc) / dt * 3.6 else 0.0
    }

    // ---------------- notifikácia / UI ----------------

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Notifications.ID_ONGOING, Notifications.ongoing(this, state, TrackingState.live.value, settings), type)
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground zlyhal (chýba povolenie polohy na pozadí?)", e)
        false
    }

    private fun updateNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 5_000) return
        lastNotifUpdate = now
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_ONGOING, Notifications.ongoing(this, state, TrackingState.live.value, settings))
    }

    private fun publishLive(loc: Location?) {
        val t = trip ?: return
        val prev = TrackingState.live.value
        TrackingState.live.value = LiveTrip(
            startedAt = t.startedAt, distanceM = currentDistance(), speedKmh = lastSpeed, maxSpeedKmh = maxSpeed,
            points = pointCount, lat = loc?.latitude ?: prev?.lat, lng = loc?.longitude ?: prev?.lng,
            source = source, manual = manual, type = t.type, steps = steps, vehicleName = vehicleName,
            limitKmh = if (t.isWalk) null else currentLimit(), zone = if (t.isWalk) null else currentZone(),
            zoneManual = TrackingState.zoneOverride.value != null, roadName = roadInfo?.roadName,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarTracking:drive")
            .apply { acquire(10 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopLocation()
        stopSteps()
        releaseWakeLock()
        // Ak systém službu zabil počas záznamu, uložíme priebežný stav – recover() ho po reštarte dokončí
        trip?.let { t ->
            val snapshot = t.copy(
                distanceM = currentDistance(), maxSpeedKmh = maxSpeed, steps = steps, hardBrakes = hardBrakes,
                hardAccels = hardAccels, speedingSec = (speedingMs / 1000).toInt(),
            )
            val dao = this.dao
            appScope.launch { dao.updateTrip(snapshot) }
        }
        TrackingState.state.value = DriveState.IDLE
        TrackingState.live.value = null
        super.onDestroy()
    }
}
