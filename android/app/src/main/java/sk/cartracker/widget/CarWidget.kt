package sk.cartracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import sk.cartracker.R
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.ReminderState
import sk.cartracker.data.RuntimeState
import sk.cartracker.data.cost
import sk.cartracker.service.DriveState
import sk.cartracker.service.TrackingService
import sk.cartracker.service.TrackingState
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatTime
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Widget na ploche.
 *  - pozadie a farba bodky podľa stavu (čakám / overujem / jazda / chôdza),
 *  - počas jazdy: rýchlosť, značka s limitom, bežiaci čas, km a náklady; počas chôdze kroky a vzdialenosť,
 *  - dlaždice: dnešné km autom, počet jázd, kroky, náklady,
 *  - väčší widget (od ~3 riadkov): graf km za 7 dní, krúžok krokov k dennému cieľu, najbližšia pripomienka, parkovanie,
 *  - tlačidlá Jazda / Chôdza / Ukončiť / Obnoviť.
 */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)

    override fun onEnabled(ctx: Context) = updateAll(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = updateAll(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) updateAll(ctx)
    }

    companion object {
        private const val ACTION_REFRESH = "sk.cartracker.widget.REFRESH"
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
                val data = runCatching { load(app) }.getOrNull() ?: return@launch
                for (id in ids) {
                    runCatching {
                        val o = mgr.getAppWidgetOptions(id)
                        val wDp = max(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)).takeIf { it > 0 } ?: 300
                        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                        val large = hDp == 0 || hDp >= 200
                        mgr.updateAppWidget(id, build(app, data, large, wDp))
                    }
                }
            }
        }

        // ---------------- údaje ----------------

        private class Data(
            val loggedIn: Boolean,
            val state: DriveState,
            val live: sk.cartracker.service.LiveTrip?,
            val vehicle: String?,
            val todayKm: Double,
            val todayTrips: Int,
            val todaySteps: Int,
            val todayCost: Double,
            val liveCost: Double,
            val week: List<Pair<String, Double>>,
            val stepGoal: Int,
            val reminder: String?,
            val reminderOverdue: Boolean,
            val parking: String?,
        )

        private suspend fun load(ctx: Context): Data {
            val c = ctx.container
            val state = TrackingState.state.value
            val live = TrackingState.live.value
            if (!c.session.isLoggedIn) return Data(false, state, null, null, 0.0, 0, 0, 0.0, 0.0, emptyList(), 0, null, false, null)
            val s = c.settings.current()
            val vehicles = c.vehicles.all().associateBy { it.uuid }
            val active = c.vehicles.active()

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            val weekStart = dayStart - 6 * 86_400_000L
            val recent = c.trips.tripsBetween(weekStart, Long.MAX_VALUE)
            val today = recent.filter { it.startedAt >= dayStart }
            val cars = today.filter { !it.isWalk }

            var km = cars.sumOf { it.distanceM }
            var steps = today.filter { it.isWalk }.sumOf { it.steps }
            var trips = cars.size
            val cost = cars.sumOf { it.cost(vehicles[it.vehicleUuid], s) }
            var liveCost = 0.0
            if (state == DriveState.DRIVING && live != null) {
                km += live.distanceM; trips += 1
                liveCost = (active?.cost(live.distanceM) ?: s.fuelCost(live.distanceM))
            }
            if (state == DriveState.WALKING && live != null) steps += live.steps

            val dayNames = listOf("Ne", "Po", "Ut", "St", "Št", "Pi", "So")
            val week = (6 downTo 0).map { back ->
                val from = dayStart - back * 86_400_000L
                val to = from + 86_400_000L
                val name = Calendar.getInstance().apply { timeInMillis = from }.get(Calendar.DAY_OF_WEEK).let { dayNames[it - 1] }
                var v = recent.filter { !it.isWalk && it.startedAt in from until to }.sumOf { it.distanceM } / 1000.0
                if (back == 0 && state == DriveState.DRIVING && live != null) v += live.distanceM / 1000.0
                name to v
            }

            // najnaliehavejšia pripomienka
            val rem = runCatching {
                c.reminders.active().map { it to c.reminders.status(it) }
                    .filter { it.second.state == ReminderState.SOON || it.second.state == ReminderState.OVERDUE }
                    .minByOrNull { it.second.daysLeft ?: (it.second.kmLeft?.toLong()?.div(50) ?: Long.MAX_VALUE) }
            }.getOrNull()
            val remText = rem?.let { (r, st) ->
                "🔔 ${r.title}: " + listOfNotNull(
                    st.daysLeft?.let { if (it < 0) "${-it} dní po termíne" else if (it == 0L) "dnes" else "o $it dní" },
                    st.kmLeft?.let { if (it < 0) "${-it} km po termíne" else "o $it km" },
                ).joinToString(" · ")
            }

            val park = RuntimeState.parking(ctx)?.takeIf { state == DriveState.IDLE }?.let {
                "🅿️ ${it.address ?: "Zaparkované"} · pred ${formatDuration(System.currentTimeMillis() - it.ts)}"
            }

            return Data(
                true, state, live, live?.vehicleName ?: active?.label, km, trips, steps, cost + liveCost, liveCost,
                week, s.dailyStepGoal, remText, rem?.second?.state == ReminderState.OVERDUE, park,
            )
        }

        // ---------------- vykreslenie ----------------

        private fun build(ctx: Context, d: Data, large: Boolean, widthDp: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, if (large) R.layout.widget_car_large else R.layout.widget_car)
            v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_btn_refresh, refresh(ctx))
            v.setTextViewText(R.id.w_updated, formatTime(System.currentTimeMillis()))

            if (!d.loggedIn) {
                v.setTextViewText(R.id.w_status, "Car Tracking")
                v.setTextViewText(R.id.w_vehicle, "Prihláste sa v aplikácii")
                v.setOnClickPendingIntent(R.id.w_btn_drive, openApp(ctx))
                v.setOnClickPendingIntent(R.id.w_btn_walk, openApp(ctx))
                return v
            }

            val (bg, dot, title) = when (d.state) {
                DriveState.IDLE -> Triple(R.drawable.widget_bg_idle, Color.rgb(0x90, 0xA4, 0xAE), "Čakám na jazdu")
                DriveState.CANDIDATE -> Triple(R.drawable.widget_bg_candidate, Color.rgb(0xFF, 0xB3, 0x00), "Overujem jazdu…")
                DriveState.DRIVING -> Triple(R.drawable.widget_bg_drive, Color.rgb(0x69, 0xF0, 0xAE), "Režim jazdy")
                DriveState.WALKING -> Triple(R.drawable.widget_bg_walk, Color.rgb(0x82, 0xB1, 0xFF), "Chôdza")
            }
            v.setInt(R.id.w_root, "setBackgroundResource", bg)
            v.setInt(R.id.w_dot, "setColorFilter", dot)
            v.setTextViewText(R.id.w_status, title)
            v.setTextViewText(R.id.w_vehicle, d.vehicle ?: "")

            // ---- živý blok ----
            val live = d.live
            val active = (d.state == DriveState.DRIVING || d.state == DriveState.WALKING) && live != null
            v.setViewVisibility(R.id.w_live, if (active) View.VISIBLE else View.GONE)
            if (active && live != null) {
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - live.startedAt)
                v.setChronometer(R.id.w_chrono, base, null, true)
                if (d.state == DriveState.DRIVING) {
                    v.setTextViewText(R.id.w_big, "${live.speedKmh.toInt()}")
                    v.setTextViewText(R.id.w_big_unit, "km/h")
                    v.setTextViewText(R.id.w_live_sub, "${formatDistance(live.distanceM)} · ${formatMoney(d.liveCost)}")
                    val limit = live.limitKmh
                    if (limit != null) {
                        v.setViewVisibility(R.id.w_limit, View.VISIBLE)
                        v.setImageViewBitmap(R.id.w_limit, limitSign(ctx, limit, live.speedKmh > limit))
                    } else v.setViewVisibility(R.id.w_limit, View.GONE)
                } else {
                    v.setTextViewText(R.id.w_big, String.format(Locale("sk"), "%,d", live.steps))
                    v.setTextViewText(R.id.w_big_unit, "krokov")
                    v.setTextViewText(R.id.w_live_sub, formatDistance(live.distanceM))
                    v.setViewVisibility(R.id.w_limit, View.GONE)
                }
            }

            // ---- dlaždice ----
            v.setTextViewText(R.id.w_t1, formatDistance(d.todayKm))
            v.setTextViewText(R.id.w_t2, "${d.todayTrips}")
            v.setTextViewText(R.id.w_t3, compact(d.todaySteps))
            v.setTextViewText(R.id.w_t4, formatMoney(d.todayCost).replace(" €", "€"))
            v.setTextViewText(R.id.w_t1l, "dnes autom")

            // ---- veľký widget ----
            if (large) {
                val density = ctx.resources.displayMetrics.density
                val chartW = ((widthDp - 28 - 92 - 16).coerceAtLeast(120) * density).toInt()
                val chartH = (64 * density).toInt()
                v.setImageViewBitmap(R.id.w_chart, weekChart(d.week, chartW, chartH, density))
                v.setTextViewText(R.id.w_week_title, "Posledných 7 dní · ${formatDistance(d.week.sumOf { it.second } * 1000)}")
                v.setImageViewBitmap(R.id.w_ring, stepRing(d.todaySteps, d.stepGoal, (86 * density).toInt(), density))
                if (d.reminder != null) {
                    v.setViewVisibility(R.id.w_reminder, View.VISIBLE)
                    v.setTextViewText(R.id.w_reminder, d.reminder)
                    v.setTextColor(R.id.w_reminder, if (d.reminderOverdue) Color.rgb(0xFF, 0x8A, 0x80) else Color.rgb(0xFF, 0xD1, 0x80))
                } else v.setViewVisibility(R.id.w_reminder, View.GONE)
                if (d.parking != null) {
                    v.setViewVisibility(R.id.w_parking, View.VISIBLE)
                    v.setTextViewText(R.id.w_parking, d.parking)
                } else v.setViewVisibility(R.id.w_parking, View.GONE)
            }

            // ---- tlačidlá ----
            when (d.state) {
                DriveState.IDLE -> {
                    v.setTextViewText(R.id.w_btn_drive, "🚗 Jazda")
                    v.setInt(R.id.w_btn_drive, "setBackgroundResource", R.drawable.widget_btn)
                    v.setOnClickPendingIntent(R.id.w_btn_drive, service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                    v.setTextViewText(R.id.w_btn_walk, "🚶 Chôdza")
                    v.setOnClickPendingIntent(R.id.w_btn_walk, service(ctx, TrackingService.ACTION_MANUAL_WALK, 13))
                }
                DriveState.CANDIDATE -> {
                    v.setTextViewText(R.id.w_btn_drive, "✓ Je to jazda")
                    v.setInt(R.id.w_btn_drive, "setBackgroundResource", R.drawable.widget_btn)
                    v.setOnClickPendingIntent(R.id.w_btn_drive, service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                    v.setTextViewText(R.id.w_btn_walk, "✕ Nie je")
                    v.setOnClickPendingIntent(R.id.w_btn_walk, service(ctx, TrackingService.ACTION_STOP, 11))
                }
                DriveState.DRIVING -> {
                    v.setTextViewText(R.id.w_btn_drive, "⏹ Ukončiť jazdu")
                    v.setInt(R.id.w_btn_drive, "setBackgroundResource", R.drawable.widget_btn_stop)
                    v.setOnClickPendingIntent(R.id.w_btn_drive, service(ctx, TrackingService.ACTION_STOP, 11))
                    v.setTextViewText(R.id.w_btn_walk, "Otvoriť")
                    v.setOnClickPendingIntent(R.id.w_btn_walk, openApp(ctx))
                }
                DriveState.WALKING -> {
                    v.setTextViewText(R.id.w_btn_drive, "⏹ Ukončiť chôdzu")
                    v.setInt(R.id.w_btn_drive, "setBackgroundResource", R.drawable.widget_btn_stop)
                    v.setOnClickPendingIntent(R.id.w_btn_drive, service(ctx, TrackingService.ACTION_STOP, 11))
                    v.setTextViewText(R.id.w_btn_walk, "🚗 Jazda")
                    v.setOnClickPendingIntent(R.id.w_btn_walk, service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                }
            }
            return v
        }

        private fun compact(n: Int): String = when {
            n >= 10_000 -> String.format(Locale("sk"), "%.1fk", n / 1000.0)
            else -> String.format(Locale("sk"), "%,d", n)
        }

        /** Stĺpcový graf km po dňoch, dnešný deň zvýraznený. */
        private fun weekChart(week: List<Pair<String, Double>>, w: Int, h: Int, d: Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val labelH = 13 * d
            val valueH = 12 * d
            val maxV = max(week.maxOfOrNull { it.second } ?: 0.0, 1.0)
            val slot = w / week.size.toFloat()
            val bw = slot * 0.56f
            val bar = Paint(Paint.ANTI_ALIAS_FLAG)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = 9.5f * d; textAlign = Paint.Align.CENTER }
            val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9.5f * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
            val area = h - labelH - valueH
            week.forEachIndexed { i, (name, km) ->
                val cx = i * slot + slot / 2
                val bh = max((km / maxV * area).toFloat(), if (km > 0) 3 * d else 1.5f * d)
                val top = valueH + area - bh
                val today = i == week.lastIndex
                bar.color = if (today) Color.rgb(0x69, 0xF0, 0xAE) else Color.argb(150, 255, 255, 255)
                c.drawRoundRect(RectF(cx - bw / 2, top, cx + bw / 2, valueH + area), 3 * d, 3 * d, bar)
                c.drawText(name, cx, h - 2 * d, label)
                if ((today || km == maxV) && km > 0) c.drawText(if (km >= 100) "${km.toInt()}" else String.format(Locale("sk"), "%.0f", km), cx, top - 2 * d, value)
            }
            return bmp
        }

        /** Krúžok krokov k dennému cieľu. */
        private fun stepRing(steps: Int, goal: Int, size: Int, d: Float): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val stroke = 7 * d
            val r = RectF(stroke, stroke, size - stroke, size - stroke)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.argb(50, 255, 255, 255) }
            val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
                color = if (steps >= goal) Color.rgb(0x69, 0xF0, 0xAE) else Color.rgb(0x82, 0xB1, 0xFF)
            }
            c.drawArc(r, 0f, 360f, false, track)
            val frac = if (goal > 0) min(steps / goal.toFloat(), 1f) else 0f
            if (frac > 0) c.drawArc(r, -90f, 360f * frac, false, prog)
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255); textSize = 9 * d; textAlign = Paint.Align.CENTER }
            c.drawText(compact(steps), size / 2f, size / 2f + 2 * d, big)
            c.drawText(if (steps >= goal) "cieľ ✓" else "z ${compact(goal)}", size / 2f, size / 2f + 14 * d, small)
            return bmp
        }

        /** Dopravná značka s limitom; pri prekročení červené pozadie. */
        private fun limitSign(ctx: Context, limit: Int, over: Boolean): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val s = (44 * d).toInt()
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = s / 2f
            c.drawCircle(cx, cx, cx, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD3, 0x2F, 0x2F) })
            c.drawCircle(cx, cx, cx - 5 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (over) Color.rgb(0xFF, 0xCD, 0xD2) else Color.WHITE })
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                textSize = (if (limit >= 100) 14 else 17) * d
            }
            c.drawText("$limit", cx, cx - (t.descent() + t.ascent()) / 2, t)
            return bmp
        }

        private fun openApp(ctx: Context) = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun refresh(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 14, Intent(ctx, CarWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Ťuknutie na widget = interakcia používateľa → služba v popredí sa smie spustiť. */
        private fun service(ctx: Context, action: String, code: Int) = PendingIntent.getForegroundService(
            ctx, code, Intent(ctx, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
