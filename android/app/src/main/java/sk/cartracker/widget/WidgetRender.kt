package sk.cartracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import sk.cartracker.R
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.service.CategoryActionReceiver
import sk.cartracker.service.DriveState
import sk.cartracker.service.TrackingService
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration
import sk.cartracker.ui.formatMoney
import sk.cartracker.ui.formatShortDate
import sk.cartracker.ui.formatTime
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/** Vykreslenie widgetov z modelov (RemoteViews). Spoločné pre aplikáciu aj test náhľadov. */
object WidgetRender {
    private val SK = Locale("sk")
    const val CHART_KM = 0
    const val CHART_SPEED = 1
    const val CHART_STEPS = 2
    const val CHART_COUNT = 3
    private const val DARK = 0xFF0B1220.toInt()

    private data class StateStyle(val bg: Int, val dot: Int, val title: String, val accent: Int)

    private fun style(s: DriveState) = when (s) {
        DriveState.IDLE -> StateStyle(R.drawable.wg_bg_idle, WidgetArt.SLATE, "Pripravený", WidgetArt.VIOLET)
        DriveState.CANDIDATE -> StateStyle(R.drawable.wg_bg_candidate, WidgetArt.AMBER, "Overujem jazdu…", WidgetArt.AMBER)
        DriveState.DRIVING -> StateStyle(R.drawable.wg_bg_drive, WidgetArt.GREEN_L, "Jazda", WidgetArt.GREEN_L)
        DriveState.WALKING -> StateStyle(R.drawable.wg_bg_walk, WidgetArt.BLUE_L, "Chôdza", WidgetArt.BLUE_L)
    }

    private fun density(ctx: Context) = ctx.resources.displayMetrics.density
    private fun km1(m: Double) = String.format(SK, if (m >= 100_000) "%.0f" else "%.1f", m / 1000)

    /** Tlačidlo: ikona + text + pozadie (primárne = biele s tmavým textom). */
    private fun button(v: RemoteViews, id: Int, ic: Int, tx: Int?, icon: Int, text: String, kind: String, pi: PendingIntent) {
        val (bg, fg) = when (kind) {
            "primary" -> R.drawable.wg_btn_primary to DARK
            "danger" -> R.drawable.wg_btn_danger to Color.WHITE
            "go" -> R.drawable.wg_btn_go to Color.WHITE
            else -> R.drawable.wg_btn_secondary to Color.WHITE
        }
        v.setInt(id, "setBackgroundResource", bg)
        v.setImageViewResource(ic, icon)
        v.setInt(ic, "setColorFilter", fg)
        if (tx != null) { v.setTextViewText(tx, text); v.setTextColor(tx, fg) }
        v.setOnClickPendingIntent(id, pi)
    }

