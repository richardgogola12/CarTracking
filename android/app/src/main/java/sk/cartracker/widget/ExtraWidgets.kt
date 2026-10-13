package sk.cartracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import sk.cartracker.R
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.RuntimeState
import sk.cartracker.service.DriveState
import sk.cartracker.service.ParkingTimer
import sk.cartracker.service.TrackingService
import sk.cartracker.service.TrackingState
import sk.cartracker.sync.MonthlySummary
import sk.cartracker.ui.formatDateTime
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatTime
import sk.cartracker.ui.screens.dailyTotals
import sk.cartracker.ui.screens.dayStart
import sk.cartracker.ui.screens.stepStreaks
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** 1) Rýchle tlačidlá – stav a Jazda / Chôdza (malý widget 2–3 × 1). */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
}

/** 2) Kroky dnes – krúžok k dennému cieľu, km, séria dní, posledných 7 dní. */
class StepsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
}

/** 3) Parkovanie – kde stojí auto, ako dlho, parkovací lístok s odpočtom. */
class ParkingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
}

/** 4) Tento mesiac – km, jazdy, náklady, služobné, kroky, porovnanie a graf po dňoch. */
class MonthWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ExtraWidgets.updateAll(ctx)
}

object ExtraWidgets {
    private fun ids(ctx: Context, cls: Class<*>) = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val quick = ids(app, QuickWidget::class.java)
        val steps = ids(app, StepsWidget::class.java)
        val parking = ids(app, ParkingWidget::class.java)
        val month = ids(app, MonthWidget::class.java)
        if (quick.isEmpty() && steps.isEmpty() && parking.isEmpty() && month.isEmpty()) return
        app.appScope.launch {
            val loggedIn = app.container.session.isLoggedIn
            if (quick.isNotEmpty()) runCatching { val v = quick(app, loggedIn); quick.forEach { mgr.updateAppWidget(it, v) } }
            if (parking.isNotEmpty()) runCatching { val v = parking(app); parking.forEach { mgr.updateAppWidget(it, v) } }
            if (steps.isNotEmpty() && loggedIn) runCatching { val v = steps(app); steps.forEach { mgr.updateAppWidget(it, v) } }
            if (month.isNotEmpty() && loggedIn) runCatching { val v = month(app); month.forEach { mgr.updateAppWidget(it, v) } }
        }
    }

    // ---------------- 1) rýchle tlačidlá ----------------
    private fun quick(ctx: Context, loggedIn: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
        v.setOnClickPendingIntent(R.id.q_root, CarWidget.openApp(ctx))
        val st = TrackingState.state.value
        val live = TrackingState.live.value
        val (bg, dot, title) = when (st) {
            DriveState.IDLE -> Triple(R.drawable.widget_bg_idle, Color.rgb(0x90, 0xA4, 0xAE), "Čakám na jazdu")
            DriveState.CANDIDATE -> Triple(R.drawable.widget_bg_candidate, Color.rgb(0xFF, 0xB3, 0x00), "Overujem…")
            DriveState.DRIVING -> Triple(R.drawable.widget_bg_drive, Color.rgb(0x69, 0xF0, 0xAE), "Jazda")
            DriveState.WALKING -> Triple(R.drawable.widget_bg_walk, Color.rgb(0x82, 0xB1, 0xFF), "Chôdza")
        }
        v.setInt(R.id.q_root, "setBackgroundResource", bg)
        v.setInt(R.id.q_dot, "setColorFilter", dot)
        v.setTextViewText(R.id.q_status, if (loggedIn) title else "Prihláste sa")
        v.setTextViewText(R.id.q_info, when {
            st == DriveState.DRIVING && live != null -> "${live.speedKmh.toInt()} km/h · ${formatDistance(live.distanceM)}"
            st == DriveState.WALKING && live != null -> "${live.steps} krokov"
            else -> ""
        })
        if (!loggedIn) {
            v.setOnClickPendingIntent(R.id.q_btn1, CarWidget.openApp(ctx)); v.setOnClickPendingIntent(R.id.q_btn2, CarWidget.openApp(ctx))
            return v
        }
        when (st) {
            DriveState.IDLE -> {
                v.setTextViewText(R.id.q_btn1, "🚗 Jazda"); v.setInt(R.id.q_btn1, "setBackgroundResource", R.drawable.widget_btn)
                v.setOnClickPendingIntent(R.id.q_btn1, CarWidget.service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                v.setTextViewText(R.id.q_btn2, "🚶 Chôdza")
                v.setOnClickPendingIntent(R.id.q_btn2, CarWidget.service(ctx, TrackingService.ACTION_MANUAL_WALK, 13))
            }
            DriveState.CANDIDATE -> {
                v.setTextViewText(R.id.q_btn1, "✓ Je to jazda"); v.setInt(R.id.q_btn1, "setBackgroundResource", R.drawable.widget_btn)
                v.setOnClickPendingIntent(R.id.q_btn1, CarWidget.service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                v.setTextViewText(R.id.q_btn2, "✕ Nie je")
                v.setOnClickPendingIntent(R.id.q_btn2, CarWidget.service(ctx, TrackingService.ACTION_STOP, 11))
            }
            DriveState.DRIVING, DriveState.WALKING -> {
                v.setTextViewText(R.id.q_btn1, "⏹ Ukončiť"); v.setInt(R.id.q_btn1, "setBackgroundResource", R.drawable.widget_btn_stop)
                v.setOnClickPendingIntent(R.id.q_btn1, CarWidget.service(ctx, TrackingService.ACTION_STOP, 11))
                v.setTextViewText(R.id.q_btn2, "Otvoriť")
                v.setOnClickPendingIntent(R.id.q_btn2, CarWidget.openApp(ctx))
            }
        }
        return v
    }

    // ---------------- 2) kroky ----------------
    private suspend fun steps(ctx: Context): RemoteViews {
        val c = ctx.container
        val s = c.settings.current()
        val today = dayStart(System.currentTimeMillis())
        val trips = c.trips.tripsBetween(today - 400 * 86_400_000L, Long.MAX_VALUE)
        val totals = dailyTotals(trips, walk = true).toMutableMap()
        val live = TrackingState.live.value
        if (TrackingState.state.value == DriveState.WALKING && live != null) totals[today] = (totals[today] ?: 0.0) + live.steps
        val stepsToday = (totals[today] ?: 0.0).toInt()
        val kmToday = trips.filter { it.isWalk && it.startedAt >= today }.sumOf { it.distanceM } +
            (if (TrackingState.state.value == DriveState.WALKING && live != null) live.distanceM else 0.0)
        val goal = s.dailyStepGoal
        val (streak, best) = stepStreaks(totals, goal)
        val d = ctx.resources.displayMetrics.density
        val v = RemoteViews(ctx.packageName, R.layout.widget_steps)
        v.setOnClickPendingIntent(R.id.s_root, CarWidget.openApp(ctx))
        v.setImageViewBitmap(R.id.s_ring, CarWidget.stepRing(stepsToday, goal, (84 * d).toInt(), d))
        v.setTextViewText(R.id.s_km, formatDistance(kmToday))
        v.setTextViewText(R.id.s_left, when {
            goal <= 0 -> "${CarWidget.compact(stepsToday)} krokov"
            stepsToday >= goal -> "Cieľ splnený ✓"
            else -> "Do cieľa ${CarWidget.compact(goal - stepsToday)} krokov"
        })
        v.setTextViewText(R.id.s_streak, if (streak > 0) "🔥 séria $streak ${dni(streak)} · rekord $best" else if (best > 0) "Rekordná séria $best ${dni(best)}" else "")
        val names = listOf("Ne", "Po", "Ut", "St", "Št", "Pi", "So")
        val week = (6 downTo 0).map { back ->
            val day = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -back) }
            names[day.get(Calendar.DAY_OF_WEEK) - 1] to (totals[dayStart(day.timeInMillis)] ?: 0.0)
        }
        v.setImageViewBitmap(R.id.s_week, CarWidget.weekChart(week, (220 * d).toInt(), (42 * d).toInt(), d, Color.rgb(0x82, 0xB1, 0xFF), steps = true))
        return v
    }

    // ---------------- 3) parkovanie ----------------
    private fun parking(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_parking)
        v.setOnClickPendingIntent(R.id.p_root, CarWidget.openApp(ctx))
        v.setOnClickPendingIntent(R.id.p_open, CarWidget.openApp(ctx))
        val driving = TrackingState.state.value == DriveState.DRIVING
        val p = RuntimeState.parking(ctx)?.takeIf { !driving }
        if (p == null) {
            v.setTextViewText(R.id.p_addr, if (driving) "Práve jazdíte" else "Žiadne uložené parkovanie")
            v.setTextViewText(R.id.p_since, if (driving) "Miesto sa uloží po skončení jazdy" else "Uloží sa po najbližšej jazde")
            v.setViewVisibility(R.id.p_find, View.GONE)
        } else {
            v.setViewVisibility(R.id.p_find, View.VISIBLE)
            v.setTextViewText(R.id.p_addr, p.address ?: String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng))
            v.setTextViewText(R.id.p_since, "od ${formatDateTime(p.ts)} · ${formatDuration(System.currentTimeMillis() - p.ts)}")
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lng}&mode=w"))
            val target = if (nav.resolveActivity(ctx.packageManager) != null) nav
                else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${p.lat},${p.lng}(Moje auto)"))
            v.setOnClickPendingIntent(R.id.p_find, PendingIntent.getActivity(ctx, 60, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        val until = ParkingTimer.until(ctx)?.takeIf { !driving }
        if (until != null) {
            v.setViewVisibility(R.id.p_ticket, View.VISIBLE)
            val left = until - System.currentTimeMillis()
            if (left > 0) {
                v.setTextViewText(R.id.p_ticket_txt, "Lístok do ${formatTime(until)} · zostáva")
                v.setChronometer(R.id.p_chrono, SystemClock.elapsedRealtime() + left, null, true)
                v.setChronometerCountDown(R.id.p_chrono, true)
                v.setViewVisibility(R.id.p_chrono, View.VISIBLE)
            } else {
                v.setTextViewText(R.id.p_ticket_txt, "Lístok vypršal o ${formatTime(until)}")
                v.setViewVisibility(R.id.p_chrono, View.GONE)
            }
            v.setTextViewText(R.id.p_open, "🎫 Zmeniť")
        } else {
            v.setViewVisibility(R.id.p_ticket, View.GONE)
            v.setTextViewText(R.id.p_open, "🎫 Lístok")
        }
        return v
    }

    // ---------------- 4) tento mesiac ----------------
    private suspend fun month(ctx: Context): RemoteViews {
        val now = Calendar.getInstance()
        val from = MonthlySummary.monthStart(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
        val prev = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val pFrom = MonthlySummary.monthStart(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH))
        val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
        // porovnanie s rovnakým počtom dní minulého mesiaca
        val pTo = minOf(from, pFrom + (System.currentTimeMillis() - from))
        val t = MonthlySummary.totals(ctx, from, Long.MAX_VALUE)
        val p = MonthlySummary.totals(ctx, pFrom, pTo)
        val months = listOf("január", "február", "marec", "apríl", "máj", "jún", "júl", "august", "september", "október", "november", "december")
        val v = RemoteViews(ctx.packageName, R.layout.widget_month)
        v.setOnClickPendingIntent(R.id.m_root, CarWidget.openApp(ctx))
        v.setTextViewText(R.id.m_title, "📊 ${months[now.get(Calendar.MONTH)].replaceFirstChar { it.uppercase() }}")
        v.setTextViewText(R.id.m_vs, if (p.km > 0) {
            val pct = ((t.km - p.km) / p.km * 100).roundToInt()
            (if (pct >= 0) "▲ $pct %" else "▼ ${-pct} %") + " km oproti min. mes."
        } else "")
        v.setTextViewText(R.id.m1, String.format(Locale("sk"), "%.0f", t.km))
        v.setTextViewText(R.id.m2, "${t.trips}")
        v.setTextViewText(R.id.m3, formatMoney(t.cost).replace(" €", "€"))
        v.setTextViewText(R.id.m4, String.format(Locale("sk"), "%.0f km", t.businessKm))
        v.setTextViewText(R.id.m5, CarWidget.compact(t.steps))
        v.setTextViewText(R.id.m6, String.format(Locale("sk"), "%.1f", t.walkKm))
        // km po dňoch 1..dnes
        val trips = ctx.container.trips.tripsBetween(from, Long.MAX_VALUE)
        val km = dailyTotals(trips, walk = false)
        val days = (1..dayOfMonth).map { day ->
            val ts = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
            (if (day == 1 || day % 5 == 0 || day == dayOfMonth) "$day" else "") to (km[dayStart(ts)] ?: 0.0)
        }
        val d = ctx.resources.displayMetrics.density
        v.setImageViewBitmap(R.id.m_chart, CarWidget.weekChart(days, (300 * d).toInt(), (56 * d).toInt(), d))
        return v
    }

    private fun dni(n: Int) = when (n) { 1 -> "deň"; in 2..4 -> "dni"; else -> "dní" }
}
