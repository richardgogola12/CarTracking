package sk.cartracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import sk.cartracker.data.RuntimeState
import sk.cartracker.data.local.DriveEvent
import sk.cartracker.data.local.PointEntity
import sk.cartracker.data.local.TripEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import java.util.UUID
import kotlin.math.max

enum class DriveState { IDLE, CANDIDATE, DRIVING }

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
)

/** Stav služby pre UI (rovnaký proces). */
object TrackingState {
    val state = MutableStateFlow(DriveState.IDLE)
    val live = MutableStateFlow<LiveTrip?>(null)
}

/**
 * Služba v popredí, ktorá beží nezávisle od hlavného okna.
 *
 * Stavový automat:
 *   IDLE ──(nastúpil do vozidla / BT auta)──► CANDIDATE ──(3× rýchlosť ≥ limit)──► DRIVING
 *   CANDIDATE ──(chôdza/bicykel alebo timeout bez rýchlosti)──► IDLE   (bez notifikácie, nič sa neuloží)
 *   DRIVING ──(státie X min alebo vystúpenie z auta + státie)──► IDLE   (uloží jazdu, notifikácia + vibrácia)
 *
 * Vďaka potvrdeniu rýchlosťou sa chôdza, beh a bicykel nikdy nezaznamenajú ako jazda.
 */
class TrackingService : LifecycleService() {

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_CANDIDATE = "sk.cartracker.action.CANDIDATE"
        const val ACTION_MANUAL_START = "sk.cartracker.action.MANUAL_START"
        const val ACTION_EXIT_SIGNAL = "sk.cartracker.action.EXIT_SIGNAL"
        const val ACTION_STOP = "sk.cartracker.action.STOP"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_BLUETOOTH = "bluetooth"
        const val SOURCE_MANUAL = "manual"

        private const val MOVING_KMH = 8.0          // nad touto rýchlosťou sa auto "hýbe"
        private const val EXIT_CONFIRM_MS = 60_000L  // po vystúpení z auta počkaj 1 min
        private const val TICK_MS = 15_000L

