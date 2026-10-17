package sk.cartracker.sync

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import sk.cartracker.R
import sk.cartracker.container
import sk.cartracker.data.cost
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.service.Notifications
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatMoney
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Prvý deň v mesiaci pošle zhrnutie predchádzajúceho mesiaca s porovnaním. */
object MonthlySummary {
    val MONTHS = listOf("január", "február", "marec", "apríl", "máj", "jún", "júl", "august", "september", "október", "november", "december")

    data class Totals(val km: Double, val trips: Int, val cost: Double, val businessKm: Double, val steps: Int, val walkKm: Double)

    fun monthStart(year: Int, month: Int): Long = Calendar.getInstance().apply {
        clear(); set(year, month, 1)
    }.timeInMillis

    suspend fun totals(ctx: Context, from: Long, to: Long): Totals {
        val c = ctx.container
        val s = c.settings.current()
        val vehicles = c.vehicles.all().associateBy { it.uuid }
        val trips = c.trips.tripsBetween(from, to)
        val cars = trips.filter { !it.isWalk }
        val walks = trips.filter { it.isWalk }
        return Totals(
            cars.sumOf { it.distanceM } / 1000.0, cars.size,
            cars.sumOf { it.cost(vehicles[it.vehicleUuid], s) },
            cars.filter { it.category == CATEGORY_BUSINESS }.sumOf { it.distanceM } / 1000.0,
            walks.sumOf { it.steps }, walks.sumOf { it.distanceM } / 1000.0,
        )
    }

    suspend fun maybeNotify(ctx: Context) {
        val c = ctx.container
        if (!c.settings.current().monthlySummary) return
        val now = Calendar.getInstance()
        val prev = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val key = prev.get(Calendar.YEAR) * 100 + prev.get(Calendar.MONTH)
        val prefs = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        val last = prefs.getInt("monthly_summary_key", 0)
        if (last == 0) { prefs.edit().putInt("monthly_summary_key", key).apply(); return } // prvé spustenie: neposielať hneď
        if (last >= key) return

        val pFrom = monthStart(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH))
        val pTo = monthStart(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
        val before = (prev.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val bFrom = monthStart(before.get(Calendar.YEAR), before.get(Calendar.MONTH))
        val t = totals(ctx, pFrom, pTo)
        val b = totals(ctx, bFrom, pFrom)
        prefs.edit().putInt("monthly_summary_key", key).apply()
        if (t.trips == 0 && t.steps == 0) return

        fun diff(a: Double, x: Double): String {
            if (x <= 0.0) return ""
            val p = ((a - x) / x * 100).roundToInt()
            return if (p == 0) " (=)" else if (p > 0) " (▲ $p %)" else " (▼ ${-p} %)"
        }
        val name = MONTHS[prev.get(Calendar.MONTH)]
        val lines = buildList {
            if (t.trips > 0) {
                add("🚗 ${formatDistance(t.km * 1000)}${diff(t.km, b.km)} · ${t.trips} jázd")
                add("⛽ ${formatMoney(t.cost)}${diff(t.cost, b.cost)}")
                if (t.businessKm > 0) add("💼 služobne ${formatDistance(t.businessKm * 1000)}")
            }
            if (t.steps > 0) add("🚶 ${String.format(Locale("sk"), "%,d", t.steps)} krokov${diff(t.steps.toDouble(), b.steps.toDouble())} · ${formatDistance(t.walkKm * 1000)}")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 50, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, Notifications.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("📊 Zhrnutie: ${name.replaceFirstChar { it.uppercase() }} ${prev.get(Calendar.YEAR)}")
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n") + "\n(porovnanie s predchádzajúcim mesiacom)"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply {
                // kniha jázd za minulý mesiac jedným ťuknutím
                if (t.trips > 0) {
                    fun act(code: Int, what: String) = PendingIntent.getActivity(ctx, code,
                        Intent(ctx, MainActivity::class.java).setAction("sk.cartracker.shortcut.$what").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    addAction(R.drawable.ic_car, "Kniha jázd – PDF", act(52, "LOGBOOK_PDF"))
                    addAction(R.drawable.ic_car, "Kniha jázd – Excel", act(53, "LOGBOOK_CSV"))
                }
            }
            .build()
        NotificationManagerCompat.from(ctx).notify(51, n)
    }
}
