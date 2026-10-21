package sk.cartracker

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.osmdroid.config.Configuration
import sk.cartracker.data.AuthRepository
import sk.cartracker.data.ItemsSync
import sk.cartracker.data.PlacesRepository
import sk.cartracker.data.RefuelRepository
import sk.cartracker.data.ReminderRepository
import sk.cartracker.data.SettingsRepository
import sk.cartracker.data.TripRepository
import sk.cartracker.data.VehicleRepository
import sk.cartracker.data.local.AppDatabase
import sk.cartracker.data.remote.ApiProvider
import sk.cartracker.detection.ActivityDetectionManager
import sk.cartracker.security.SessionStore
import sk.cartracker.service.Notifications

class AppContainer(ctx: Context) {
    val settings = SettingsRepository(ctx)
    val session = SessionStore(ctx)
    val db = AppDatabase.create(ctx)
    val api = ApiProvider(settings, session)
    val auth = AuthRepository(ctx, api, session, settings)
    val trips = TripRepository(db, api, session, ctx.getSharedPreferences("pending_deletes", Context.MODE_PRIVATE))
    val vehicles = VehicleRepository(db, session, settings, api, ctx)
    val places = PlacesRepository(db, session)
    val refuels = RefuelRepository(db, session)
    val expenses = sk.cartracker.data.ExpenseRepository(db, session)
    val reminders = ReminderRepository(db, session, trips, vehicles)
    val items = ItemsSync(db, api, session)
    val steps = sk.cartracker.data.StepRepository(ctx, db, api, session)
}

class CarTrackerApp : Application() {
    lateinit var container: AppContainer
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        sk.cartracker.data.SpeedLimits.cacheDir = java.io.File(cacheDir, "speedlimits")
        sk.cartracker.service.ParkingTimer.createChannel(this)
        Configuration.getInstance().userAgentValue = packageName
        // Mapové dlaždice sa ukladajú do vyrovnávacej pamäte – aj na offline použitie
        Configuration.getInstance().apply {
            tileFileSystemCacheMaxBytes = 500L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 420L * 1024 * 1024
            expirationOverrideDuration = 30L * 24 * 3600 * 1000   // stiahnuté dlaždice platia 30 dní
        }
        appScope.launch {
            val mb = container.settings.current().mapCacheMb.coerceIn(100, 4000).toLong()
            Configuration.getInstance().tileFileSystemCacheMaxBytes = mb * 1024 * 1024
            Configuration.getInstance().tileFileSystemCacheTrimBytes = mb * 1024 * 1024 * 85 / 100
        }
        sk.cartracker.sync.ReminderWorker.schedule(this)
        sk.cartracker.sync.UpdateWorker.schedule(this)
        sk.cartracker.sync.PointThinning.schedule(this)
        appScope.launch {
            val st = container.settings.current()
            sk.cartracker.sync.StepWorker.schedule(this@CarTrackerApp, st.allDaySteps, st.stepReadMin)
            runCatching { sk.cartracker.data.StepCounter.sample(this@CarTrackerApp) }
        }
        sk.cartracker.widget.CarWidget.updateAll(this)

        // Widgety sa obnovia pri každej zmene jázd (úprava, zmazanie, zlúčenie, synchronizácia…) a po polnoci
        appScope.launch {
            container.db.tripDao().finishedSignature().distinctUntilChanged().drop(1).debounce(1500)
                .collect { sk.cartracker.widget.CarWidget.updateAll(this@CarTrackerApp) }
        }
        sk.cartracker.widget.MidnightRefresh.schedule(this)

        // Diagnostika zapnutá / vypnutá podľa nastavení
        appScope.launch { container.settings.flow.collect { sk.cartracker.data.DiagLog.enabled = it.diagnostics } }

        // Tlačidlá v rýchlej lište a widget sledujú stav jazdy
        appScope.launch {
            sk.cartracker.service.TrackingState.state.collect { sk.cartracker.service.BaseTrackTile.requestUpdate(this@CarTrackerApp) }
        }

        // Pri každom štarte procesu sa uistíme, že detekcia jazdy je zaregistrovaná
        appScope.launch {
            if (container.session.isLoggedIn) {
                // po aktualizácii: nastavenia ešte neboli na serveri → raz zosúladiť (stiahne aj chýbajúce údaje)
                container.session.user()?.let { u ->
                    if (!sk.cartracker.data.ServerRestore.settingsReady(this@CarTrackerApp, u.id)) sk.cartracker.sync.RestoreWorker.start(this@CarTrackerApp)
                    // čo ešte nikdy nebolo zálohované na serveri, sa automaticky doplní (raz), potom kontrola týždenne
                    val wifi = container.settings.current().syncWifiOnly
                    if (!sk.cartracker.data.ServerBackup.isVerified(this@CarTrackerApp, u.id)) sk.cartracker.sync.BackupCheckWorker.runOnce(this@CarTrackerApp, wifi)
                    sk.cartracker.sync.BackupCheckWorker.scheduleWeekly(this@CarTrackerApp, wifi)
                }
                container.vehicles.ensureDefault()
                val s = container.settings.current()
                if (s.autoDetection || s.walkTracking) ActivityDetectionManager.register(this@CarTrackerApp)
            }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as CarTrackerApp).container
val Context.appScope: CoroutineScope get() = (applicationContext as CarTrackerApp).appScope
