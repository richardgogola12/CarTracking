package sk.cartracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import sk.cartracker.R
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.service.DriveState
import sk.cartracker.service.TrackingService
import sk.cartracker.service.TrackingState
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatDistance
import java.util.Calendar

/**
 * Widget na ploche: stav (čakám / overujem / jazda / chôdza), aktívne vozidlo,
 * dnešné km autom a kroky, tlačidlá na spustenie a ukončenie záznamu.
 */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)

    override fun onEnabled(ctx: Context) = updateAll(ctx)

    companion object {
        @Volatile private var lastUpdate = 0L

        /** Prekreslí všetky inštancie widgetu. [throttle] = nie častejšie ako raz za 20 s (počas jazdy). */
        fun updateAll(ctx: Context, throttle: Boolean = false) {
            val app = ctx.applicationContext
            val now = System.currentTimeMillis()
            if (throttle && now - lastUpdate < 20_000) return
            lastUpdate = now
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, CarWidget::class.java))
            if (ids.isEmpty()) return
            app.appScope.launch {
                runCatching { mgr.updateAppWidget(ids, build(app)) }
            }
        }

        private suspend fun build(ctx: Context): RemoteViews {
            val c = ctx.container
            val v = RemoteViews(ctx.packageName, R.layout.widget_car)
            val state = TrackingState.state.value
            val live = TrackingState.live.value

            if (!c.session.isLoggedIn) {
                v.setTextViewText(R.id.w_status, "Car Tracking")
                v.setTextViewText(R.id.w_vehicle, "Prihláste sa v aplikácii")
                v.setTextViewText(R.id.w_today, "")
                v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
                return v
            }

            v.setTextViewText(R.id.w_status, when (state) {
                DriveState.IDLE -> "Čakám na jazdu"
                DriveState.CANDIDATE -> "Overujem jazdu…"
                DriveState.DRIVING -> "🚗 Jazda · ${live?.let { formatDistance(it.distanceM) } ?: ""}" +
                    (live?.let { " · ${it.speedKmh.toInt()} km/h" } ?: "")
                DriveState.WALKING -> "🚶 Chôdza · ${live?.steps ?: 0} krokov"
            })
            v.setTextViewText(R.id.w_vehicle, live?.vehicleName ?: c.vehicles.active()?.label ?: "")

            // Dnešné súčty (ukončené záznamy + práve prebiehajúci)
            val dayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = c.trips.tripsBetween(dayStart, Long.MAX_VALUE)
            var carKm = today.filter { !it.isWalk }.sumOf { it.distanceM }
            var steps = today.filter { it.isWalk }.sumOf { it.steps }
            if (state == DriveState.DRIVING && live != null) carKm += live.distanceM
            if (state == DriveState.WALKING && live != null) steps += live.steps
            val due = runCatching {
                c.reminders.active().count {
                    c.reminders.status(it).state.let { st -> st == sk.cartracker.data.ReminderState.SOON || st == sk.cartracker.data.ReminderState.OVERDUE }
                }
            }.getOrDefault(0)
            v.setTextViewText(R.id.w_today, "Dnes: ${formatDistance(carKm)} autom · $steps krokov" +
                if (due > 0) "\n🔔 Pripomienky: $due" else "")

            // Tlačidlá
            val active = state == DriveState.DRIVING || state == DriveState.WALKING || state == DriveState.CANDIDATE
            if (active) {
                v.setTextViewText(R.id.w_btn_drive, if (state == DriveState.WALKING) "⏹ Ukončiť chôdzu" else "⏹ Ukončiť jazdu")
                v.setOnClickPendingIntent(R.id.w_btn_drive, service(ctx, TrackingService.ACTION_STOP, 11))
                v.setTextViewText(R.id.w_btn_walk, if (state == DriveState.WALKING) "🚗 Jazda" else "Otvoriť")
                v.setOnClickPendingIntent(R.id.w_btn_walk,
                    if (state == DriveState.WALKING) service(ctx, TrackingService.ACTION_MANUAL_START, 12) else openApp(ctx))
            } else {
                v.setTextViewText(R.id.w_btn_drive, "🚗 Jazda")
                v.setOnClickPendingIntent(R.id.w_btn_drive, service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                v.setTextViewText(R.id.w_btn_walk, "🚶 Chôdza")
                v.setOnClickPendingIntent(R.id.w_btn_walk, service(ctx, TrackingService.ACTION_MANUAL_WALK, 13))
            }
            v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
            return v
        }

        private fun openApp(ctx: Context) = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Ťuknutie na widget = interakcia používateľa → služba v popredí sa smie spustiť. */
        private fun service(ctx: Context, action: String, code: Int) = PendingIntent.getForegroundService(
            ctx, code, Intent(ctx, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
