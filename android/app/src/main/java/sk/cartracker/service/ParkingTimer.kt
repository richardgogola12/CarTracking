package sk.cartracker.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import sk.cartracker.R
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.RuntimeState
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatTime

/**
 * Parkovací lístok: používateľ zadá, dokedy má zaplatené parkovanie.
 * Upozornenie príde [WARN_MIN] minút pred koncom a znova pri skončení.
 */
object ParkingTimer {
    const val WARN_MIN = 10
    const val CH_PARKING = "parking_v2"   // nový kanál bez systémového zvuku (pôvodný „parking“ sa zmaže)
    private const val ID_NOTIF = 40
    private const val PREFS = "runtime"
    internal const val ACTION_WARN = "sk.cartracker.parking.WARN"
    internal const val ACTION_END = "sk.cartracker.parking.END"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Čas konca lístka (epoch ms) alebo null. */
    fun until(ctx: Context): Long? = prefs(ctx).getLong("park_until", 0L).takeIf { it > 0 }

    fun createChannel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).deleteNotificationChannel("parking") }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_PARKING, "Parkovací lístok", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Upozornenie pred koncom zaplateného parkovania (zvuk sa nastavuje v aplikácii: Nastavenia → Zvuky)"
                setSound(null, null)
            }
        )
    }

    fun set(ctx: Context, until: Long) {
        prefs(ctx).edit().putLong("park_until", until).apply()
        schedule(ctx, ACTION_WARN, until - WARN_MIN * 60_000L, 41)
        schedule(ctx, ACTION_END, until, 42)
        sk.cartracker.widget.CarWidget.updateAll(ctx)
    }

    /** Po reštarte telefónu sa budíky stratia – znova ich naplánujeme. */
    fun reschedule(ctx: Context) {
        val u = until(ctx) ?: return
        schedule(ctx, ACTION_WARN, u - WARN_MIN * 60_000L, 41)
        schedule(ctx, ACTION_END, u, 42)
    }

    fun cancel(ctx: Context) {
        if (until(ctx) == null) return
        prefs(ctx).edit().remove("park_until").apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, ACTION_WARN, 41))
        am.cancel(pending(ctx, ACTION_END, 42))
        NotificationManagerCompat.from(ctx).cancel(ID_NOTIF)
        sk.cartracker.widget.CarWidget.updateAll(ctx)
    }

    private fun pending(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, ParkingAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(ctx: Context, action: String, at: Long, code: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, action, code)
        if (at <= System.currentTimeMillis()) { am.cancel(pi); return }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    internal fun notify(ctx: Context, end: Boolean) {
        val until = until(ctx) ?: return
        val app = ctx.applicationContext
        app.appScope.launch { SoundPlayer.event(app, SoundEvent.PARKING, app.container.settings.current()) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val park = RuntimeState.parking(ctx)
        val open = PendingIntent.getActivity(ctx, 43, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, CH_PARKING)
            .setSmallIcon(R.drawable.ic_parking)
            .setContentTitle(if (end) "🅿️ Parkovací lístok vypršal" else "🅿️ Parkovanie končí o $WARN_MIN min")
            .setContentText((if (end) "Zaplatené bolo do " else "Zaplatené do ") + formatTime(until) + (park?.address?.let { " · $it" } ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (park != null) {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${park.lat},${park.lng}&mode=w"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${park.lat},${park.lng}(Moje auto)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = if (nav.resolveActivity(ctx.packageManager) != null) nav else fallback
            b.addAction(0, "Nájsť auto", PendingIntent.getActivity(ctx, 44, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        NotificationManagerCompat.from(ctx).notify(ID_NOTIF, b.build())
        sk.cartracker.widget.CarWidget.updateAll(ctx)
    }
}

class ParkingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ParkingTimer.ACTION_WARN -> ParkingTimer.notify(ctx, end = false)
            ParkingTimer.ACTION_END -> ParkingTimer.notify(ctx, end = true)
        }
    }
}
