package sk.cartracker.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.ReminderState
import sk.cartracker.data.RuntimeState
import sk.cartracker.data.SpeedProfile
import sk.cartracker.data.cost
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.TripEntity
import sk.cartracker.service.DriveState
import sk.cartracker.service.TrackingState
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatShortDate
import sk.cartracker.ui.formatTime
import java.util.Calendar
import kotlin.math.max

/**
 * Hlavný widget: stav (čakám / jazda / chôdza), počas jazdy rýchlosť so značkou limitu, inak dnešné km;
 * dlaždice (jazdy, kroky, náklady), prepínateľný graf (km 7 dní / rýchlosť jazdy / kroky), krúžok krokov,
 * posledná jazda s náčrtom trasy, pripomienka alebo zaparkované auto a tlačidlá. Obsah sa prispôsobí veľkosti.
 */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)
    override fun onEnabled(ctx: Context) = updateAll(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = updateAll(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAll(ctx)
            ACTION_CHART -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit().putInt("chart_$id", (prefs.getInt("chart_$id", WidgetRender.CHART_KM) + 1) % WidgetRender.CHART_COUNT).apply()
                    updateAll(ctx)
                }
            }
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { e.remove("chart_$it") }
        e.apply()
    }

    companion object {
        const val ACTION_REFRESH = "sk.cartracker.widget.REFRESH"
        const val ACTION_CHART = "sk.cartracker.widget.CHART"
        private const val PREFS = "widget"
        @Volatile private var lastUpdate = 0L

        /** Veľkosť widgetu v dp (na výšku telefónu: šírka = min, výška = max). */
        fun sizeOf(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return (w.takeIf { it > 0 } ?: 300) to h
        }

        /** Prekreslí všetky widgety aplikácie. [throttle] = nie častejšie ako raz za 20 s (počas jazdy). */
        fun updateAll(ctx: Context, throttle: Boolean = false) {
            val app = ctx.applicationContext
            val now = System.currentTimeMillis()
            if (throttle && now - lastUpdate < 20_000) return
            lastUpdate = now
            ExtraWidgets.updateAll(app)
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, CarWidget::class.java))
            if (ids.isEmpty()) return
            app.appScope.launch {
                val m = runCatching { load(app) }.getOrNull() ?: return@launch
                for (id in ids) runCatching {
                    val (w, h) = sizeOf(mgr, id)
                    val mode = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("chart_$id", WidgetRender.CHART_KM)
                    mgr.updateAppWidget(id, WidgetRender.main(app, m, w, h, id, mode))
                }
            }
        }

        // ---------------- údaje ----------------

        private val DAY_NAMES = listOf("Ne", "Po", "Ut", "St", "Št", "Pi", "So")

        /** Súhrn jazdy pre widget vrátane zjednodušenej trasy. */
        suspend fun tripSummary(ctx: Context, t: TripEntity): TripSummary {
            val c = ctx.container
            val s = c.settings.current()
            val v = c.vehicles.byUuid(t.vehicleUuid)
            val pts = c.trips.points(t.id).filter { (it.accuracyM ?: 0f) <= 60f }
            val step = max(1, pts.size / 150)
            val route = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }.map { RoutePt(it.lat, it.lng, it.speedKmh ?: 0f) }
            fun name(place: String?, addr: String?) = place ?: addr?.split(",")?.take(2)?.joinToString(",")?.trim()?.ifBlank { null } ?: "—"
            return TripSummary(t.id, t.uuid, name(t.startPlace, t.startAddress), name(t.endPlace, t.endAddress), t.startedAt, t.endedAt ?: t.startedAt,
                t.distanceM / 1000, t.avgSpeedKmh, t.maxSpeedKmh, t.score, t.category == CATEGORY_BUSINESS, t.cost(v, s), v?.label, route)
        }

        private suspend fun load(ctx: Context): MainModel {
            val c = ctx.container
            val state = TrackingState.state.value
            val live = TrackingState.live.value
            if (!c.session.isLoggedIn) return MainModel(false, state)
            val s = c.settings.current()
            val vehicles = c.vehicles.all().associateBy { it.uuid }
            val active = c.vehicles.active()
            val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val recent = c.trips.tripsBetween(dayStart - 6 * 86_400_000L, Long.MAX_VALUE)
            val today = recent.filter { it.startedAt >= dayStart }
            val cars = today.filter { !it.isWalk }
            var km = cars.sumOf { it.distanceM }
            var steps = today.filter { it.isWalk }.sumOf { it.steps }
            var trips = cars.size
            var ms = cars.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt }
            val cost = cars.sumOf { it.cost(vehicles[it.vehicleUuid], s) }
            var liveCost = 0.0
            if (state == DriveState.DRIVING && live != null) {
                km += live.distanceM; trips += 1; ms += System.currentTimeMillis() - live.startedAt
                liveCost = active?.cost(live.distanceM) ?: s.fuelCost(live.distanceM)
            }
            if (state == DriveState.WALKING && live != null) steps += live.steps
            fun series(walk: Boolean) = (6 downTo 0).map { back ->
                val from = dayStart - back * 86_400_000L; val to = from + 86_400_000L
                val name = Calendar.getInstance().apply { timeInMillis = from }.get(Calendar.DAY_OF_WEEK).let { DAY_NAMES[it - 1] }
                var v = recent.filter { it.isWalk == walk && it.startedAt in from until to }.sumOf { if (walk) it.steps.toDouble() else it.distanceM / 1000.0 }
                if (back == 0 && live != null) {
                    if (!walk && state == DriveState.DRIVING) v += live.distanceM / 1000.0
                    if (walk && state == DriveState.WALKING) v += live.steps
                }
                name to v
            }
            val speedTrip = runCatching { c.trips.currentOrLastCarTrip(state == DriveState.DRIVING) }.getOrNull()
            val speed = speedTrip?.let { runCatching { SpeedProfile.build(c.trips.points(it.id), 160) }.getOrNull() } ?: emptyList()
            val speedSum = SpeedProfile.summary(speed, speedTrip?.maxSpeedKmh)
            val speedTitle = when {
                speedTrip == null -> "Rýchlosť · zatiaľ žiadna jazda"
                !speedTrip.finished -> "Rýchlosť · aktuálna jazda"
                else -> "Rýchlosť · " + (if (speedTrip.startedAt >= dayStart) "" else formatShortDate(speedTrip.startedAt) + " ") +
                    formatTime(speedTrip.startedAt) + " · " + formatDistance(speedTrip.distanceM)
            }
            val rem = runCatching {
                c.reminders.active().map { it to c.reminders.status(it) }
                    .filter { it.second.state == ReminderState.SOON || it.second.state == ReminderState.OVERDUE }
                    .minByOrNull { it.second.daysLeft ?: (it.second.kmLeft?.toLong()?.div(50) ?: Long.MAX_VALUE) }
            }.getOrNull()
            val remText = rem?.let { (r, st) ->
                "${r.title}: " + listOfNotNull(
                    st.daysLeft?.let { if (it < 0) "${-it} dní po termíne" else if (it == 0L) "dnes" else "o $it dní" },
                    st.kmLeft?.let { if (it < 0) "${-it} km po termíne" else "o $it km" },
                ).joinToString(" · ")
            }
            val park = RuntimeState.parking(ctx)?.takeIf { state == DriveState.IDLE }
            val last = runCatching { c.trips.currentOrLastCarTrip(false)?.let { tripSummary(ctx, it) } }.getOrNull()
            return MainModel(
                true, state,
                liveStartedAt = live?.startedAt ?: 0, liveKmh = live?.speedKmh?.toInt() ?: 0, liveDistanceM = live?.distanceM ?: 0.0,
                liveSteps = live?.steps ?: 0, liveLimit = live?.limitKmh, liveCost = liveCost,
                vehicle = live?.vehicleName ?: active?.label,
                todayKm = km / 1000, todayTrips = trips, todaySteps = steps, todayCost = cost + liveCost, todayMs = ms,
                week = series(false), weekSteps = series(true), speed = speed, speedTitle = speedTitle, speedMax = speedSum.maxKmh, speedAvg = speedSum.movingAvgKmh,
                stepGoal = s.dailyStepGoal, reminder = remText, reminderOverdue = rem?.second?.state == ReminderState.OVERDUE,
                parkingAddr = park?.address ?: park?.let { "zaparkované" }, parkingSince = park?.ts, parkingLat = park?.lat, parkingLng = park?.lng,
                lastTrip = last,
            )
        }
    }
}
