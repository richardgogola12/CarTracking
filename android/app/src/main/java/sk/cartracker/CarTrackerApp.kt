package sk.cartracker

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import sk.cartracker.data.AuthRepository
import sk.cartracker.data.SettingsRepository
import sk.cartracker.data.TripRepository
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
    val trips = TripRepository(db, api, session)
}

class CarTrackerApp : Application() {
    lateinit var container: AppContainer
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        Configuration.getInstance().userAgentValue = packageName

        // Pri každom štarte procesu sa uistíme, že detekcia jazdy je zaregistrovaná
        appScope.launch {
            if (container.session.isLoggedIn && container.settings.current().autoDetection) {
                ActivityDetectionManager.register(this@CarTrackerApp)
            }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as CarTrackerApp).container
val Context.appScope: CoroutineScope get() = (applicationContext as CarTrackerApp).appScope
