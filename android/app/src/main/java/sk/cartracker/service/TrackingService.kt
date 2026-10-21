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
import sk.cartracker.data.BatteryStats
import sk.cartracker.data.TripClassifier
import sk.cartracker.data.DiagLog
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
import sk.cartracker.data.StrideFix
import sk.cartracker.data.local.TYPE_CAR
import sk.cartracker.data.local.TYPE_WALK
import sk.cartracker.data.local.TripEntity
import sk.cartracker.sync.SyncWorker
import sk.cartracker.widget.CarWidget
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

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
        /** Dočasný záznam s bodmi, ktoré prevezme nasledujúca jazda / chôdza (nikdy sa neobnovuje ani nezobrazuje). */
        const val SOURCE_HANDOVER = "handover"

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
    /** Auto sa v tejto jazde už pohlo (rýchlosť ≥ potvrdzujúca). Kým nie, ručná jazda sa sama neukončí a jazda
     *  spustená „jednou podmienkou“ sa neohlási (žiadny zvuk ani notifikácia pri falošnom hlásení systému). */
    private var hasMoved = false
    private var announcePending = false
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
    // Skutočný limit z mapy (OSM) pre cestu pod autom a vlastné opravy používateľa
    private var mapSign: Int? = null
    private var customSign: Int? = null
    private var customCache: Pair<String, List<sk.cartracker.data.SpeedLimits.Custom>> = "" to emptyList()

    // Štýl jazdy
    private var hardBrakes = 0
    private var hardAccels = 0
    private var speedingMs = 0L
    private var speedFix: Location? = null
    private var lastEventAt = 0L
    private var wasSpeeding = false
    /** Upozornenia na prekročenie: hneď, potom každých N s, kým trvá; reset po 2 bodoch pod limitom. */
    private val speedAlerter = sk.cartracker.data.SpeedAlerter()
    private var speedAlertJob: kotlinx.coroutines.Job? = null
    private var lastSpeedKmh = 0; private var lastLimitKmh = 0; private var lastFixAt = 0L

    // Kroky
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var stepListener: SensorEventListener? = null
    private var stepBase = -1f
    private var stepsOffset = 0
    private var steps = 0
    private var candidateMax = 0.0
    private var stepSensorActive = false
    // Krokomer počas jazdy: koľko krokov od zastavenia auta (chôdza po zaparkovaní začne už od auta)
    private var carStepListener: SensorEventListener? = null
    private var carCounter = -1f
    private var counterAtMove = -1f

    /**
     * Odovzdanie medzi chôdzou a jazdou: body po konci jedného záznamu (chôdza → nastupovanie a rozjazd,
     * jazda → odchod od auta) sa presunú do dočasného záznamu [holder] a nový záznam ich prevezme –
     * začne tam a vtedy, kde skončil predchádzajúci, takže sa nestratia metre ani kilometre.
     */
    private class Handover(val holder: Long, val type: String, val at: Long, val lat: Double?, val lng: Double?, val steps: Int, val created: Long)
    private var handover: Handover? = null

    private var locationActive = false
    // Úspora batérie: interval GPS podľa situácie (normálne / diaľnica / státie)
    private enum class GpsMode(val label: String) { NORMAL("normálny"), HIGHWAY("diaľnica – redšie body"), STOPPED("státie – úsporný") }
    private var gpsMode = GpsMode.NORMAL
    private var fastSince = 0L
    private var slowSince = 0L
    // Spotreba batérie počas behu GPS
    private var gpsOnAt = 0L
    private var battStart = -1
    private var battCharging = false
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
        if (userId < 0 || !hasLocationPermission()) {
            DiagLog.log(this, DiagLog.ERROR, if (userId < 0) "Sledovanie nespustené – nie ste prihlásený" else "Sledovanie nespustené – chýba povolenie polohy (Povolenia → Poloha vždy)")
            shutdown(); return
        }
        if (action != null && action != ACTION_CANDIDATE) DiagLog.log(this, DiagLog.APP, "Príkaz: " + when (action) {
            ACTION_MANUAL_START -> "ručne spustiť jazdu"; ACTION_MANUAL_WALK -> "ručne spustiť chôdzu"; ACTION_STOP -> "ukončiť / nie je to jazda"
            ACTION_EXIT_SIGNAL -> "vystúpenie z vozidla"; ACTION_WALK_SIGNAL -> "chôdza"; ACTION_BIKE_SIGNAL -> "bicykel"; else -> action
        } + " (stav: ${state.name})")
        val now = System.currentTimeMillis()
        // Systém (napr. Xiaomi) mohol službu počas jazdy ukončiť a nový príkaz ju spustil nanovo →
        // najprv nadviazať na rozbehnutú jazdu, aby nevznikla druhá jazda a prvá sa neodrezala.
        if (action != null && state == DriveState.IDLE && trip == null && tryRecover())
            DiagLog.log(this, DiagLog.APP, "Služba bola ukončená systémom – záznam ${if (state == DriveState.WALKING) "chôdze" else "jazdy"} obnovený")

        when (action) {
            ACTION_CANDIDATE -> when (state) {
                DriveState.IDLE -> beginCandidate(src)
                DriveState.WALKING -> finishTrip(lastMovingAt, TYPE_CAR) { beginCandidate(src) }   // z chôdze nastúpil do auta (koniec chôdze = posledný krok)
                else -> { exitSignalAt = null; pendingWalk = false }             // znova v aute
            }
            ACTION_MANUAL_START -> when (state) {
                DriveState.IDLE -> manualCar()
                DriveState.CANDIDATE -> { manual = true; source = SOURCE_MANUAL; confirmDriving() }
                DriveState.DRIVING -> manual = true
                DriveState.WALKING -> finishTrip(now, TYPE_CAR) { manualCar() }
            }
            ACTION_MANUAL_WALK -> when (state) {
                DriveState.IDLE -> beginWalk(manualWalk = true)
                DriveState.CANDIDATE -> discard("ručne spustená chôdza") { beginWalk(manualWalk = true) }
                DriveState.DRIVING -> finishTrip(now, TYPE_WALK) { beginWalk(manualWalk = true) }
                DriveState.WALKING -> manual = true
            }
            ACTION_WALK_SIGNAL -> when (state) {
                DriveState.IDLE -> if (settings.walkActiveNow()) beginWalk(false) else shutdown()
                DriveState.CANDIDATE -> discard("systém hlási chôdzu") { if (settings.walkActiveNow()) beginWalk(false) else shutdown() }
                DriveState.DRIVING -> if (autoEndAllowed()) {
                    if (exitSignalAt == null) exitSignalAt = now
                    pendingWalk = settings.walkActiveNow()
                }
                DriveState.WALKING -> Unit
            }
            ACTION_EXIT_SIGNAL -> when (state) {
                DriveState.CANDIDATE -> discard("vystúpenie z vozidla / odpojené Bluetooth")
                DriveState.DRIVING -> if (autoEndAllowed() && exitSignalAt == null) exitSignalAt = now
                DriveState.WALKING -> Unit
                DriveState.IDLE -> shutdown()
            }
            ACTION_BIKE_SIGNAL -> when (state) {
                DriveState.CANDIDATE -> discard("systém hlási bicykel")
                DriveState.DRIVING -> if (autoEndAllowed() && exitSignalAt == null) exitSignalAt = now
                DriveState.WALKING -> if (!manual) finishTrip(lastMovingAt)
                DriveState.IDLE -> shutdown()
            }
            ACTION_STOP -> when (state) {
                DriveState.CANDIDATE -> discard("ručne – „Nie je to jazda“")
                // auto už stojí dlhšie ako 2 min (zabudnuté ukončenie) → koniec jazdy = posledný pohyb
                DriveState.DRIVING -> finishTrip(if (hasMoved && now - lastMovingAt > 2 * 60_000L) lastMovingAt else now)
                DriveState.WALKING -> finishTrip(now)
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
        candidateMax = 0.0
        if (settings.detectAnyCondition) {
            // Stačí jedna podmienka: systém hlási „vo vozidle“ alebo sa pripojilo Bluetooth auta → jazda sa zapisuje hneď
            DiagLog.log(this, DiagLog.CHECK, "Stačí jedna podmienka – " + (if (src == SOURCE_BLUETOOTH) "pripojené Bluetooth auta" else "systém hlási jazdu vo vozidle") + " → jazda sa zapisuje hneď")
            confirmDriving(if (src == SOURCE_BLUETOOTH) "Bluetooth auta" else "systém hlási vo vozidle", announce = false)
            return
        }
        DiagLog.log(this, DiagLog.CHECK, "Overujem jazdu (zdroj: ${if (src == SOURCE_BLUETOOTH) "Bluetooth" else "systém"}) – treba 3 body GPS s rýchlosťou ≥ ${settings.minDriveSpeedKmh} km/h do ${settings.confirmTimeoutMin.coerceAtLeast(1) * (if (src == SOURCE_BLUETOOTH) 2 else 1)} min")
    }

    /** Počet rýchlych bodov GPS potrebných na potvrdenie jazdy (pri „stačí jedna podmienka“ jeden). */
    private fun fixesNeeded() = if (settings.detectAnyCondition) 1 else 3

    private suspend fun confirmDriving(reason: String = "potvrdené rýchlosťou GPS", announce: Boolean = true) {
        val buffered = candidateBuffer.toList()
        candidateBuffer.clear()
        startTrip(buffered.firstOrNull()?.time ?: System.currentTimeMillis(), TYPE_CAR)
        hasMoved = hasMoved || (announce && !manual) || buffered.any { speedOf(it, null) >= settings.minDriveSpeedKmh }
        announcePending = !announce && !hasMoved
        var prev: Location? = null
        for (loc in buffered) { recordPoint(loc, speedOf(loc, prev)); prev = loc }
        DiagLog.log(this, DiagLog.DRIVE, "Režim jazdy ZAPNUTÝ" + (if (manual) " (ručne)" else " – $reason") + (vehicleName?.let { " · $it" } ?: "") +
            (if (announcePending) " – oznámenie, keď sa auto pohne" else ""))
        if (!announcePending) announceStart()
    }

    /** Notifikácia a zvuk začiatku jazdy. */
    private suspend fun announceStart() {
        announcePending = false
        val cont = trip?.let { mergeCandidate(it) } != null
        Notifications.driveEvent(this, started = true, s = settings,
            summary = listOfNotNull(if (cont) "Pokračovanie jazdy – po skončení sa zlúči s predchádzajúcou" else null,
                vehicleName?.let { "Vozidlo: $it" }).joinToString("\n").ifEmpty { null })
        Log.i(TAG, "Jazda potvrdená")
    }

    private suspend fun beginWalk(manualWalk: Boolean) {
        closeStaleTrips()
        manual = manualWalk
        source = if (manualWalk) SOURCE_MANUAL else SOURCE_AUTO
        stopLocation()
        startTrip(System.currentTimeMillis(), TYPE_WALK)
        startSteps(steps)   // kroky od zaparkovania auta (odovzdanie z jazdy), inak 0
        startLocation()
        Notifications.walkEvent(this, started = true, s = settings)
        Log.i(TAG, "Chôdza začala")
        DiagLog.log(this, DiagLog.WALK, "Chôdza začala" + if (manualWalk) " (ručne)" else "")
    }

    private suspend fun startTrip(startedAt: Long, type: String) {
        val vehicle = if (type == TYPE_CAR) container.vehicles.active() else null
        this.vehicle = vehicle
        vehicleName = vehicle?.label
        roadInfo = null; roadLookupLoc = null; roadLookupAt = 0L
        TrackingState.zoneOverride.value = null
        val h = handover?.takeIf { it.type == type && System.currentTimeMillis() - it.created < 20 * 60_000L }
        handover = null
        val t = TripEntity(
            uuid = UUID.randomUUID().toString(), userId = userId, startedAt = if (h != null) min(startedAt, h.at) else startedAt,
            startLat = h?.lat, startLng = h?.lng,
            triggerSource = source, type = type, vehicleUuid = vehicle?.uuid,
            category = if (type == TYPE_CAR) settings.defaultCategory else CATEGORY_PRIVATE,
        )
        trip = t.copy(id = dao.insertTrip(t))
        distance = 0.0; maxSpeed = 0.0; pointCount = 0
        lastGood = null
        hardBrakes = 0; hardAccels = 0; speedingMs = 0L; speedFix = null; wasSpeeding = false; speedAlerter.reset()
        steps = 0; stepsOffset = 0
        lastMovingAt = System.currentTimeMillis()
        exitSignalAt = null
        pendingWalk = false
        hasMoved = false; announcePending = false
        stopCarSteps()
        if (type == TYPE_CAR) { ParkingTimer.cancel(this); startCarSteps() }
        if (h != null) takeOver(h, type, startedAt)
        TrackingState.state.value = if (type == TYPE_WALK) DriveState.WALKING else DriveState.DRIVING
        publishLive(null)
        updateNotification(force = true)
    }

    /** Nový záznam prevezme body z odovzdania – začiatok = koniec predchádzajúceho záznamu. */
    private suspend fun takeOver(h: Handover, type: String, detectedAt: Long) {
        var t = trip ?: return
        dao.movePoints(listOf(h.holder), t.id)
        dao.deleteTrip(h.holder)
        val pts = dao.points(t.id)
        // bez prevzatých bodov: začiatok ostáva čas rozpoznania, len poloha nadväzuje na predchádzajúci záznam
        if (pts.isEmpty() && type == TYPE_CAR && t.startedAt != detectedAt) { t = t.copy(startedAt = detectedAt); trip = t }
        val walk = type == TYPE_WALK
        var prev: PointEntity? = null
        for (p in pts) {
            if ((p.accuracyM ?: 99f) > (if (walk) 25f else 35f)) continue
            if (prev != null) {
                val r = FloatArray(1); Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, r)
                if (!walk || (r[0] >= 8f && (p.speedKmh ?: 0f) < 15f)) distance += r[0]
            }
            prev = p
            if (!walk && (p.speedKmh ?: 0f) < 300f) maxSpeed = max(maxSpeed, (p.speedKmh ?: 0f).toDouble())
            if (!walk && (p.speedKmh ?: 0f) >= settings.minDriveSpeedKmh) hasMoved = true
        }
        pointCount = pts.size
        prev?.let { lastGood = Location("handover").apply { latitude = it.lat; longitude = it.lng; accuracy = it.accuracyM ?: 10f; time = it.ts } }
        if (walk) steps = h.steps
        trip = t.copy(distanceM = if (walk) currentDistance() else distance, maxSpeedKmh = maxSpeed, steps = steps)
        dao.updateTrip(trip!!)
        DiagLog.log(this, if (walk) DiagLog.WALK else DiagLog.DRIVE, (if (walk) "Chôdza nadväzuje na jazdu" else "Jazda nadväzuje na chôdzu") +
            " – začiatok ${sk.cartracker.ui.formatTime(h.at)}, prevzatých ${pts.size} bodov (${distance.toInt()} m)" + (if (walk && h.steps > 0) ", ${h.steps} krokov od zaparkovania" else ""))
    }

    private fun startCarSteps() {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        carCounter = -1f; counterAtMove = -1f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { carCounter = e.values[0]; if (counterAtMove < 0) counterAtMove = carCounter; feedDaily(e.values[0]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (sensorManager?.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true) carStepListener = l
    }

    /** Celodenné kroky: počas chôdze a jazdy sa krokomer zapisuje priebežne (najviac raz za minútu). */
    private var lastDailyFeed = 0L
    private fun feedDaily(v: Float) {
        val now = System.currentTimeMillis()
        if (!settings.allDaySteps || now - lastDailyFeed < settings.stepLiveSec.coerceIn(10, 600) * 1000L) return
        lastDailyFeed = now
        lifecycleScope.launch {
            runCatching { sk.cartracker.data.StepCounter.feed(this@TrackingService, v) }
            // počas chôdze a jazdy beží služba stále → kroky sa odosielajú presne v nastavenom intervale (aj každú minútu)
            if (sk.cartracker.data.StepCounter.syncDue(this@TrackingService, settings)) runCatching { container.steps.sync() }
        }
    }

    private fun stopCarSteps() {
        carStepListener?.let { sensorManager?.unregisterListener(it) }
        carStepListener = null
    }

    /** Kroky od chvíle, keď sa auto naposledy hýbalo (vystúpil a odchádza pešo). */
    private fun carStepsSinceStop() = if (carCounter >= 0 && counterAtMove >= 0) (carCounter - counterAtMove).toInt().coerceAtLeast(0) else 0

    private suspend fun recover() {
        if (state != DriveState.IDLE) return
        if (tryRecover()) DiagLog.log(this, DiagLog.APP, "Služba reštartovaná systémom – záznam obnovený")
        else { closeStaleTrips(); shutdown() }
    }

    /** Nadviaže na nedokončenú jazdu / chôdzu (posledný bod do 30 min). @return true = obnovené */
    private suspend fun tryRecover(): Boolean {
        val t = dao.unfinishedTrips(userId).filter { it.triggerSource != SOURCE_HANDOVER }.maxByOrNull { it.startedAt } ?: return false
        val last = dao.lastPoint(t.id)
        if (System.currentTimeMillis() - (last?.ts ?: t.startedAt) > 30 * 60_000L) return false
        trip = t
        source = t.triggerSource
        manual = t.triggerSource == SOURCE_MANUAL
        distance = if (t.isWalk && t.steps > 0 && !settings.walkDistanceGps) 0.0 else t.distanceM
        maxSpeed = t.maxSpeedKmh
        hardBrakes = t.hardBrakes; hardAccels = t.hardAccels; speedingMs = t.speedingSec * 1000L
        speedFix = null; wasSpeeding = false; speedAlerter.reset()
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
        hasMoved = t.distanceM > 300 || t.maxSpeedKmh >= settings.minDriveSpeedKmh
        announcePending = false
        updateNotification(force = true)
        Log.i(TAG, "Záznam obnovený po reštarte služby")
        return true
    }

    private suspend fun onLocation(loc: Location) {
        if (state == DriveState.IDLE) return
        // Systém niekedy ako prvý pošle starú uloženú polohu (napr. z miesta posledného parkovania) – tú ignorujeme
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > 30_000) {
            DiagLog.log(this, DiagLog.GPS, "Stará poloha (pred ${ageMs / 1000} s, ±${loc.accuracy.toInt()} m) – ignorovaná")
            return
        }
        val speed = speedOf(loc, prevFix)
        prevFix = loc
        lastSpeed = speed
        when (state) {
            DriveState.CANDIDATE -> {
                candidateBuffer.add(loc)
                if (candidateBuffer.size > 600) candidateBuffer.removeAt(0)
                if (loc.accuracy <= 40f && speed >= settings.minDriveSpeedKmh) fastFixes++
                candidateMax = max(candidateMax, speed)
                val btOk = !settings.bluetoothRequired || RuntimeState.isCarBtConnected(this)
                DiagLog.log(this, DiagLog.GPS, "${speed.toInt()} km/h, presnosť ±${loc.accuracy.toInt()} m → rýchlych bodov $fastFixes/${fixesNeeded()}" +
                    (if (loc.accuracy > 40f) " (nepresný bod sa nepočíta)" else "") + (if (!btOk) " · čaká sa na Bluetooth auta" else ""))
                if (fastFixes >= fixesNeeded() && btOk) confirmDriving()
            }
            DriveState.DRIVING -> recordPoint(loc, speed)
            DriveState.WALKING -> {
                // Rýchlosť auta počas „chôdze“ → nastúpil do vozidla
                // jeden bod stačí len s rýchlosťou meranou GPS (nie z rozdielu polôh – skok polohy by spustil jazdu)
                if (loc.accuracy <= 30f && speed >= settings.minDriveSpeedKmh && (!settings.detectAnyCondition || hasDoppler(loc))) fastFixes++
                else if (speed < 10) fastFixes = 0
                if (fastFixes >= fixesNeeded() && settings.autoDetection && !manual) {
                    finishTrip(lastMovingAt, TYPE_CAR) { beginCandidate(SOURCE_AUTO) }
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
            else if (t.isWalk) {
                val d = prev.distanceTo(loc)
                // ignoruj GPS "tancovanie" na mieste a úseky rýchlosťou vozidla (nastupovanie, rozjazd)
                if (!(d < max(8f, loc.accuracy) && speed < 5.0)) { if (speed < 15.0) distance += d; lastGood = loc }
            } else carStep(prev, loc, speed)?.let { d -> distance += d; lastGood = loc }
            // max. rýchlosť iba z Dopplerovej rýchlosti GPS (z rozdielu polôh by skok GPS dal nezmysel)
            if (!t.isWalk && hasDoppler(loc) && speed < 300) maxSpeed = max(maxSpeed, speed)
        }

        var event: String? = null
        if (t.isWalk) {
            if (!stepSensorActive && speed >= WALK_MOVING_KMH) lastMovingAt = now
        } else {
            if (speed >= MOVING_KMH) { lastMovingAt = now; if (carCounter >= 0) counterAtMove = carCounter }
            if (speed >= settings.minDriveSpeedKmh && loc.accuracy <= 40f) {
                exitSignalAt = null
                if (!hasMoved) { hasMoved = true; DiagLog.log(this, DiagLog.DRIVE, "Auto sa pohlo (${speed.toInt()} km/h)") }
                if (announcePending) announceStart()
            }
            adaptGps(speed, now)
            maybeLookupRoad(loc)
            updateSignLimit(loc, speed)
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

    private fun hasDoppler(loc: Location) = loc.hasSpeed() && (!loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond < 4f)

    /**
     * Úsek trasy auta medzi dvoma presnými bodmi (m), alebo null = bod sa nepočíta (kotva ostáva):
     *  1. státie – GPS hlási < 3 km/h a posun je v rámci presnosti (semafor, kolóna: poloha „tancuje“),
     *  2. skok GPS – posun nezodpovedá rýchlosti (odraz od budov),
     *  3. „cik-cak“ šumu – úsek najviac o 10 % + 3 m dlhší než dráha podľa rýchlosti GPS.
     * Overené simuláciou: v meste so semaformi pôvodný výpočet pripočítal ~+20 %, nový ~+4 %; na diaľnici oba ±0,3 %.
     */
    private fun carStep(prev: Location, loc: Location, speed: Double): Double? {
        val d = prev.distanceTo(loc).toDouble()
        val dt = (loc.time - prev.time) / 1000.0
        val r = sk.cartracker.data.DistanceFilter.carStep(d, dt, speed, hasDoppler(loc), prev.accuracy, loc.accuracy)
        if (r.jump) DiagLog.log(this, DiagLog.GPS, "Skok GPS ${d.toInt()} m za ${dt.toInt()} s pri ${speed.toInt()} km/h – ignorovaný")
        return r.meters
    }

    private fun currentDistance(): Double = if (isWalk) walkDistance() else distance

    /**
     * Vzdialenosť chôdze podľa nastavenia:
     *  - z krokov (kroky × dĺžka kroku) – funguje aj v budove; bez krokomera sa použije GPS,
     *  - z GPS – presnejšie vonku; ak GPS nič nenameralo (budova), použijú sa kroky.
     */
    private fun walkDistance(): Double {
        val bySteps = if (steps > 0) steps * settings.strideCm / 100.0 else null
        if (!settings.walkDistanceGps) return bySteps ?: distance
        // GPS v budove / pri slabom signále nameria zlomok skutočnej vzdialenosti → vtedy platia kroky
        if (bySteps != null && distance < bySteps * 0.5) return bySteps
        return if (distance > 0) distance else bySteps ?: 0.0
    }

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

    /** Limit zo značky pre aktuálny bod: vlastná oprava, inak cesta z mapy (OSM) v smere jazdy. */
    private fun updateSignLimit(loc: Location, speed: Double) {
        if (!settings.roadLimits || loc.accuracy > 30f) return
        val bearing = if (loc.hasBearing() && speed > 10) loc.bearing else null
        if (customCache.first != settings.customLimits) customCache = settings.customLimits to sk.cartracker.data.SpeedLimits.parseCustom(settings.customLimits)
        customSign = sk.cartracker.data.SpeedLimits.custom(customCache.second, loc.latitude, loc.longitude, bearing)
        if (settings.useRoadSigns) {
            sk.cartracker.data.SpeedLimits.prefetch(loc.latitude, loc.longitude, bearing)
            val m = sk.cartracker.data.SpeedLimits.match(loc.latitude, loc.longitude, bearing)
            val prev = mapSign
            mapSign = m?.limit
            if (m != null && mapSign != prev && mapSign != null)
                DiagLog.log(this, DiagLog.GPS, "Limit z mapy: $mapSign km/h" + (m.way.tags["name"] ?: m.way.tags["ref"]?.let { "cesta č. $it" })?.let { " · $it" }.orEmpty())
        } else mapSign = null
    }

    private fun currentZone(): RoadZone? = TrackingState.zoneOverride.value ?: roadInfo?.zone

    /** Aktuálny limit; null = typ cesty zatiaľ neznámy (bez internetu) → prekročenie sa nehodnotí. */
    private fun currentLimit(): Int? {
        if (!settings.roadLimits) return settings.speedAlertKmh
        customSign?.let { return it }                       // moje limity majú prednosť
        val manual = TrackingState.zoneOverride.value != null
        val sign = if (manual) null else (mapSign ?: roadInfo?.signLimit)
        val zone = currentZone() ?: when {                  // typ cesty ešte neznámy, ale značka z mapy áno
            sign == null -> return null
            sign <= 50 -> RoadZone.URBAN
            sign <= 90 -> RoadZone.RURAL
            else -> RoadZone.HIGHWAY
        }
        return speedLimitFor(zone, sign, vehicle, settings)
    }

    /** Opakuje upozornenie aj medzi bodmi GPS (ak prídu redšie ako interval), kým trvá prekročenie. */
    private fun startSpeedAlertTicker() {
        speedAlertJob = lifecycleScope.launch {
            while (speedAlerter.active && trip != null) {
                kotlinx.coroutines.delay(500)
                val now = System.currentTimeMillis()
                if (now - lastFixAt > 15_000) continue            // bez čerstvej polohy neopakovať
                if (settings.speedAlert && speedAlerter.onTick(now))
                    Notifications.speedAlert(this@TrackingService, lastSpeedKmh, lastLimitKmh, currentZone()?.label, settings, repeat = true)
            }
        }
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
        // nepresný bod alebo neznámy limit nič nemení (nepreruší ani nezačne prekročenie)
        val known = limit != null && loc.accuracy <= 30f
        val wall = System.currentTimeMillis()
        speedAlerter.intervalMs = settings.speedAlertRepeatSec.coerceAtLeast(0) * 1000L
        val r = speedAlerter.onFix(if (known) speeding else null, wall)
        if (known) { lastSpeedKmh = speedKmh.toInt(); lastLimitKmh = limit ?: 0; lastFixAt = wall }
        if (r.newEpisode) event = DriveEvent.SPEEDING
        if (r.alert && settings.speedAlert) Notifications.speedAlert(this, lastSpeedKmh, lastLimitKmh, currentZone()?.label, settings, repeat = !r.newEpisode)
        if (speedAlerter.active && settings.speedAlert && speedAlertJob?.isActive != true) startSpeedAlertTicker()
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
                if (now - candidateSince > settings.confirmTimeoutMin.coerceAtLeast(1) * 60_000L * factor)
                    discard("za ${settings.confirmTimeoutMin.coerceAtLeast(1) * factor} min sa nenamerali ${fixesNeeded()} body ≥ ${settings.minDriveSpeedKmh} km/h (max. ${candidateMax.toInt()} km/h)")
            }
            DriveState.DRIVING -> {
                if (!autoEndAllowed()) { updateNotification(); return }
                // jazda spustená jednou podmienkou, ale auto sa nepohlo → potichu zahodiť (falošné hlásenie systému)
                if (announcePending && now - lastMovingAt >= settings.stopDelayMin.coerceAtLeast(1) * 60_000L) {
                    DiagLog.log(this, DiagLog.CHECK, "Auto sa za ${settings.stopDelayMin.coerceAtLeast(1)} min nepohlo – nebola to jazda, zahodené")
                    silentDiscardTrip(); return
                }
                val exit = exitSignalAt
                when {
                    exit != null && now - exit >= EXIT_CONFIRM_MS && lastSpeed < 15 -> {
                        DiagLog.log(this, DiagLog.DRIVE, "Koniec jazdy – vystúpenie z vozidla a státie"); finishTrip(lastMovingAt)
                    }
                    now - lastMovingAt >= settings.stopDelayMin.coerceAtLeast(1) * 60_000L -> {
                        DiagLog.log(this, DiagLog.DRIVE, "Koniec jazdy – státie ${settings.stopDelayMin.coerceAtLeast(1)} min"); finishTrip(lastMovingAt)
                    }
                    else -> updateNotification()
                }
            }
            DriveState.WALKING -> {
                publishLive(null)
                if (manual) { updateNotification(); return }
                when {
                    now - lastMovingAt >= settings.walkStopMin.coerceAtLeast(1) * 60_000L -> {
                        DiagLog.log(this, DiagLog.WALK, "Koniec chôdze – ${settings.walkStopMin.coerceAtLeast(1)} min bez krokov"); finishTrip(lastMovingAt)
                    }
                    settings.walkWorkHoursOnly && !settings.walkActiveNow(now) -> {
                        DiagLog.log(this, DiagLog.WALK, "Koniec chôdze – skončil pracovný čas"); finishTrip(now)
                    }
                    else -> updateNotification()
                }
            }
            DriveState.IDLE -> Unit
        }
    }

    /** Automatické ukončenie: pri automatickej jazde vždy, pri ručnej ak je to zapnuté a auto sa už pohlo. */
    private fun autoEndAllowed() = !manual || (settings.manualAutoEnd && hasMoved && state == DriveState.DRIVING)

    /** Zmaže práve zaznamenávanú jazdu bez notifikácie a zvuku. */
    private suspend fun silentDiscardTrip() {
        stopLocation()
        trip?.let { dao.deleteTrip(it.id) }
        trip = null; announcePending = false
        shutdown()
    }

    /**
     * Uloží jazdu/chôdzu. [then] = čo spustiť hneď potom (napr. chôdza po jazde);
     * bez neho sa služba ukončí.
     */
    private suspend fun finishTrip(endedAt0: Long, nextType: String? = null, then: (suspend () -> Unit)? = null) {
        val stepsAfterStop = if (trip?.isWalk == false) carStepsSinceStop() else 0
        stopLocation()
        stopSteps()
        stopCarSteps()
        val t0 = trip ?: run { if (then != null) then() else shutdown(); return }
        trip = null
        handover = null
        var endedAt = endedAt0
        var pts = dao.points(t0.id)
        // Chôdza → auto: koniec chôdze je posledný pomalý bod pred rozjazdom (úsek rýchlosťou auta patrí jazde)
        if (t0.isWalk && nextType == TYPE_CAR) {
            var i = pts.lastIndex
            while (i >= 0 && (pts[i].speedKmh ?: 0f) >= 8f) i--
            if (i in 0 until pts.lastIndex) endedAt = min(endedAt, pts[i].ts)
            endedAt = endedAt.coerceAtLeast(t0.startedAt)
        }
        // Body po konci chôdze (nastupovanie, rozjazd) odovzdať jazde hneď – nech nepatria chôdzi
        if (t0.isWalk && nextType == TYPE_CAR && pts.any { it.ts > endedAt }) {
            val endP = pts.lastOrNull { it.ts <= endedAt }
            makeHandover(t0, endedAt, endP?.lat, endP?.lng, 0)
            pts = pts.filter { it.ts <= endedAt }
        } else if (t0.isWalk && nextType == TYPE_CAR) {
            val endP = pts.lastOrNull()
            makeHandover(t0, endedAt, endP?.lat, endP?.lng, 0)
        }
        // Začiatok a koniec z najpresnejších bodov (prvý bod býva nepresný) – podľa nich adresa, miesta a zlučovanie
        val bestStart = Geo.bestStart(pts)
        val last = Geo.bestEnd(pts) ?: dao.lastPoint(t0.id)
        var t = if (bestStart != null) t0.copy(startLat = bestStart.lat, startLng = bestStart.lng) else t0
        // Začiatok jazdy: systém rozpozná jazdu autom až po chvíli → prvé metre chýbajú. Auto stálo tam,
        // kde skončila jeho posledná jazda – ak je to do 3 km od prvého bodu, úsek sa doplní (priamka).
        var startFill = 0.0
        var parkedAddr: String? = null
        if (!t.isWalk) {
            val prev = dao.previousTrip(userId, TYPE_CAR, t0.startedAt)
            val first = pts.firstOrNull { (it.accuracyM ?: 99f) <= 35f }
            if (prev != null && first != null && prev.endLat != null && prev.endLng != null &&
                prev.vehicleUuid == t.vehicleUuid && (prev.endedAt ?: 0) <= t0.startedAt) {
                val r = FloatArray(1)
                Location.distanceBetween(prev.endLat, prev.endLng, first.lat, first.lng, r)
                if (r[0] in 30f..3000f) {
                    startFill = r[0].toDouble()
                    parkedAddr = prev.endAddress
                    t = t.copy(startLat = prev.endLat, startLng = prev.endLng)
                    DiagLog.log(this, DiagLog.DRIVE, "Začiatok jazdy doplnený od miesta parkovania: +${r[0].toInt()} m")
                }
            }
        }
        // auto: oprava podľa tachometra (kalibrácia z tankovaní), chôdza: kroky alebo GPS
        val dist = if (t.isWalk) walkDistance() else (distance + startFill) * settings.odoFactor(t.vehicleUuid)
        if (t.isWalk) calibrateStride(steps, distance)
        // Auto: koniec = posledný pohyb (body zo státia po zaparkovaní sa odstránia), nie čas ukončenia
        val lastMove = if (t.isWalk) null else pts.lastOrNull { (it.speedKmh ?: 0f) >= MOVING_KMH && (it.accuracyM ?: 99f) <= 40f }?.ts
        val end = (if (t.isWalk) endedAt else max(endedAt, lastMove ?: (last?.ts ?: endedAt)).coerceAtMost(last?.ts ?: endedAt)).coerceAtLeast(t.startedAt)
        // Auto → chôdza: po zaparkovaní pokračuje chôdza od auta (ručne, systém hlási chôdzu, alebo kroky od zastavenia)
        val walkFollows = !t.isWalk && (nextType == TYPE_WALK ||
            (then == null && settings.autoDetection && settings.walkActiveNow() && (pendingWalk || stepsAfterStop >= 30)))
        val carEnd = if (t.isWalk) last else pts.lastOrNull { it.ts <= end + 30_000L && (it.accuracyM ?: 99f) <= 40f } ?: last
        if (walkFollows) {
            makeHandover(t0, end + 30_000L, carEnd?.lat, carEnd?.lng, stepsAfterStop, at = end)
            if (stepsAfterStop > 0) DiagLog.log(this, DiagLog.WALK, "Od zaparkovania $stepsAfterStop krokov – chôdza začne pri aute")
        } else if (!t.isWalk && last != null && end < last.ts - 60_000L) {
            dao.deletePointsAfter(t0.id, end + 30_000L)
            DiagLog.log(this, DiagLog.DRIVE, "Koniec jazdy podľa posledného pohybu: ${sk.cartracker.ui.formatTime(end)} (státie ${formatDuration(last.ts - end)} odstránené)")
        }
        val hours = (end - t.startedAt) / 3_600_000.0
        val avg = if (hours > 0) dist / 1000.0 / hours else 0.0
        val minDist = if (t.isWalk) settings.walkMinDistanceM else settings.minTripDistanceM

        // Krátka prestávka po predchádzajúcej jazde (pumpa, obchod) → automaticky sa zlúčia
        val mergeWith = mergeCandidate(t)

        val summary: String?
        var classify: Notifications.Classify? = null
        if (!manual && dist < minDist && mergeWith == null) {
            dao.deleteTrip(t.id)
            summary = if (t.isWalk) null else "Jazda bola kratšia ako $minDist m – neuložená"
        } else {
            val startAddr = if (!settings.detectAddresses) null else parkedAddr ?: t.startLat?.let { la -> t.startLng?.let { lo -> Geo.address(this, la, lo) } }
            val endAddr = if (settings.detectAddresses && carEnd != null) Geo.address(this, carEnd.lat, carEnd.lng) else null
            var saved = if (t.isWalk) {
                t.copy(
                    endedAt = end, distanceM = dist, avgSpeedKmh = avg, steps = steps,
                    endLat = last?.lat, endLng = last?.lng, finished = true,
                    startAddress = startAddr, endAddress = endAddr,
                )
            } else {
                val speedingSec = (speedingMs / 1000).toInt()
                t.copy(
                    endedAt = end, distanceM = dist, maxSpeedKmh = maxSpeed, avgSpeedKmh = avg,
                    endLat = carEnd?.lat, endLng = carEnd?.lng, finished = true,
                    startAddress = startAddr, endAddress = endAddr,
                    score = if (settings.drivingEvents) DrivingScore.compute(dist, end - t.startedAt, hardBrakes, hardAccels, speedingSec) else null,
                    hardBrakes = hardBrakes, hardAccels = hardAccels, speedingSec = speedingSec,
                )
            }
            // Miesta (Domov, Práca…): názvy, typ jazdy a účel
            val places = container.places.list()
            saved = container.places.applyToTrip(saved, places, settings, setCategory = true)
            // Účel: naučené trasy → miesta → pravidlá času → predvolený
            var reason: String? = null
            if (!t.isWalk) {
                val d = TripClassifier.decide(saved, places, { la, lo -> container.places.match(places, la, lo) }, settings)
                saved = saved.copy(category = d.category)
                reason = d.reason
                DiagLog.log(this, DiagLog.DRIVE, "Účel jazdy: ${Notifications.categoryLabel(d.category)} (${d.reason})")
            }
            dao.updateTrip(saved)
            var merged = false
            if (mergeWith != null) {
                runCatching { container.trips.merge(listOf(mergeWith, saved)) }
                    .onSuccess { id -> dao.getTrip(id)?.let { saved = it; merged = true } }
                    .onFailure { Log.e(TAG, "Automatické zlúčenie zlyhalo", it) }
            }
            if (!t.isWalk) classify = Notifications.Classify(saved.uuid, saved.category, reason ?: "")
            val total = (saved.endedAt ?: end) - saved.startedAt
            val prefix = if (merged) "Zlúčené s predchádzajúcou · spolu " else ""
            if (t.isWalk) {
                summary = prefix + "${String.format(Locale("sk"), "%,d", saved.steps)} krokov · ${formatDistance(saved.distanceM)} · ${formatDuration(total)}"
            } else {
                if (carEnd != null) RuntimeState.setParking(this, carEnd.lat, carEnd.lng, end, endAddr)
                val vehicle = container.vehicles.byUuid(saved.vehicleUuid)
                summary = buildString {
                    append(prefix)
                    append("${formatDistance(saved.distanceM)} · ${formatDuration(total)} · Ø ${saved.avgSpeedKmh.toInt()} km/h")
                    append(" · ~").append(String.format(Locale("sk"), "%.2f €", saved.cost(vehicle, settings)))
                    saved.score?.let { append(" · štýl $it/100") }
                    vehicle?.let { append("\n").append(it.label) }
                }
            }
            if (settings.autoSync) SyncWorker.enqueueNow(this, settings.syncWifiOnly)
        }
        if (t.isWalk) Notifications.walkEvent(this, started = false, s = settings, summary = summary)
        else if (!announcePending) Notifications.driveEvent(this, started = false, s = settings, summary = summary, classify = classify)
        announcePending = false
        Log.i(TAG, "Záznam ukončený: $summary")
        DiagLog.log(this, if (t.isWalk) DiagLog.WALK else DiagLog.DRIVE,
            (if (t.isWalk) "Chôdza ukončená" else "Režim jazdy VYPNUTÝ") + ": " + (summary ?: "kratšie ako minimum (${minDist} m) – neuložené"))

        val walkNext: suspend () -> Unit = { beginWalk(false) }
        val next = then ?: (if (walkFollows) walkNext else null)
        pendingWalk = false
        if (next != null) next() else shutdown()
    }

    /** Body záznamu [t] po čase [after] presunie do dočasného záznamu, ktorý prevezme nasledujúca jazda / chôdza. */
    private suspend fun makeHandover(t: TripEntity, after: Long, lat: Double?, lng: Double?, steps: Int, at: Long = after) {
        val holder = TripEntity(uuid = UUID.randomUUID().toString(), userId = userId, startedAt = at, triggerSource = SOURCE_HANDOVER,
            type = if (t.isWalk) TYPE_CAR else TYPE_WALK, category = CATEGORY_PRIVATE)
        val id = dao.insertTrip(holder)
        dao.movePointsFrom(t.id, id, after + 1)
        handover = Handover(id, holder.type, at, lat, lng, steps, System.currentTimeMillis())
    }

    /**
     * Kalibrácia dĺžky kroku: chôdza vonku s dobrým GPS (aspoň 300 m a 300 krokov, 40–120 cm na krok)
     * sa pripočíta k nazbieraným údajom. Pri zapnutej automatike sa dĺžka kroku hneď upraví.
     */
    private suspend fun calibrateStride(stepCount: Int, gpsMeters: Double) {
        if (stepCount < 300 || gpsMeters < 300) return
        val perStep = gpsMeters / stepCount
        // GPS pri chôdzi (interval 10 s, mesto, budovy) trasu skracuje – nereálne krátke kroky by stiahli dĺžku kroku a s ňou všetky km
        if (perStep * 100 !in StrideFix.MIN_CM.toDouble()..StrideFix.MAX_CM.toDouble()) {
            DiagLog.log(this, DiagLog.WALK, "Kalibrácia kroku: chôdza vynechaná (${(perStep * 100).toInt()} cm/krok – GPS nameralo kratšiu trasu, reálne je ${StrideFix.MIN_CM}–${StrideFix.MAX_CM} cm)"); return }
        container.settings.update { s ->
            val n = s.copy(strideCalMeters = s.strideCalMeters + gpsMeters.toInt(), strideCalSteps = s.strideCalSteps + stepCount,
                strideCalWalks = s.strideCalWalks + 1)
            val est = n.strideFromGpsCm
            if (n.strideAuto && est != null) n.copy(strideCm = est) else n
        }
        DiagLog.log(this, DiagLog.WALK, "Kalibrácia kroku: ${gpsMeters.toInt()} m / $stepCount krokov = ${(perStep * 100).toInt()} cm/krok")
    }

    /** Predchádzajúci záznam rovnakého typu, s ktorým sa má tento automaticky zlúčiť. */
    private suspend fun mergeCandidate(t: TripEntity): TripEntity? {
        if (!settings.autoMerge) return null
        val prev = dao.previousTrip(userId, t.type, t.startedAt) ?: return null
        val prevEnd = prev.endedAt ?: return null
        val gap = t.startedAt - prevEnd
        val maxGap = (if (t.isWalk) settings.walkMergeGapMin else settings.autoMergeGapMin) * 60_000L
        if (gap < 0 || gap > maxGap) return null
        // iné auto = iná jazda
        if (!t.isWalk && prev.vehicleUuid != null && t.vehicleUuid != null && prev.vehicleUuid != t.vehicleUuid) return null
        val a = prev.endLat; val b = prev.endLng; val la = t.startLat; val lo = t.startLng
        if (a != null && b != null && la != null && lo != null) {
            val d = FloatArray(1)
            Location.distanceBetween(a, b, la, lo, d)
            if (d[0] > settings.autoMergeMaxDistanceM) return null
        }
        return prev
    }

    private suspend fun discard(reason: String = "", then: (suspend () -> Unit)? = null) {
        stopLocation()
        candidateBuffer.clear()
        Log.i(TAG, "Kandidát zahodený – nejde o jazdu autom")
        DiagLog.log(this, DiagLog.CHECK, "Nie je to jazda autom – zahodené" + if (reason.isNotEmpty()) ": $reason" else "")
        if (then != null) then() else shutdown()
    }

    /** Uzavrie záznamy, ktoré ostali otvorené (pád appky, vybitá batéria). */
    private suspend fun closeStaleTrips() {
        for (t in dao.unfinishedTrips(userId)) {
            if (t.id == trip?.id || t.id == handover?.holder) continue
            if (t.triggerSource == SOURCE_HANDOVER) { dao.deleteTrip(t.id); continue }
            val last = dao.lastPoint(t.id)
            val minDist = if (t.isWalk) settings.walkMinDistanceM else settings.minTripDistanceM
            if (last == null || t.distanceM < minDist) dao.deleteTrip(t.id)
            else dao.updateTrip(t.copy(endedAt = last.ts, endLat = last.lat, endLng = last.lng, finished = true))
        }
    }

    private fun shutdown() {
        TrackingState.state.value = DriveState.IDLE
        TrackingState.live.value = null
        CarWidget.updateAll(this)
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
                feedDaily(v)
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

    private fun locationRequest(): LocationRequest {
        val walking = state == DriveState.WALKING
        val base = (if (walking) settings.walkGpsIntervalSec else settings.gpsIntervalSec).coerceAtLeast(1)
        val sec = when (gpsMode) {
            GpsMode.NORMAL -> base
            GpsMode.HIGHWAY -> (base * 2).coerceAtMost(6).coerceAtLeast(base)
            GpsMode.STOPPED -> maxOf(base, 10)
        }
        val priority = when {
            walking || gpsMode == GpsMode.STOPPED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            settings.highAccuracy -> Priority.PRIORITY_HIGH_ACCURACY
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val intervalMs = sec * 1000L
        return LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationActive) return
        gpsMode = GpsMode.NORMAL; fastSince = 0L; slowSince = 0L
        try {
            fused.requestLocationUpdates(locationRequest(), callback, Looper.getMainLooper())
            locationActive = true
            gpsOnAt = System.currentTimeMillis()
            BatteryStats.level(this).let { (pct, ch) -> battStart = pct; battCharging = ch }
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
        val (pct, ch) = BatteryStats.level(this)
        val now = System.currentTimeMillis()
        runCatching { BatteryStats.record(this, gpsOnAt, now, battStart, pct, battCharging || ch) }
        if (battStart in 0..100 && pct in 0..100 && now - gpsOnAt > 5 * 60_000L)
            DiagLog.log(this, DiagLog.GPS, "Batéria počas sledovania: $battStart % → $pct %" +
                (if (battCharging || ch) " (nabíjanie – nezarátané)" else " za ${formatDuration(now - gpsOnAt)}"))
    }

    /**
     * Úspora batérie počas jazdy: po minúte nad 90 km/h redšie body (diaľnica), po 45 s státia
     * úsporný režim (10 s, nižšia presnosť). Pri rozbehu sa hneď vráti normálny interval.
     */
    @SuppressLint("MissingPermission")
    private fun adaptGps(speed: Double, now: Long) {
        if (!settings.adaptiveGps || !locationActive) return
        if (speed >= 90) { if (fastSince == 0L) fastSince = now } else fastSince = 0L
        if (speed < 5) { if (slowSince == 0L) slowSince = now } else slowSince = 0L
        val want = when {
            speed >= MOVING_KMH && gpsMode == GpsMode.STOPPED -> GpsMode.NORMAL
            fastSince > 0 && now - fastSince >= 60_000 -> GpsMode.HIGHWAY
            slowSince > 0 && now - slowSince >= 45_000 -> GpsMode.STOPPED
            gpsMode == GpsMode.HIGHWAY && speed < 75 -> GpsMode.NORMAL
            else -> gpsMode
        }
        if (want == gpsMode) return
        gpsMode = want
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(locationRequest(), callback, Looper.getMainLooper())
            DiagLog.log(this, DiagLog.GPS, "GPS režim: ${want.label}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Chýba povolenie polohy", e)
        }
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
        if (force) CarWidget.updateAll(this)
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
        CarWidget.updateAll(this, throttle = true)
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
