package sk.cartracker.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

/** Server musí bežať na HTTPS – adresa sa zadáva pri prihlásení (Server: …) alebo v Nastaveniach. */
const val DEFAULT_SERVER_URL = "https://"

enum class VibrationPattern(val label: String) { SHORT("Krátka"), LONG("Dlhá"), DOUBLE("Dvojitá") }
enum class ThemeMode(val label: String) { SYSTEM("Podľa systému"), LIGHT("Svetlý"), DARK("Tmavý") }
/** Mapové podklady – iba OpenStreetMap a OpenTopoMap (Esri bolo odstránené). STREETS/DETAILED/SATELLITE ostávajú
 *  len kvôli starším uloženým nastaveniam a zobrazia sa ako svetlá mapa. */
enum class MapStyle(val label: String, val shown: Boolean = true) {
    AUTO("Podľa témy aplikácie"), LIGHT("OpenStreetMap – svetlá"), DARK("OpenStreetMap – tmavá"),
    STREETS("OpenStreetMap", false), DETAILED("OpenStreetMap", false), TOPO("Turistická (OpenTopoMap)"), SATELLITE("OpenStreetMap", false)
}

data class AppSettings(
    // Automatická detekcia jazdy autom
    val autoDetection: Boolean = true,
    val startOnBoot: Boolean = true,
    val minDriveSpeedKmh: Int = 25,      // rýchlosť, ktorá potvrdí, že ide o auto (nie chôdza/bicykel)
    val confirmTimeoutMin: Int = 5,      // ak sa do X min nepotvrdí rýchlosť, detekcia sa zahodí
    val stopDelayMin: Int = 4,           // koľko minút státia ukončí jazdu
    val minTripDistanceM: Int = 500,     // kratšie jazdy sa neukladajú
    // GPS
    val gpsIntervalSec: Int = 3,
    val highAccuracy: Boolean = true,
    // Notifikácie a vibrácie
    val notifyOnStart: Boolean = true,
    val notifyOnStop: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val vibrationPattern: VibrationPattern = VibrationPattern.DOUBLE,
    val showSpeedInNotification: Boolean = true,
    // Bluetooth auta (voliteľné)
    val bluetoothTrigger: Boolean = false,
    val bluetoothRequired: Boolean = false,
    val carBtAddress: String = "",
    val carBtName: String = "",
    // Synchronizácia
    val autoSync: Boolean = true,
    val syncWifiOnly: Boolean = false,
    val serverUrl: String = DEFAULT_SERVER_URL,
    // Zabezpečenie / vzhľad
    val biometricEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Kniha jázd
    val defaultCategory: String = "private",
    val carName: String = "",
    val licensePlate: String = "",
    val odometerKm: Int = 0,          // stav tachometra…
    val odometerDate: Long = 0L,      // …k tomuto dátumu
    val detectAddresses: Boolean = true,
    // Palivo
    val fuelConsumptionX10: Int = 65, // 6,5 l/100 km
    val fuelPriceCents: Int = 160,    // 1,60 €/l
    // Štýl jazdy
    val drivingEvents: Boolean = true,
    val speedAlert: Boolean = false,
    val speedAlertKmh: Int = 130,         // jednotný limit, ak je rozpoznávanie typu cesty vypnuté
    val speedAlertRepeatSec: Int = 3,
    /** Spustenie jazdy: true = stačí jedna podmienka (systém „vo vozidle“, Bluetooth auta alebo rýchlosť GPS), false = overiť 3 bodmi GPS. */
    val detectAnyCondition: Boolean = true,     // opakovanie upozornenia, kým je limit prekročený (0 = len raz)
    val roadLimits: Boolean = true,       // limit podľa typu cesty (obec / mimo obce / diaľnica)
    val useRoadSigns: Boolean = true,     // použiť limit zo značky v mape, ak je zadaný
    val speedToleranceKmh: Int = 5,
    // Mapa
    val mapStyle: MapStyle = MapStyle.AUTO,
    // Vozidlá
    val activeVehicleUuid: String = "",
    // Chôdza
    val walkTracking: Boolean = true,
    val walkWorkHoursOnly: Boolean = false,
    val workDays: String = "1,2,3,4,5",   // 1 = pondelok … 7 = nedeľa
    val workFromMin: Int = 7 * 60,
    val workToMin: Int = 16 * 60,
    val strideCm: Int = 75,
    /** Vzdialenosť chôdze: false = z krokov (aj v budove), true = z GPS (presnejšie vonku). */
    val walkDistanceGps: Boolean = false,
    val walkStopMin: Int = 3,
    val walkMinDistanceM: Int = 150,
    val walkGpsIntervalSec: Int = 10,
    val walkNotify: Boolean = false,
    val walkVibrate: Boolean = false,
    val dailyStepGoal: Int = 8000,
    // Mesačné zhrnutie, offline mapy
    val monthlySummary: Boolean = true,
    val offlineMaps: Boolean = false,      // mapy iba z uložených dlaždíc (bez internetu)
    val mapCacheMb: Int = 500,
    // Diagnostika a kalibrácia
    val diagnostics: Boolean = true,
    /** Opravný koeficient km podľa tachometra pre vozidlá: "uuid=1.0340;uuid2=0.9910" */
    val odoFactors: String = "",
    /** Automaticky upravovať dĺžku kroku podľa chôdze vonku s GPS. */
    val strideAuto: Boolean = false,
    /** Nazbierané údaje pre kalibráciu kroku: metre GPS a kroky z vhodných chôdzí. */
    val strideCalMeters: Int = 0,
    val strideCalSteps: Int = 0,
    val strideCalWalks: Int = 0,
    // Automatické zlučovanie (zastávka na pumpe, v obchode…)
    val autoMerge: Boolean = true,
    val autoMergeGapMin: Int = 10,        // auto: max. dĺžka prestávky
    val walkMergeGapMin: Int = 5,         // chôdza: max. dĺžka prestávky
    val autoMergeMaxDistanceM: Int = 1000, // koniec predchádzajúcej ↔ začiatok novej
    // Účel jazdy: otázka v notifikácii, učenie a pravidlá
    val askCategory: Boolean = true,
    val learnCategory: Boolean = true,
    val ruleWorkTime: Boolean = false,      // v pracovnom čase → služobná
    val ruleWeekendPrivate: Boolean = false, // v deň voľna → súkromná
    /** Naučené trasy: "lat,lng,lat,lng,služobné,súkromné;…" */
    val learnedRoutes: String = "",
    // Úspora batérie a kontrola jázd
    val adaptiveGps: Boolean = true,
    val tripCheck: Boolean = true,
    /** Moje limity – opravy používateľa: "lat,lng,limit[,smer];…" */
    val customLimits: String = "",
    // 2.0 – šablóny účelu jazdy (oddelené novým riadkom), preriedenie starých bodov (0 = nikdy), kontrola aktualizácií
    val purposeTemplates: String = "Návšteva klienta\nNákup materiálu\nPorada\nServis vozidla\nPošta / úrad",
    val thinAfterMonths: Int = 0,
    val updateCheck: Boolean = true,
    // Zvuky udalostí (id zo sounds.json, "none", "system" alebo "uri:…")
    val soundDriveStart: String = "nahor",
    val soundDriveEnd: String = "nadol",
    val soundWalkStart: String = "none",
    val soundWalkEnd: String = "none",
    val soundSpeeding: String = "varovanie",
    val soundParking: String = "dingdong",
    val soundVolume: Int = 80,
    /** Prehrať aj v tichom režime / pri vibráciách (cez kanál budíka). */
    val soundIgnoreSilent: Boolean = true,
) {
    val workDaySet: Set<Int> get() = workDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** Je teraz čas, keď sa má chôdza sledovať? */
    fun walkActiveNow(now: Long = System.currentTimeMillis()): Boolean {
        if (!walkTracking) return false
        if (!walkWorkHoursOnly) return true
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val day = ((cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Po = 1
        val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return day in workDaySet && min in workFromMin until workToMin
    }

    val fuelConsumption get() = fuelConsumptionX10 / 10.0
    val fuelPrice get() = fuelPriceCents / 100.0
    fun fuelCost(distanceM: Double) = distanceM / 1000.0 / 100.0 * fuelConsumption * fuelPrice
    /** Opravný koeficient km pre vozidlo (1.0 = bez opravy). */
    fun odoFactor(vehicleUuid: String?): Double = vehicleUuid?.let { u ->
        odoFactors.split(';').firstOrNull { it.startsWith("$u=") }?.substringAfter('=')?.toDoubleOrNull()
    }?.takeIf { it in 0.5..1.5 } ?: 1.0

    fun withOdoFactor(vehicleUuid: String, factor: Double?): String {
        val rest = odoFactors.split(';').filter { it.isNotBlank() && !it.startsWith("$vehicleUuid=") }
        return (rest + listOfNotNull(factor?.let { "$vehicleUuid=" + String.format(java.util.Locale.US, "%.4f", it) })).joinToString(";")
    }

    /** Dĺžka kroku vypočítaná z GPS (cm) alebo null, ak je málo údajov. */
    val strideFromGpsCm: Int? get() = if (strideCalWalks >= 3 && strideCalSteps > 500) (strideCalMeters * 100.0 / strideCalSteps).toInt().takeIf { it in 30..150 } else null

    fun fuelLiters(distanceM: Double) = distanceM / 1000.0 / 100.0 * fuelConsumption
}

class SettingsRepository(private val context: Context) {

    private object K {
        val AUTO = booleanPreferencesKey("auto_detection")
        val BOOT = booleanPreferencesKey("start_on_boot")
        val MIN_SPEED = intPreferencesKey("min_drive_speed")
        val CONFIRM = intPreferencesKey("confirm_timeout")
        val STOP_DELAY = intPreferencesKey("stop_delay")
        val MIN_DIST = intPreferencesKey("min_trip_distance")
        val GPS_INT = intPreferencesKey("gps_interval")
        val HIGH_ACC = booleanPreferencesKey("high_accuracy")
        val N_START = booleanPreferencesKey("notify_start")
        val N_STOP = booleanPreferencesKey("notify_stop")
        val SOUND = booleanPreferencesKey("sound")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val VIB_PATTERN = stringPreferencesKey("vibration_pattern")
        val SPEED_NOTIF = booleanPreferencesKey("speed_in_notification")
        val BT_TRIGGER = booleanPreferencesKey("bt_trigger")
        val BT_REQUIRED = booleanPreferencesKey("bt_required")
        val BT_ADDR = stringPreferencesKey("bt_address")
        val BT_NAME = stringPreferencesKey("bt_name")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SERVER = stringPreferencesKey("server_url")
        val BIO = booleanPreferencesKey("biometric")
        val THEME = stringPreferencesKey("theme")
        val CATEGORY = stringPreferencesKey("default_category")
        val CAR_NAME = stringPreferencesKey("car_name")
        val PLATE = stringPreferencesKey("license_plate")
        val ODO = intPreferencesKey("odometer_km")
        val ODO_DATE = longPreferencesKey("odometer_date")
        val ADDRESSES = booleanPreferencesKey("detect_addresses")
        val FUEL_CONS = intPreferencesKey("fuel_consumption_x10")
        val FUEL_PRICE = intPreferencesKey("fuel_price_cents")
        val EVENTS = booleanPreferencesKey("driving_events")
        val SPEED_ALERT = booleanPreferencesKey("speed_alert")
        val SPEED_ALERT_KMH = intPreferencesKey("speed_alert_kmh")
        val SPEED_ALERT_REPEAT = intPreferencesKey("speed_alert_repeat_sec")
        val DETECT_ANY = booleanPreferencesKey("detect_any_condition")
        val ROAD_LIMITS = booleanPreferencesKey("road_limits")
        val ROAD_SIGNS = booleanPreferencesKey("road_signs")
        val TOLERANCE = intPreferencesKey("speed_tolerance")
        val MAP_STYLE = stringPreferencesKey("map_style")
        val ACTIVE_VEHICLE = stringPreferencesKey("active_vehicle")
        val WALK = booleanPreferencesKey("walk_tracking")
        val WALK_WORK = booleanPreferencesKey("walk_work_hours_only")
        val WORK_DAYS = stringPreferencesKey("work_days")
        val WORK_FROM = intPreferencesKey("work_from_min")
        val WORK_TO = intPreferencesKey("work_to_min")
        val STRIDE = intPreferencesKey("stride_cm")
        val WALK_DIST_GPS = booleanPreferencesKey("walk_distance_gps")
        val WALK_STOP = intPreferencesKey("walk_stop_min")
        val WALK_MIN = intPreferencesKey("walk_min_distance")
        val WALK_GPS = intPreferencesKey("walk_gps_interval")
        val WALK_NOTIFY = booleanPreferencesKey("walk_notify")
        val WALK_VIBRATE = booleanPreferencesKey("walk_vibrate")
        val STEP_GOAL = intPreferencesKey("daily_step_goal")
        val MONTHLY = booleanPreferencesKey("monthly_summary")
        val OFFLINE_MAPS = booleanPreferencesKey("offline_maps")
        val MAP_CACHE = intPreferencesKey("map_cache_mb")
        val DIAG = booleanPreferencesKey("diagnostics")
        val ODO_FACTORS = stringPreferencesKey("odo_factors")
        val STRIDE_AUTO = booleanPreferencesKey("stride_auto")
        val STRIDE_CAL_M = intPreferencesKey("stride_cal_m")
        val STRIDE_CAL_S = intPreferencesKey("stride_cal_steps")
        val STRIDE_CAL_W = intPreferencesKey("stride_cal_walks")
        val AUTO_MERGE = booleanPreferencesKey("auto_merge")
        val MERGE_GAP = intPreferencesKey("auto_merge_gap")
        val WALK_MERGE_GAP = intPreferencesKey("walk_merge_gap")
        val MERGE_DIST = intPreferencesKey("auto_merge_distance")
        val ASK_CAT = booleanPreferencesKey("ask_category")
        val LEARN_CAT = booleanPreferencesKey("learn_category")
        val RULE_WORK = booleanPreferencesKey("rule_work_time")
        val RULE_WEEKEND = booleanPreferencesKey("rule_weekend_private")
        val LEARNED = stringPreferencesKey("learned_routes")
        val ADAPTIVE_GPS = booleanPreferencesKey("adaptive_gps")
        val TRIP_CHECK = booleanPreferencesKey("trip_check")
        val CUSTOM_LIMITS = stringPreferencesKey("custom_limits")
        val PURPOSES = stringPreferencesKey("purpose_templates")
        val THIN_MONTHS = intPreferencesKey("thin_after_months")
        val UPDATE_CHECK = booleanPreferencesKey("update_check")
        val SND_DS = stringPreferencesKey("sound_drive_start")
        val SND_DE = stringPreferencesKey("sound_drive_end")
        val SND_WS = stringPreferencesKey("sound_walk_start")
        val SND_WE = stringPreferencesKey("sound_walk_end")
        val SND_SP = stringPreferencesKey("sound_speeding")
        val SND_PK = stringPreferencesKey("sound_parking")
        val SND_VOL = intPreferencesKey("sound_volume")
        val SND_SILENT = booleanPreferencesKey("sound_ignore_silent")
    }

    val flow: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { read(it) }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
        // zmenené nastavenia sa po chvíli odošlú na server (obnovia sa na novom telefóne)
        runCatching { sk.cartracker.sync.SettingsPushWorker.enqueue(context) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoDetection = p[K.AUTO] ?: d.autoDetection,
            startOnBoot = p[K.BOOT] ?: d.startOnBoot,
            minDriveSpeedKmh = p[K.MIN_SPEED] ?: d.minDriveSpeedKmh,
            confirmTimeoutMin = p[K.CONFIRM] ?: d.confirmTimeoutMin,
            stopDelayMin = p[K.STOP_DELAY] ?: d.stopDelayMin,
            minTripDistanceM = p[K.MIN_DIST] ?: d.minTripDistanceM,
            gpsIntervalSec = p[K.GPS_INT] ?: d.gpsIntervalSec,
            highAccuracy = p[K.HIGH_ACC] ?: d.highAccuracy,
            notifyOnStart = p[K.N_START] ?: d.notifyOnStart,
            notifyOnStop = p[K.N_STOP] ?: d.notifyOnStop,
            sound = p[K.SOUND] ?: d.sound,
            vibrate = p[K.VIBRATE] ?: d.vibrate,
            vibrationPattern = p[K.VIB_PATTERN]?.let { runCatching { VibrationPattern.valueOf(it) }.getOrNull() } ?: d.vibrationPattern,
            showSpeedInNotification = p[K.SPEED_NOTIF] ?: d.showSpeedInNotification,
            bluetoothTrigger = p[K.BT_TRIGGER] ?: d.bluetoothTrigger,
            bluetoothRequired = p[K.BT_REQUIRED] ?: d.bluetoothRequired,
            carBtAddress = p[K.BT_ADDR] ?: d.carBtAddress,
            carBtName = p[K.BT_NAME] ?: d.carBtName,
            autoSync = p[K.AUTO_SYNC] ?: d.autoSync,
            syncWifiOnly = p[K.WIFI_ONLY] ?: d.syncWifiOnly,
            serverUrl = p[K.SERVER] ?: d.serverUrl,
            biometricEnabled = p[K.BIO] ?: d.biometricEnabled,
            themeMode = p[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            defaultCategory = p[K.CATEGORY] ?: d.defaultCategory,
            carName = p[K.CAR_NAME] ?: d.carName,
            licensePlate = p[K.PLATE] ?: d.licensePlate,
            odometerKm = p[K.ODO] ?: d.odometerKm,
            odometerDate = p[K.ODO_DATE] ?: d.odometerDate,
            detectAddresses = p[K.ADDRESSES] ?: d.detectAddresses,
            fuelConsumptionX10 = p[K.FUEL_CONS] ?: d.fuelConsumptionX10,
            fuelPriceCents = p[K.FUEL_PRICE] ?: d.fuelPriceCents,
            drivingEvents = p[K.EVENTS] ?: d.drivingEvents,
            speedAlert = p[K.SPEED_ALERT] ?: d.speedAlert,
            speedAlertKmh = p[K.SPEED_ALERT_KMH] ?: d.speedAlertKmh,
            speedAlertRepeatSec = p[K.SPEED_ALERT_REPEAT] ?: d.speedAlertRepeatSec,
            detectAnyCondition = p[K.DETECT_ANY] ?: d.detectAnyCondition,
            roadLimits = p[K.ROAD_LIMITS] ?: d.roadLimits,
            useRoadSigns = p[K.ROAD_SIGNS] ?: d.useRoadSigns,
            speedToleranceKmh = p[K.TOLERANCE] ?: d.speedToleranceKmh,
            mapStyle = p[K.MAP_STYLE]?.let { runCatching { MapStyle.valueOf(it) }.getOrNull() } ?: d.mapStyle,
            activeVehicleUuid = p[K.ACTIVE_VEHICLE] ?: d.activeVehicleUuid,
            walkTracking = p[K.WALK] ?: d.walkTracking,
            walkWorkHoursOnly = p[K.WALK_WORK] ?: d.walkWorkHoursOnly,
            workDays = p[K.WORK_DAYS] ?: d.workDays,
            workFromMin = p[K.WORK_FROM] ?: d.workFromMin,
            workToMin = p[K.WORK_TO] ?: d.workToMin,
            strideCm = p[K.STRIDE] ?: d.strideCm,
            walkDistanceGps = p[K.WALK_DIST_GPS] ?: d.walkDistanceGps,
            walkStopMin = p[K.WALK_STOP] ?: d.walkStopMin,
            walkMinDistanceM = p[K.WALK_MIN] ?: d.walkMinDistanceM,
            walkGpsIntervalSec = p[K.WALK_GPS] ?: d.walkGpsIntervalSec,
            walkNotify = p[K.WALK_NOTIFY] ?: d.walkNotify,
            walkVibrate = p[K.WALK_VIBRATE] ?: d.walkVibrate,
            dailyStepGoal = p[K.STEP_GOAL] ?: d.dailyStepGoal,
            monthlySummary = p[K.MONTHLY] ?: d.monthlySummary,
            offlineMaps = p[K.OFFLINE_MAPS] ?: d.offlineMaps,
            mapCacheMb = p[K.MAP_CACHE] ?: d.mapCacheMb,
            diagnostics = p[K.DIAG] ?: d.diagnostics,
            odoFactors = p[K.ODO_FACTORS] ?: d.odoFactors,
            strideAuto = p[K.STRIDE_AUTO] ?: d.strideAuto,
            strideCalMeters = p[K.STRIDE_CAL_M] ?: d.strideCalMeters,
            strideCalSteps = p[K.STRIDE_CAL_S] ?: d.strideCalSteps,
            strideCalWalks = p[K.STRIDE_CAL_W] ?: d.strideCalWalks,
            autoMerge = p[K.AUTO_MERGE] ?: d.autoMerge,
            autoMergeGapMin = p[K.MERGE_GAP] ?: d.autoMergeGapMin,
            walkMergeGapMin = p[K.WALK_MERGE_GAP] ?: d.walkMergeGapMin,
            autoMergeMaxDistanceM = p[K.MERGE_DIST] ?: d.autoMergeMaxDistanceM,
            askCategory = p[K.ASK_CAT] ?: d.askCategory,
            learnCategory = p[K.LEARN_CAT] ?: d.learnCategory,
            ruleWorkTime = p[K.RULE_WORK] ?: d.ruleWorkTime,
            ruleWeekendPrivate = p[K.RULE_WEEKEND] ?: d.ruleWeekendPrivate,
            learnedRoutes = p[K.LEARNED] ?: d.learnedRoutes,
            adaptiveGps = p[K.ADAPTIVE_GPS] ?: d.adaptiveGps,
            tripCheck = p[K.TRIP_CHECK] ?: d.tripCheck,
            customLimits = p[K.CUSTOM_LIMITS] ?: d.customLimits,
            purposeTemplates = p[K.PURPOSES] ?: d.purposeTemplates,
            thinAfterMonths = p[K.THIN_MONTHS] ?: d.thinAfterMonths,
            updateCheck = p[K.UPDATE_CHECK] ?: d.updateCheck,
            soundDriveStart = p[K.SND_DS] ?: d.soundDriveStart,
            soundDriveEnd = p[K.SND_DE] ?: d.soundDriveEnd,
            soundWalkStart = p[K.SND_WS] ?: d.soundWalkStart,
            soundWalkEnd = p[K.SND_WE] ?: d.soundWalkEnd,
            soundSpeeding = p[K.SND_SP] ?: d.soundSpeeding,
            soundParking = p[K.SND_PK] ?: d.soundParking,
            soundVolume = p[K.SND_VOL] ?: d.soundVolume,
            soundIgnoreSilent = p[K.SND_SILENT] ?: d.soundIgnoreSilent,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.AUTO] = s.autoDetection
        p[K.BOOT] = s.startOnBoot
        p[K.MIN_SPEED] = s.minDriveSpeedKmh
        p[K.CONFIRM] = s.confirmTimeoutMin
        p[K.STOP_DELAY] = s.stopDelayMin
        p[K.MIN_DIST] = s.minTripDistanceM
        p[K.GPS_INT] = s.gpsIntervalSec
        p[K.HIGH_ACC] = s.highAccuracy
        p[K.N_START] = s.notifyOnStart
        p[K.N_STOP] = s.notifyOnStop
        p[K.SOUND] = s.sound
        p[K.VIBRATE] = s.vibrate
        p[K.VIB_PATTERN] = s.vibrationPattern.name
        p[K.SPEED_NOTIF] = s.showSpeedInNotification
        p[K.BT_TRIGGER] = s.bluetoothTrigger
        p[K.BT_REQUIRED] = s.bluetoothRequired
        p[K.BT_ADDR] = s.carBtAddress
        p[K.BT_NAME] = s.carBtName
        p[K.AUTO_SYNC] = s.autoSync
        p[K.WIFI_ONLY] = s.syncWifiOnly
        p[K.SERVER] = s.serverUrl
        p[K.BIO] = s.biometricEnabled
        p[K.THEME] = s.themeMode.name
        p[K.CATEGORY] = s.defaultCategory
        p[K.CAR_NAME] = s.carName
        p[K.PLATE] = s.licensePlate
        p[K.ODO] = s.odometerKm
        p[K.ODO_DATE] = s.odometerDate
        p[K.ADDRESSES] = s.detectAddresses
        p[K.FUEL_CONS] = s.fuelConsumptionX10
        p[K.FUEL_PRICE] = s.fuelPriceCents
        p[K.EVENTS] = s.drivingEvents
        p[K.SPEED_ALERT] = s.speedAlert
        p[K.SPEED_ALERT_KMH] = s.speedAlertKmh
        p[K.SPEED_ALERT_REPEAT] = s.speedAlertRepeatSec
        p[K.DETECT_ANY] = s.detectAnyCondition
        p[K.ROAD_LIMITS] = s.roadLimits
        p[K.ROAD_SIGNS] = s.useRoadSigns
        p[K.TOLERANCE] = s.speedToleranceKmh
        p[K.MAP_STYLE] = s.mapStyle.name
        p[K.ACTIVE_VEHICLE] = s.activeVehicleUuid
        p[K.WALK] = s.walkTracking
        p[K.WALK_WORK] = s.walkWorkHoursOnly
        p[K.WORK_DAYS] = s.workDays
        p[K.WORK_FROM] = s.workFromMin
        p[K.WORK_TO] = s.workToMin
        p[K.STRIDE] = s.strideCm
        p[K.WALK_DIST_GPS] = s.walkDistanceGps
        p[K.WALK_STOP] = s.walkStopMin
        p[K.WALK_MIN] = s.walkMinDistanceM
        p[K.WALK_GPS] = s.walkGpsIntervalSec
        p[K.WALK_NOTIFY] = s.walkNotify
        p[K.WALK_VIBRATE] = s.walkVibrate
        p[K.STEP_GOAL] = s.dailyStepGoal
        p[K.MONTHLY] = s.monthlySummary
        p[K.OFFLINE_MAPS] = s.offlineMaps
        p[K.MAP_CACHE] = s.mapCacheMb
        p[K.DIAG] = s.diagnostics
        p[K.ODO_FACTORS] = s.odoFactors
        p[K.STRIDE_AUTO] = s.strideAuto
        p[K.STRIDE_CAL_M] = s.strideCalMeters
        p[K.STRIDE_CAL_S] = s.strideCalSteps
        p[K.STRIDE_CAL_W] = s.strideCalWalks
        p[K.AUTO_MERGE] = s.autoMerge
        p[K.MERGE_GAP] = s.autoMergeGapMin
        p[K.WALK_MERGE_GAP] = s.walkMergeGapMin
        p[K.MERGE_DIST] = s.autoMergeMaxDistanceM
        p[K.ASK_CAT] = s.askCategory
        p[K.LEARN_CAT] = s.learnCategory
        p[K.RULE_WORK] = s.ruleWorkTime
        p[K.RULE_WEEKEND] = s.ruleWeekendPrivate
        p[K.LEARNED] = s.learnedRoutes
        p[K.ADAPTIVE_GPS] = s.adaptiveGps
        p[K.TRIP_CHECK] = s.tripCheck
        p[K.CUSTOM_LIMITS] = s.customLimits
        p[K.PURPOSES] = s.purposeTemplates
        p[K.THIN_MONTHS] = s.thinAfterMonths
        p[K.UPDATE_CHECK] = s.updateCheck
        p[K.SND_DS] = s.soundDriveStart
        p[K.SND_DE] = s.soundDriveEnd
        p[K.SND_WS] = s.soundWalkStart
        p[K.SND_WE] = s.soundWalkEnd
        p[K.SND_SP] = s.soundSpeeding
        p[K.SND_PK] = s.soundParking
        p[K.SND_VOL] = s.soundVolume
        p[K.SND_SILENT] = s.soundIgnoreSilent
    }
}