    // ================= hlavný widget =================
    fun main(ctx: Context, m: MainModel, wDp: Int, hDp: Int, widgetId: Int, chartMode: Int, now: Long = System.currentTimeMillis()): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_car_large)
        val d = density(ctx)
        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        v.setOnClickPendingIntent(R.id.w_btn_refresh, broadcast(ctx, CarWidget.ACTION_REFRESH, 14))
        if (!m.loggedIn) {
            v.setTextViewText(R.id.w_status, "Trasník")
            v.setTextViewText(R.id.w_vehicle, "Prihláste sa v aplikácii")
            v.setViewVisibility(R.id.w_tiles, View.GONE)
            button(v, R.id.w_b1, R.id.w_b1_ic, R.id.w_b1_tx, R.drawable.ic_car, "Otvoriť", "primary", openApp(ctx))
            v.setViewVisibility(R.id.w_b2, View.GONE); v.setViewVisibility(R.id.w_b3, View.GONE)
            return v
        }
        val st = style(m.state)
        v.setInt(R.id.w_root, "setBackgroundResource", st.bg)
        v.setInt(R.id.w_dot, "setColorFilter", st.dot)
        v.setTextViewText(R.id.w_status, st.title)
        v.setTextViewText(R.id.w_vehicle, m.vehicle ?: "")

        // ---- rozpočet výšky (dp, zmerané na náhľadoch): hlavička + hlavný blok + dlaždice + tlačidlá, potom čo sa zmestí ----
        val h = if (hDp <= 0) 480 else hDp
        val compact = h < 235                 // 4 × 2: bez veľkého čísla (km v prvej dlaždici), počas jazdy menšia rýchlosť
        val liveNow = m.state == DriveState.DRIVING || m.state == DriveState.WALKING
        var used = when { compact -> 180; liveNow -> 262; else -> 220 }
        val showChart = h >= used + 102; if (showChart) used += 102
        val showTripRow = !liveNow && m.lastTrip != null && h >= used + 76; if (showTripRow) used += 76
        val infoText = m.reminder ?: m.parkingAddr?.let { a -> "Auto stojí" + (m.parkingSince?.let { " od ${whenText(it, now)}" } ?: "") + ": $a" }
        val showInfoRow = infoText != null && h >= used + 30

        // ---- živý blok alebo „dnes“ ----
        val live = m.state == DriveState.DRIVING || m.state == DriveState.WALKING
        v.setViewVisibility(R.id.w_live, if (live) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.w_hero, if (live || compact) View.GONE else View.VISIBLE)
        v.setTextViewTextSize(R.id.w_big, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 34f else 46f)
        if (live) {
            v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() - (now - m.liveStartedAt), null, true)
            if (m.state == DriveState.DRIVING) {
                v.setTextViewText(R.id.w_big, "${m.liveKmh}")
                v.setTextViewText(R.id.w_big_unit, "km/h")
                v.setTextViewText(R.id.w_live_sub, "${formatDistance(m.liveDistanceM)} · ${formatMoney(m.liveCost)}")
                val over = m.liveLimit != null && m.liveKmh > m.liveLimit
                v.setTextColor(R.id.w_big, if (over) Color.rgb(0xFC, 0xA5, 0xA5) else Color.WHITE)
                if (m.liveLimit != null) {
                    v.setViewVisibility(R.id.w_limit, View.VISIBLE)
                    v.setImageViewBitmap(R.id.w_limit, WidgetArt.limitSign(m.liveLimit, over, d))
                } else v.setViewVisibility(R.id.w_limit, View.GONE)
            } else {
                v.setTextViewText(R.id.w_big, String.format(SK, "%,d", m.liveSteps))
                v.setTextViewText(R.id.w_big_unit, "krokov")
                v.setTextColor(R.id.w_big, Color.WHITE)
                v.setTextViewText(R.id.w_live_sub, formatDistance(m.liveDistanceM))
                v.setViewVisibility(R.id.w_limit, View.GONE)
            }
        } else {
            v.setTextViewText(R.id.w_hero_val, km1(m.todayKm * 1000).let { if (m.todayKm >= 100) it.substringBefore(",") else it })
            v.setTextViewText(R.id.w_hero_sub, "najazdené dnes" + if (m.todayMs > 0) "\n${formatDuration(m.todayMs)} za volantom" else "")
        }

        // ---- dlaždice ----
        // počas jazdy v malom widgete dlaždice nie sú (živý blok je väčší)
        v.setViewVisibility(R.id.w_tiles, if (live && compact) View.GONE else View.VISIBLE)
        if (compact && !live) {
            v.setTextViewText(R.id.w_t1, km1(m.todayKm * 1000) + " km")
            v.setTextViewText(R.id.w_t1l, "dnes autom")
        } else {
            v.setTextViewText(R.id.w_t1, "${m.todayTrips}")
            v.setTextViewText(R.id.w_t1l, when (m.todayTrips) { 1 -> "jazda dnes"; in 2..4 -> "jazdy dnes"; else -> "jázd dnes" })
        }
        v.setTextViewText(R.id.w_t2, WidgetArt.compact(m.todaySteps))
        v.setTextViewText(R.id.w_t3, formatMoney(m.todayCost).replace(" €", " €"))
        v.setInt(R.id.w_t1i, "setColorFilter", WidgetArt.GREEN_L)
        v.setInt(R.id.w_t2i, "setColorFilter", WidgetArt.BLUE_L)
        v.setInt(R.id.w_t3i, "setColorFilter", Color.rgb(0xFD, 0xE6, 0x8A))

        // ---- graf + krúžok ----
        v.setViewVisibility(R.id.w_week_row, if (showChart) View.VISIBLE else View.GONE)
        if (showChart) {
            val chartW = ((wDp - 32 - 104 - 20).coerceAtLeast(120) * d).toInt()
            val chartH = (58 * d).toInt()
            val dots = (0 until CHART_COUNT).joinToString(" ") { if (it == chartMode) "●" else "○" }
            when (chartMode) {
                CHART_SPEED -> {
                    v.setImageViewBitmap(R.id.w_chart, WidgetArt.speedChart(m.speed, m.speedMax, m.speedAvg, chartW, chartH, d))
                    v.setTextViewText(R.id.w_week_title, "$dots   ${m.speedTitle}")
                }
                CHART_STEPS -> {
                    v.setImageViewBitmap(R.id.w_chart, WidgetArt.bars(m.weekSteps, chartW, chartH, d, WidgetArt.BLUE, WidgetArt.BLUE_L) { WidgetArt.compact(it.toInt()) })
                    v.setTextViewText(R.id.w_week_title, "$dots   Kroky · 7 dní · ${WidgetArt.compact(m.weekSteps.sumOf { it.second }.toInt())}")
                }
                else -> {
                    v.setImageViewBitmap(R.id.w_chart, WidgetArt.bars(m.week, chartW, chartH, d, WidgetArt.GREEN, WidgetArt.GREEN_L))
                    v.setTextViewText(R.id.w_week_title, "$dots   Km autom · 7 dní · ${formatDistance(m.week.sumOf { it.second } * 1000)}")
                }
            }
            v.setOnClickPendingIntent(R.id.w_chart_box, chartToggle(ctx, widgetId))
            v.setImageViewBitmap(R.id.w_ring, WidgetArt.ring(m.todaySteps, m.stepGoal, (86 * d).toInt(), d))
        }

        // ---- posledná jazda ----
        val t = m.lastTrip
        val showTrip = t != null && showTripRow
        v.setViewVisibility(R.id.w_trip, if (showTrip) View.VISIBLE else View.GONE)
        if (showTrip && t != null) {
            v.setImageViewBitmap(R.id.w_trip_map, WidgetArt.route(t.route, (60 * d).toInt(), (44 * d).toInt(), d))
            v.setTextViewText(R.id.w_trip_title, "${t.from} → ${t.to}")
            v.setTextViewText(R.id.w_trip_sub, "${whenText(t.startedAt, now)} · ${formatDistance(t.km * 1000)} · ${formatDuration(t.endedAt - t.startedAt)}")
            v.setTextViewText(R.id.w_trip_badge, if (t.business) "služobná" else "súkromná")
            v.setOnClickPendingIntent(R.id.w_trip, openTrip(ctx, t.id))
        }

        // ---- pripomienka / parkovanie ----
        val info = infoText
        val showInfo = showInfoRow
        v.setViewVisibility(R.id.w_info, if (showInfo) View.VISIBLE else View.GONE)
        if (showInfo) {
            v.setTextViewText(R.id.w_info_tx, info)
            val isRem = m.reminder != null
            v.setImageViewResource(R.id.w_info_ic, if (isRem) R.drawable.ic_w_bell else R.drawable.ic_parking)
            val col = when { isRem && m.reminderOverdue -> Color.rgb(0xFC, 0xA5, 0xA5); isRem -> Color.rgb(0xFD, 0xE6, 0x8A); else -> Color.rgb(0xCB, 0xD5, 0xE1) }
            v.setTextColor(R.id.w_info_tx, col); v.setInt(R.id.w_info_ic, "setColorFilter", col)
        }

        // ---- tlačidlá ----
        v.setViewVisibility(R.id.w_b2, View.VISIBLE); v.setViewVisibility(R.id.w_b3, View.VISIBLE)
        when (m.state) {
            DriveState.IDLE -> {
                button(v, R.id.w_b1, R.id.w_b1_ic, R.id.w_b1_tx, R.drawable.ic_car, "Jazda", "primary", service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                button(v, R.id.w_b2, R.id.w_b2_ic, R.id.w_b2_tx, R.drawable.ic_walk, "Chôdza", "secondary", service(ctx, TrackingService.ACTION_MANUAL_WALK, 13))
                if (m.parkingLat != null && m.parkingLng != null) button(v, R.id.w_b3, R.id.w_b3_ic, null, R.drawable.ic_w_nav, "", "secondary", findCar(ctx, m.parkingLat, m.parkingLng))
                else button(v, R.id.w_b3, R.id.w_b3_ic, null, R.drawable.ic_w_route, "", "secondary", openApp(ctx))
            }
            DriveState.CANDIDATE -> {
                button(v, R.id.w_b1, R.id.w_b1_ic, R.id.w_b1_tx, R.drawable.ic_w_check, "Je to jazda", "go", service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                button(v, R.id.w_b2, R.id.w_b2_ic, R.id.w_b2_tx, R.drawable.ic_w_close, "Nie je", "secondary", service(ctx, TrackingService.ACTION_STOP, 11))
                v.setViewVisibility(R.id.w_b3, View.GONE)
            }
            DriveState.DRIVING -> {
                button(v, R.id.w_b1, R.id.w_b1_ic, R.id.w_b1_tx, R.drawable.ic_w_stop, "Ukončiť jazdu", "danger", service(ctx, TrackingService.ACTION_STOP, 11))
                button(v, R.id.w_b2, R.id.w_b2_ic, R.id.w_b2_tx, R.drawable.ic_w_route, "Otvoriť", "secondary", openApp(ctx))
                v.setViewVisibility(R.id.w_b3, View.GONE)
            }
            DriveState.WALKING -> {
                button(v, R.id.w_b1, R.id.w_b1_ic, R.id.w_b1_tx, R.drawable.ic_w_stop, "Ukončiť chôdzu", "danger", service(ctx, TrackingService.ACTION_STOP, 11))
                button(v, R.id.w_b2, R.id.w_b2_ic, R.id.w_b2_tx, R.drawable.ic_car, "Jazda", "secondary", service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                v.setViewVisibility(R.id.w_b3, View.GONE)
            }
        }
        return v
    }

    // ================= rýchle tlačidlá =================
    fun quick(ctx: Context, loggedIn: Boolean, state: DriveState, liveKmh: Int, liveDistanceM: Double, liveSteps: Int, todayM: Double = 0.0): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
        v.setOnClickPendingIntent(R.id.q_root, openApp(ctx))
        val st = style(state)
        v.setInt(R.id.q_root, "setBackgroundResource", st.bg)
        v.setInt(R.id.q_dot, "setColorFilter", st.dot)
        v.setTextViewText(R.id.q_status, if (loggedIn) st.title else "Prihláste sa")
        v.setTextViewText(R.id.q_info, when (state) {
            DriveState.DRIVING -> "$liveKmh km/h · ${formatDistance(liveDistanceM)}"
            DriveState.WALKING -> "${WidgetArt.compact(liveSteps)} krokov · ${formatDistance(liveDistanceM)}"
            DriveState.CANDIDATE -> "Potvrďte alebo zrušte"
            DriveState.IDLE -> if (todayM > 0) "dnes ${formatDistance(todayM)}" else "dnes bez jázd"
        })
        if (!loggedIn) {
            button(v, R.id.q_btn1, R.id.q_btn1_ic, R.id.q_btn1_tx, R.drawable.ic_car, "Otvoriť", "primary", openApp(ctx))
            v.setViewVisibility(R.id.q_btn2, View.GONE); return v
        }
        when (state) {
            DriveState.IDLE -> {
                button(v, R.id.q_btn1, R.id.q_btn1_ic, R.id.q_btn1_tx, R.drawable.ic_car, "Jazda", "primary", service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                button(v, R.id.q_btn2, R.id.q_btn2_ic, null, R.drawable.ic_walk, "", "secondary", service(ctx, TrackingService.ACTION_MANUAL_WALK, 13))
            }
            DriveState.CANDIDATE -> {
                button(v, R.id.q_btn1, R.id.q_btn1_ic, R.id.q_btn1_tx, R.drawable.ic_w_check, "Jazda", "go", service(ctx, TrackingService.ACTION_MANUAL_START, 12))
                button(v, R.id.q_btn2, R.id.q_btn2_ic, null, R.drawable.ic_w_close, "", "secondary", service(ctx, TrackingService.ACTION_STOP, 11))
            }
            DriveState.DRIVING, DriveState.WALKING -> {
                button(v, R.id.q_btn1, R.id.q_btn1_ic, R.id.q_btn1_tx, R.drawable.ic_w_stop, "Ukončiť", "danger", service(ctx, TrackingService.ACTION_STOP, 11))
                button(v, R.id.q_btn2, R.id.q_btn2_ic, null, R.drawable.ic_w_route, "", "secondary", openApp(ctx))
            }
        }
        return v
    }

    // ================= kroky =================
    fun steps(ctx: Context, m: StepsModel, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_steps)
        val d = density(ctx)
        v.setOnClickPendingIntent(R.id.s_root, openApp(ctx))
        v.setImageViewBitmap(R.id.s_ring, WidgetArt.ring(m.steps, m.goal, (96 * d).toInt(), d))
        v.setTextViewText(R.id.s_big, String.format(SK, "%,d", m.steps))
        v.setTextViewText(R.id.s_left, when {
            m.goal <= 0 -> "krokov dnes"
            m.steps >= m.goal -> "Denný cieľ splnený 🎉"
            else -> "Do cieľa ešte ${WidgetArt.compact(m.goal - m.steps)}"
        })
        v.setTextViewText(R.id.s_km, formatDistance(m.km * 1000))
        if (m.streak > 0 || m.best > 0) {
            v.setViewVisibility(R.id.s_streak, View.VISIBLE)
            v.setTextViewText(R.id.s_streak, if (m.streak > 0) "🔥 ${m.streak} ${dni(m.streak)}" else "rekord ${m.best} ${dni(m.best)}")
        } else v.setViewVisibility(R.id.s_streak, View.GONE)
        val showWeek = hDp == 0 || hDp >= 150
        v.setViewVisibility(R.id.s_week, if (showWeek) View.VISIBLE else View.GONE)
        if (showWeek) v.setImageViewBitmap(R.id.s_week, WidgetArt.bars(m.week, ((wDp - 28).coerceAtLeast(140) * d).toInt(),
            (max(44, (hDp.takeIf { it > 0 } ?: 170) - 140) * d).toInt().coerceAtMost((90 * d).toInt()), d, WidgetArt.BLUE, WidgetArt.BLUE_L) { WidgetArt.compact(it.toInt()) })
        return v
    }

    // ================= parkovanie =================
    fun parking(ctx: Context, m: ParkingModel, now: Long = System.currentTimeMillis()): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_parking)
        v.setOnClickPendingIntent(R.id.p_root, openApp(ctx))
        v.setInt(R.id.p_root, "setBackgroundResource", if (m.driving) R.drawable.wg_bg_drive else R.drawable.wg_bg_idle)
        if (m.addr == null && m.lat == null || m.driving) {
            v.setTextViewText(R.id.p_addr, if (m.driving) "Práve jazdíte" else "Žiadne uložené parkovanie")
            v.setTextViewText(R.id.p_since, if (m.driving) "uloží sa po jazde" else "")
            v.setViewVisibility(R.id.p_find, View.GONE)
        } else {
            v.setViewVisibility(R.id.p_find, View.VISIBLE)
            v.setTextViewText(R.id.p_addr, m.addr ?: String.format(Locale.US, "%.5f, %.5f", m.lat, m.lng))
            // čas státia beží priamo vo widgete (Chronometer) – nezávisí od toho, kedy sa widget naposledy obnovil
            v.setTextViewText(R.id.p_since, m.since?.let { "od ${whenText(it, now)} ·" } ?: "")
            if (m.since != null) {
                v.setViewVisibility(R.id.p_since_chrono, View.VISIBLE)
                v.setChronometer(R.id.p_since_chrono, SystemClock.elapsedRealtime() - (now - m.since), null, true)
            }
            if (m.lat != null && m.lng != null) button(v, R.id.p_find, R.id.p_find_ic, R.id.p_find_tx, R.drawable.ic_w_nav, "Navigovať k autu", "primary", findCar(ctx, m.lat, m.lng))
        }
        val until = m.ticketUntil?.takeIf { !m.driving }
        if (until != null) {
            v.setViewVisibility(R.id.p_ticket, View.VISIBLE)
            val left = until - now
            if (left > 0) {
                v.setTextViewText(R.id.p_ticket_txt, "Lístok do ${formatTime(until)}")
                v.setChronometer(R.id.p_chrono, SystemClock.elapsedRealtime() + left, null, true)
                v.setChronometerCountDown(R.id.p_chrono, true)
                v.setViewVisibility(R.id.p_chrono, View.VISIBLE)
                v.setTextColor(R.id.p_chrono, if (left < 10 * 60_000L) Color.rgb(0xFC, 0xA5, 0xA5) else Color.WHITE)
            } else {
                v.setTextViewText(R.id.p_ticket_txt, "Lístok vypršal o ${formatTime(until)}")
                v.setViewVisibility(R.id.p_chrono, View.GONE)
            }
        } else v.setViewVisibility(R.id.p_ticket, View.GONE)
        button(v, R.id.p_open, R.id.p_open_ic, R.id.p_open_tx, R.drawable.ic_w_ticket, if (until != null) "Zmeniť" else "Lístok", "secondary", openApp(ctx))
        return v
    }

    // ================= mesiac =================
    fun month(ctx: Context, m: MonthModel, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_month)
        val d = density(ctx)
        v.setOnClickPendingIntent(R.id.m_root, openApp(ctx))
        v.setTextViewText(R.id.m_status, m.monthName)
        if (m.vsPct != null) {
            v.setViewVisibility(R.id.m_vs, View.VISIBLE)
            v.setTextViewText(R.id.m_vs, (if (m.vsPct >= 0) "▲ ${m.vsPct} %" else "▼ ${-m.vsPct} %") + if (m.prevMonth.isNotEmpty()) " vs. ${m.prevMonth}" else "")
            v.setInt(R.id.m_vs, "setBackgroundResource", if (m.vsPct >= 0) R.drawable.wg_chip_up else R.drawable.wg_chip_down)
        } else v.setViewVisibility(R.id.m_vs, View.GONE)
        v.setTextViewText(R.id.m1, String.format(SK, "%,.0f", m.km))
        v.setTextViewText(R.id.m_sub, "${m.trips} ${when (m.trips) { 1 -> "jazda"; in 2..4 -> "jazdy"; else -> "jázd" }}\nza ${m.days.size} ${dni(m.days.size)}")
        v.setTextViewText(R.id.m4, String.format(SK, "%,.0f km", m.businessKm))
        v.setTextViewText(R.id.m5, WidgetArt.compact(m.steps))
        v.setTextViewText(R.id.m6, formatMoney(m.cost))
        v.setInt(R.id.m4i, "setColorFilter", WidgetArt.VIOLET)
        v.setInt(R.id.m5i, "setColorFilter", WidgetArt.BLUE_L)
        v.setInt(R.id.m6i, "setColorFilter", Color.rgb(0xFD, 0xE6, 0x8A))
        val showTiles = hDp == 0 || hDp >= 150
        v.setViewVisibility(R.id.m_tiles, if (showTiles) View.VISIBLE else View.GONE)
        val showChart = hDp == 0 || hDp >= 190
        v.setViewVisibility(R.id.m_chart, if (showChart) View.VISIBLE else View.GONE)
        if (showChart) v.setImageViewBitmap(R.id.m_chart, WidgetArt.bars(m.days, ((wDp - 28).coerceAtLeast(160) * d).toInt(),
            (max(40, (hDp.takeIf { it > 0 } ?: 230) - 175) * d).toInt().coerceAtMost((80 * d).toInt()), d, WidgetArt.GREEN, WidgetArt.GREEN_L))
        return v
    }

    // ================= posledná jazda =================
    fun trip(ctx: Context, t: TripSummary?, wDp: Int, hDp: Int, now: Long = System.currentTimeMillis()): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_trip)
        val d = density(ctx)
        if (t == null) {
            v.setOnClickPendingIntent(R.id.t_root, openApp(ctx))
            v.setTextViewText(R.id.t_title, "Zatiaľ žiadna jazda autom")
            v.setTextViewText(R.id.t_stats, "Po prvej jazde sa tu zobrazí trasa a údaje.")
            v.setViewVisibility(R.id.t_seg, View.GONE)
            v.setImageViewBitmap(R.id.t_map, WidgetArt.route(emptyList(), (104 * d).toInt(), (80 * d).toInt(), d))
            return v
        }
        v.setOnClickPendingIntent(R.id.t_root, openTrip(ctx, t.id))
        v.setTextViewText(R.id.t_when, whenText(t.startedAt, now) + "–" + formatTime(t.endedAt))
        v.setTextViewText(R.id.t_title, "${t.from} → ${t.to}")
        v.setTextViewText(R.id.t_km, String.format(SK, "%.1f", t.km))
        v.setTextViewText(R.id.t_stats, "${formatDuration(t.endedAt - t.startedAt)} · Ø ${t.avgKmh.toInt()} km/h · ${formatMoney(t.cost)}" + (t.vehicle?.let { "\n$it" } ?: ""))
        if (t.score != null) {
            v.setViewVisibility(R.id.t_score, View.VISIBLE)
            v.setTextViewText(R.id.t_score, "štýl ${t.score}")
            v.setInt(R.id.t_score, "setBackgroundResource", when { t.score >= 85 -> R.drawable.wg_chip_up; t.score >= 70 -> R.drawable.wg_chip_warn; else -> R.drawable.wg_chip_down })
        } else v.setViewVisibility(R.id.t_score, View.GONE)
        val mapH = ((hDp.takeIf { it > 0 } ?: 180) - 100).coerceIn(60, 180)
        v.setImageViewBitmap(R.id.t_map, WidgetArt.route(t.route, (104 * d).toInt(), (mapH * d).toInt(), d))
        val showSeg = hDp == 0 || hDp >= 150
        v.setViewVisibility(R.id.t_seg, if (showSeg) View.VISIBLE else View.GONE)
        fun seg(id: Int, on: Boolean) {
            v.setInt(id, "setBackgroundResource", if (on) R.drawable.wg_seg_on else R.drawable.wg_seg_off)
            v.setTextColor(id, if (on) DARK else Color.WHITE)
        }
        seg(R.id.t_priv, !t.business); seg(R.id.t_biz, t.business)
        v.setTextViewText(R.id.t_priv, if (!t.business) "✓ Súkromná" else "Súkromná")
        v.setTextViewText(R.id.t_biz, if (t.business) "✓ Služobná" else "Služobná")
        v.setOnClickPendingIntent(R.id.t_priv, category(ctx, t.uuid, CATEGORY_PRIVATE, 70))
        v.setOnClickPendingIntent(R.id.t_biz, category(ctx, t.uuid, CATEGORY_BUSINESS, 71))
        return v
    }

    // ================= pomocné =================
    private fun dni(n: Int) = when (n) { 1 -> "deň"; in 2..4 -> "dni"; else -> "dní" }

    fun whenText(ts: Long, now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val today = c.timeInMillis
        return when {
            ts >= today -> "dnes ${formatTime(ts)}"
            ts >= today - 86_400_000L -> "včera ${formatTime(ts)}"
            else -> "${formatShortDate(ts)} ${formatTime(ts)}"
        }
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openTrip(ctx: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        ctx, 20, Intent(ctx, MainActivity::class.java).setAction("sk.cartracker.shortcut.TRIP").putExtra("trip_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun findCar(ctx: Context, lat: Double, lng: Double): PendingIntent {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=w"))
        val target = if (nav.resolveActivity(ctx.packageManager) != null) nav else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$lat,$lng(Moje auto)"))
        return PendingIntent.getActivity(ctx, 60, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun category(ctx: Context, uuid: String, cat: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, CategoryActionReceiver::class.java).setAction(CategoryActionReceiver.ACTION)
            .putExtra(CategoryActionReceiver.EXTRA_UUID, uuid).putExtra(CategoryActionReceiver.EXTRA_CATEGORY, cat)
            .putExtra(CategoryActionReceiver.EXTRA_SILENT, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, CarWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Ťuknutie na graf prepne jeho obsah (km / rýchlosť / kroky) – pre každý widget zvlášť. */
    private fun chartToggle(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1000 + id,
        Intent(ctx, CarWidget::class.java).setAction(CarWidget.ACTION_CHART).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Ťuknutie na widget = interakcia používateľa → služba v popredí sa smie spustiť. */
    fun service(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        ctx, code, Intent(ctx, TrackingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
