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

/** Emulátor: 10.0.2.2 = localhost počítača. Na reálnom telefóne zadajte IP/doménu servera v Nastaveniach. */
const val DEFAULT_SERVER_URL = "http://10.0.2.2/cartracking/api/"

enum class VibrationPattern(val label: String) { SHORT("Krátka"), LONG("Dlhá"), DOUBLE("Dvojitá") }
enum class ThemeMode(val label: String) { SYSTEM("Podľa systému"), LIGHT("Svetlý"), DARK("Tmavý") }
enum class MapStyle(val label: String) {
    AUTO("Podľa témy aplikácie"), LIGHT("Svetlá"), DARK("Tmavá"), STREETS("Klasická OSM"), TOPO("Terénna"), SATELLITE("Satelitná")
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
    val speedAlertKmh: Int = 130,
    // Mapa
    val mapStyle: MapStyle = MapStyle.AUTO,
) {
    val fuelConsumption get() = fuelConsumptionX10 / 10.0
    val fuelPrice get() = fuelPriceCents / 100.0
    fun fuelCost(distanceM: Double) = distanceM / 1000.0 / 100.0 * fuelConsumption * fuelPrice
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
        val MAP_STYLE = stringPreferencesKey("map_style")
    }

    val flow: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { read(it) }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
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
            mapStyle = p[K.MAP_STYLE]?.let { runCatching { MapStyle.valueOf(it) }.getOrNull() } ?: d.mapStyle,
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
        p[K.MAP_STYLE] = s.mapStyle.name
    }
}