        fun startCandidate(ctx: Context, source: String) =
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_CANDIDATE).putExtra(EXTRA_SOURCE, source))

        fun startManual(ctx: Context) =
            startFg(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_MANUAL_START))

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

    // DRIVING
    private var trip: TripEntity? = null
    private var distance = 0.0
    private var maxSpeed = 0.0
    private var lastSpeed = 0.0
    private var pointCount = 0
    private var prevFix: Location? = null
    private var lastGood: Location? = null
    private var lastMovingAt = 0L
    private var exitSignalAt: Long? = null

    // Štýl jazdy
    private var hardBrakes = 0
    private var hardAccels = 0
    private var speedingMs = 0L
    private var speedFix: Location? = null      // posledný presný bod s rýchlosťou (na výpočet zrýchlenia)
    private var lastEventAt = 0L
    private var wasSpeeding = false
    private var lastSpeedAlertAt = 0L

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

        when (action) {
            ACTION_CANDIDATE -> when (state) {
                DriveState.IDLE -> beginCandidate(src)
                else -> exitSignalAt = null // znova v aute (napr. po zastavení na svetelnej križovatke)
            }
            ACTION_MANUAL_START -> when (state) {
                DriveState.IDLE -> { manual = true; source = SOURCE_MANUAL; startLocation(); startTrip(System.currentTimeMillis()) }
                DriveState.CANDIDATE -> { manual = true; source = SOURCE_MANUAL; confirmDriving() }
                DriveState.DRIVING -> manual = true
            }
            ACTION_EXIT_SIGNAL -> when (state) {
                DriveState.CANDIDATE -> discard()           // začal chodiť/bicyklovať → nebola to jazda
                DriveState.DRIVING -> if (!manual && exitSignalAt == null) exitSignalAt = System.currentTimeMillis()
                DriveState.IDLE -> shutdown()
            }
            ACTION_STOP -> when (state) {
                DriveState.CANDIDATE -> discard()
                DriveState.DRIVING -> finishTrip(System.currentTimeMillis())
                DriveState.IDLE -> shutdown()
            }
            null -> recover()
        }
    }

    // ---------------- stavy ----------------

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
        startTrip(buffered.firstOrNull()?.time ?: System.currentTimeMillis())
        var prev: Location? = null
        for (loc in buffered) { recordPoint(loc, speedOf(loc, prev)); prev = loc }
        Notifications.driveEvent(this, started = true, s = settings)
        Log.i(TAG, "Jazda potvrdená")
    }

    private suspend fun startTrip(startedAt: Long) {
        val t = TripEntity(uuid = UUID.randomUUID().toString(), userId = userId, startedAt = startedAt,
            triggerSource = source, category = settings.defaultCategory)
        trip = t.copy(id = dao.insertTrip(t))
        distance = 0.0; maxSpeed = 0.0; pointCount = 0
        lastGood = null
        hardBrakes = 0; hardAccels = 0; speedingMs = 0L; speedFix = null; wasSpeeding = false
        lastMovingAt = System.currentTimeMillis()
        exitSignalAt = null
        TrackingState.state.value = DriveState.DRIVING
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
        distance = t.distanceM; maxSpeed = t.maxSpeedKmh
        hardBrakes = t.hardBrakes; hardAccels = t.hardAccels; speedingMs = t.speedingSec * 1000L
        speedFix = null; wasSpeeding = false
        pointCount = dao.points(t.id).size
        lastGood = last?.let { Location("db").apply { latitude = it.lat; longitude = it.lng; time = it.ts } }
        lastMovingAt = System.currentTimeMillis()
        exitSignalAt = null
        TrackingState.state.value = DriveState.DRIVING
        startLocation()
        publishLive(null)
        updateNotification(force = true)
        Log.i(TAG, "Jazda obnovená po reštarte služby")
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
            DriveState.IDLE -> Unit
        }
        evaluate()
    }

    private suspend fun recordPoint(loc: Location, speed: Double) {
        val t = trip ?: return
        if (loc.accuracy > 100f) return
        val now = System.currentTimeMillis()

        if (loc.accuracy <= 35f) {
            val prev = lastGood
            if (prev == null) lastGood = loc
            else {
                val d = prev.distanceTo(loc)
                if (!(d < 5f && speed < 5.0)) { distance += d; lastGood = loc } // ignoruj GPS "tancovanie" na mieste
            }
            if (speed < 300) maxSpeed = max(maxSpeed, speed)
        }
        if (speed >= MOVING_KMH) lastMovingAt = now
        if (speed >= settings.minDriveSpeedKmh) exitSignalAt = null

        val event = detectEvent(loc, speed)

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
        updated = updated.copy(distanceM = distance, maxSpeedKmh = maxSpeed,
            hardBrakes = hardBrakes, hardAccels = hardAccels, speedingSec = (speedingMs / 1000).toInt())
        trip = updated
        if (pointCount % 10 == 1) dao.updateTrip(updated) // priebežne – pre obnovu po páde
        publishLive(loc)
    }

    /** Prudké brzdenie / zrýchlenie z rozdielu GPS rýchlostí, prekročenie nastaveného limitu. */
    private fun detectEvent(loc: Location, speedKmh: Double): String? {
        if (!settings.drivingEvents) return null
        var event: String? = null
        val now = loc.time

        // Prekračovanie rýchlosti
        val speeding = speedKmh > settings.speedAlertKmh && loc.accuracy <= 30f
        val prev = speedFix
        if (speeding && prev != null) speedingMs += (now - prev.time).coerceIn(0, 10_000)
        if (speeding && !wasSpeeding) {
            event = DriveEvent.SPEEDING
            if (settings.speedAlert && System.currentTimeMillis() - lastSpeedAlertAt > 60_000) {
                lastSpeedAlertAt = System.currentTimeMillis()
                Notifications.speedAlert(this, speedKmh.toInt(), settings)
            }
        }
        wasSpeeding = speeding

        // Zrýchlenie – iba z presných bodov s rýchlosťou z GPS, 1–6 s od seba
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
            DriveState.IDLE -> Unit
        }
    }

    private suspend fun finishTrip(endedAt: Long) {
        stopLocation()
        val t = trip ?: run { shutdown(); return }
        val last = dao.lastPoint(t.id)
        val end = max(endedAt, last?.ts ?: endedAt).coerceAtLeast(t.startedAt)
        val hours = (end - t.startedAt) / 3_600_000.0
        val avg = if (hours > 0) distance / 1000.0 / hours else 0.0

        val summary: String
        if (!manual && distance < settings.minTripDistanceM) {
            dao.deleteTrip(t.id)
            summary = "Jazda bola kratšia ako ${settings.minTripDistanceM} m – neuložená"
        } else {
            val speedingSec = (speedingMs / 1000).toInt()
            val score = if (settings.drivingEvents) DrivingScore.compute(distance, end - t.startedAt, hardBrakes, hardAccels, speedingSec) else null
            val startAddr = if (settings.detectAddresses && t.startLat != null) Geo.address(this, t.startLat, t.startLng!!) else null
            val endAddr = if (settings.detectAddresses && last != null) Geo.address(this, last.lat, last.lng) else null
            dao.updateTrip(
                t.copy(
                    endedAt = end, distanceM = distance, maxSpeedKmh = maxSpeed, avgSpeedKmh = avg,
                    endLat = last?.lat, endLng = last?.lng, finished = true,
                    startAddress = startAddr, endAddress = endAddr, score = score,
                    hardBrakes = hardBrakes, hardAccels = hardAccels, speedingSec = speedingSec,
                )
            )
            if (last != null) RuntimeState.setParking(this, last.lat, last.lng, end, endAddr)
            summary = buildString {
                append("${formatDistance(distance)} · ${formatDuration(end - t.startedAt)} · Ø ${avg.toInt()} km/h")
                append(" · ~").append(String.format(java.util.Locale("sk"), "%.2f €", settings.fuelCost(distance)))
                if (score != null) append(" · štýl $score/100")
            }
            if (settings.autoSync) SyncWorker.enqueueNow(this, settings.syncWifiOnly)
        }
        Notifications.driveEvent(this, started = false, s = settings, summary = summary)
        trip = null
        Log.i(TAG, "Jazda ukončená: $summary")
        shutdown()
    }

    private fun discard() {
        stopLocation()
        candidateBuffer.clear()
        Log.i(TAG, "Kandidát zahodený – nejde o jazdu autom")
        shutdown()
    }

    /** Uzavrie jazdy, ktoré ostali otvorené (pád appky, vybitá batéria). */
    private suspend fun closeStaleTrips() {
        for (t in dao.unfinishedTrips(userId)) {
            if (t.id == trip?.id) continue
            val last = dao.lastPoint(t.id)
            if (last == null || t.distanceM < settings.minTripDistanceM) dao.deleteTrip(t.id)
            else dao.updateTrip(t.copy(endedAt = last.ts, endLat = last.lat, endLng = last.lng, finished = true))
        }
    }

    private fun shutdown() {
        TrackingState.state.value = DriveState.IDLE
        TrackingState.live.value = null
        stopLocation()
        handler.removeCallbacks(ticker)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- poloha ----------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationActive) return
        val intervalMs = settings.gpsIntervalSec * 1000L
        val priority = if (settings.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
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
        TrackingState.live.value = LiveTrip(
            startedAt = t.startedAt, distanceM = distance, speedKmh = lastSpeed, maxSpeedKmh = maxSpeed,
            points = pointCount, lat = loc?.latitude, lng = loc?.longitude, source = source, manual = manual,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarTracking:drive")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopLocation()
        releaseWakeLock()
        // Ak systém službu zabil počas jazdy, uložíme priebežný stav – recover() ju po reštarte dokončí
        trip?.let { t ->
            val snapshot = t.copy(distanceM = distance, maxSpeedKmh = maxSpeed, hardBrakes = hardBrakes,
                hardAccels = hardAccels, speedingSec = (speedingMs / 1000).toInt())
            val dao = this.dao
            appScope.launch { dao.updateTrip(snapshot) }
        }
        TrackingState.state.value = DriveState.IDLE
        TrackingState.live.value = null
        super.onDestroy()
    }
}
