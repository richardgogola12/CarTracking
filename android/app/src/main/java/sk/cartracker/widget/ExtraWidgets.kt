package sk.cartracker.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.launch
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.RuntimeState
import sk.cartracker.service.DriveState
import sk.cartracker.service.ParkingTimer
import sk.cartracker.service.TrackingState
import sk.cartracker.sync.MonthlySummary
import sk.cartracker.ui.screens.dailyTotals
import sk.cartracker.ui.screens.dayStart
import sk.cartracker.ui.screens.stepStreaks
import java.util.Calendar
import kotlin.math.roundToInt

/** 1) Rýchle tlačidlá – stav a Jazda / Chôdza (malý widget 3 × 1). */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
}

/** 2) Kroky dnes – krúžok k dennému cieľu, km, séria dní, posledných 7 dní. */
class StepsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = ExtraWidgets.updateAll(ctx)
}

/** 3) Parkovanie – kde stojí auto, ako dlho, parkovací lístok s odpočtom, navigácia k autu. */
class ParkingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
}

/** 4) Tento mesiac – km, jazdy, služobné km, kroky, náklady, porovnanie a graf po dňoch. */
class MonthWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = ExtraWidgets.updateAll(ctx)
}

/** 5) Posledná jazda – náčrt trasy, km, čas, štýl a prepínač Súkromná / Služobná. */
class TripWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = ExtraWidgets.updateAll(ctx)
}

object ExtraWidgets {
    private fun ids(ctx: Context, cls: Class<*>) = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))
    private val MONTHS = listOf("Január", "Február", "Marec", "Apríl", "Máj", "Jún", "Júl", "August", "September", "Október", "November", "December")

    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val quick = ids(app, QuickWidget::class.java)
        val steps = ids(app, StepsWidget::class.java)
        val parking = ids(app, ParkingWidget::class.java)
        val month = ids(app, MonthWidget::class.java)
        val trip = ids(app, TripWidget::class.java)
        if (quick.isEmpty() && steps.isEmpty() && parking.isEmpty() && month.isEmpty() && trip.isEmpty()) return
        app.appScope.launch {
            val loggedIn = app.container.session.isLoggedIn
            val st = TrackingState.state.value; val live = TrackingState.live.value
            if (quick.isNotEmpty()) runCatching {
                val v = WidgetRender.quick(app, loggedIn, st, live?.speedKmh?.toInt() ?: 0, live?.distanceM ?: 0.0, live?.steps ?: 0)
                quick.forEach { mgr.updateAppWidget(it, v) }
            }
            if (parking.isNotEmpty()) runCatching {
                val p = RuntimeState.parking(app)
                val m = ParkingModel(st == DriveState.DRIVING, p?.address, p?.ts, p?.lat, p?.lng, ParkingTimer.until(app))
                val v = WidgetRender.parking(app, m); parking.forEach { mgr.updateAppWidget(it, v) }
            }
            if (!loggedIn) return@launch
            if (steps.isNotEmpty()) runCatching { val m = stepsModel(app); steps.forEach { id -> val (w, h) = CarWidget.sizeOf(mgr, id); mgr.updateAppWidget(id, WidgetRender.steps(app, m, w, h)) } }
            if (month.isNotEmpty()) runCatching { val m = monthModel(app); month.forEach { id -> val (w, h) = CarWidget.sizeOf(mgr, id); mgr.updateAppWidget(id, WidgetRender.month(app, m, w, h)) } }
            if (trip.isNotEmpty()) runCatching {
                val t = app.container.trips.currentOrLastCarTrip(false)?.let { CarWidget.tripSummary(app, it) }
                trip.forEach { id -> val (w, h) = CarWidget.sizeOf(mgr, id); mgr.updateAppWidget(id, WidgetRender.trip(app, t, w, h)) }
            }
        }
    }

    private suspend fun stepsModel(ctx: Context): StepsModel {
        val c = ctx.container
        val s = c.settings.current()
        val today = dayStart(System.currentTimeMillis())
        val trips = c.trips.tripsBetween(today - 400 * 86_400_000L, Long.MAX_VALUE)
        val totals = dailyTotals(trips, walk = true).toMutableMap()
        val live = TrackingState.live.value
        val walking = TrackingState.state.value == DriveState.WALKING && live != null
        if (walking) totals[today] = (totals[today] ?: 0.0) + live!!.steps
        val kmToday = trips.filter { it.isWalk && it.startedAt >= today }.sumOf { it.distanceM } + (if (walking) live!!.distanceM else 0.0)
        val (streak, best) = stepStreaks(totals, s.dailyStepGoal)
        val names = listOf("Ne", "Po", "Ut", "St", "Št", "Pi", "So")
        val week = (6 downTo 0).map { back ->
            val day = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -back) }
            names[day.get(Calendar.DAY_OF_WEEK) - 1] to (totals[dayStart(day.timeInMillis)] ?: 0.0)
        }
        return StepsModel((totals[today] ?: 0.0).toInt(), s.dailyStepGoal, kmToday / 1000, streak, best, week)
    }

    private suspend fun monthModel(ctx: Context): MonthModel {
        val now = Calendar.getInstance()
        val from = MonthlySummary.monthStart(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
        val prev = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val pFrom = MonthlySummary.monthStart(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH))
        val pTo = minOf(from, pFrom + (System.currentTimeMillis() - from))   // rovnaký počet dní minulého mesiaca
        val t = MonthlySummary.totals(ctx, from, Long.MAX_VALUE)
        val p = MonthlySummary.totals(ctx, pFrom, pTo)
        val km = dailyTotals(ctx.container.trips.tripsBetween(from, Long.MAX_VALUE), walk = false)
        val dom = now.get(Calendar.DAY_OF_MONTH)
        val days = (1..dom).map { day ->
            val ts = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
            (if (day == 1 || day % 5 == 0 || day == dom) "$day" else "") to (km[dayStart(ts)] ?: 0.0)
        }
        return MonthModel(MONTHS[now.get(Calendar.MONTH)], t.km, t.trips, t.cost, t.businessKm, t.steps, t.walkKm,
            if (p.km > 0) ((t.km - p.km) / p.km * 100).roundToInt() else null, days)
    }
}
