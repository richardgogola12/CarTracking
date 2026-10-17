package sk.cartracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import sk.cartracker.R
import sk.cartracker.appScope
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.data.VibrationPattern
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatDistance
import sk.cartracker.ui.formatDuration

object Notifications {
    const val CH_TRACKING = "tracking"
    const val CH_EVENTS = "drive_events"
    const val CH_EVENTS_SILENT = "drive_events_silent"
    const val CH_REMINDERS = "reminders"
    const val ID_ONGOING = 1
    private const val ID_EVENT = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, "Prebiehajúce sledovanie", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Trvalá notifikácia počas jazdy (vyžaduje ju Android pre beh na pozadí)"
                setShowBadge(false)
            }
        )
        // Vibrácie riadime sami podľa nastavení, preto ich na kanáloch vypíname
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Zapnutie / vypnutie režimu jazdy", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Oznámenie so zvukom, keď sa jazda začne alebo skončí"
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Pripomienky (STK, olej, známka…)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Blížiace sa termíny pri vozidlách"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS_SILENT, "Zapnutie / vypnutie režimu jazdy (tiché)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Oznámenie bez zvuku"
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun openAppIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(ctx: Context, state: DriveState, live: LiveTrip?, s: AppSettings): Notification {
        val title = when (state) {
            DriveState.CANDIDATE -> "Overujem, či ide o jazdu autom…"
            DriveState.DRIVING -> "Režim jazdy je zapnutý" + (live?.vehicleName?.let { " · $it" } ?: "")
            DriveState.WALKING -> "🚶 Chôdza sa zaznamenáva"
            DriveState.IDLE -> "Trasník"
        }
        val text = when {
            state == DriveState.DRIVING && live != null -> buildString {
                append(formatDistance(live.distanceM)).append(" · ").append(formatDuration(System.currentTimeMillis() - live.startedAt))
                if (s.showSpeedInNotification) append(" · ").append(live.speedKmh.toInt()).append(" km/h")
                live.limitKmh?.let { append(" · limit ").append(it) }
            }
            state == DriveState.WALKING && live != null ->
                "${live.steps} krokov · ${formatDistance(live.distanceM)} · ${formatDuration(System.currentTimeMillis() - live.startedAt)}"
            else -> "Čakám na potvrdenie rýchlosťou (chôdza a bicykel sa ignorujú)"
        }

        val stopIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, NotificationActionReceiver::class.java).setAction(TrackingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(ctx))
            .apply {
                if (state == DriveState.DRIVING) addAction(0, "Ukončiť jazdu", stopIntent)
                else if (state == DriveState.WALKING) addAction(0, "Ukončiť chôdzu", stopIntent)
                else addAction(0, "Nie je to jazda", stopIntent)
            }
            .build()
    }

    /** Jazda, pri ktorej sa po skončení pýta účel (tlačidlá Služobná / Súkromná). */
    data class Classify(val tripUuid: String, val category: String, val reason: String)

    fun driveEvent(ctx: Context, started: Boolean, s: AppSettings, summary: String? = null, classify: Classify? = null) {
        if (s.vibrate) vibrate(ctx, s.vibrationPattern)
        SoundPlayer.event(ctx, if (started) SoundEvent.DRIVE_START else SoundEvent.DRIVE_END, s)
        if (!started && classify != null && s.askCategory) { classifyNotification(ctx, s, summary, classify); return }
        if ((started && !s.notifyOnStart) || (!started && !s.notifyOnStop)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val n = NotificationCompat.Builder(ctx, CH_EVENTS_SILENT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(if (started) "🚗 Režim jazdy ZAPNUTÝ" else "🅿️ Režim jazdy VYPNUTÝ")
            .setContentText(summary ?: if (started) "Sledujem polohu počas jazdy" else "Jazda bola uložená")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_EVENT, n)
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun categoryLabel(cat: String) = if (cat == sk.cartracker.data.local.CATEGORY_BUSINESS) "Služobná" else "Súkromná"

    /** Koniec jazdy s otázkou na účel – ťuknutím na tlačidlo sa jazda zaradí (a aplikácia sa trasu naučí). */
    private fun classifyNotification(ctx: Context, s: AppSettings, summary: String?, c: Classify) {
        if (!canNotify(ctx)) return
        NotificationManagerCompat.from(ctx).cancel(ID_EVENT)
        fun action(cat: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, CategoryActionReceiver::class.java).setAction(CategoryActionReceiver.ACTION)
                .putExtra(CategoryActionReceiver.EXTRA_UUID, c.tripUuid).putExtra(CategoryActionReceiver.EXTRA_CATEGORY, cat),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = (summary ?: "Jazda bola uložená") + "\nÚčel: ${categoryLabel(c.category)} (${c.reason}) – zmeňte tlačidlom nižšie"
        val n = NotificationCompat.Builder(ctx, CH_EVENTS_SILENT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("🅿️ Jazda skončila · ${categoryLabel(c.category)}?")
            .setContentText(summary ?: "Služobná alebo súkromná?")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .addAction(0, (if (c.category == sk.cartracker.data.local.CATEGORY_BUSINESS) "✓ " else "") + "Služobná", action(sk.cartracker.data.local.CATEGORY_BUSINESS, 51))
            .addAction(0, (if (c.category != sk.cartracker.data.local.CATEGORY_BUSINESS) "✓ " else "") + "Súkromná", action(sk.cartracker.data.local.CATEGORY_PRIVATE, 52))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_CLASSIFY, n)
    }

    /** Potvrdenie po ťuknutí na tlačidlo – zmizne o 4 s. */
    fun classified(ctx: Context, text: String) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_EVENTS_SILENT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(text)
            .setSilent(true)
            .setTimeoutAfter(4_000)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_CLASSIFY, n)
    }

    /** Začiatok / koniec chôdze – predvolene bez notifikácie a vibrácie (v práci by rušili). */
    fun walkEvent(ctx: Context, started: Boolean, s: AppSettings, summary: String? = null) {
        if (s.walkVibrate) vibrate(ctx, VibrationPattern.SHORT)
        if (started || summary != null) SoundPlayer.event(ctx, if (started) SoundEvent.WALK_START else SoundEvent.WALK_END, s)
        if (!s.walkNotify) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!started && summary == null) return
        val n = NotificationCompat.Builder(ctx, CH_EVENTS_SILENT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(if (started) "🚶 Chôdza sa zaznamenáva" else "🚶 Chôdza ukončená")
            .setContentText(summary ?: "Počítam kroky a trasu")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openAppIntent(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_EVENT, n)
    }

    fun reminder(ctx: Context, r: sk.cartracker.data.local.ReminderEntity, st: sk.cartracker.data.ReminderStatus, vehicle: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val parts = listOfNotNull(
            st.daysLeft?.let { if (it < 0) "po termíne ${-it} dní" else if (it == 0L) "dnes" else "o $it dní" },
            st.kmLeft?.let { if (it < 0) "prekročené o ${-it} km" else "o $it km" },
            vehicle,
        )
        val n = NotificationCompat.Builder(ctx, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle((if (st.state == sk.cartracker.data.ReminderState.OVERDUE) "⚠️ " else "🔔 ") + r.title)
            .setContentText(parts.joinToString(" · "))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(1000 + (r.id % 1000).toInt(), n)
    }

    /** Upozornenie na prekročenie nastavenej rýchlosti (3 krátke vibrácie). */
    fun speedAlert(ctx: Context, speedKmh: Int, limitKmh: Int, zone: String?, s: AppSettings) {
        if (s.vibrate) vibrateRaw(ctx, longArrayOf(0, 150, 100, 150, 100, 150))
        SoundPlayer.event(ctx, SoundEvent.SPEEDING, s)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CH_EVENTS_SILENT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("⚠️ Rýchlosť $speedKmh km/h")
            .setContentText("Limit $limitKmh km/h" + (zone?.let { " ($it)" } ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_SPEED, n)
    }

    private const val ID_SPEED = 3
    const val ID_CLASSIFY = 4

    private fun vibrateRaw(ctx: Context, timings: LongArray) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    fun vibrate(ctx: Context, pattern: VibrationPattern) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        val timings = when (pattern) {
            VibrationPattern.SHORT -> longArrayOf(0, 200)
            VibrationPattern.LONG -> longArrayOf(0, 900)
            VibrationPattern.DOUBLE -> longArrayOf(0, 300, 200, 300)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }
}

/** Tlačidlo "Ukončiť jazdu" v notifikácii. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == TrackingService.ACTION_STOP) TrackingService.signal(ctx, TrackingService.ACTION_STOP)
    }
}

/** Tlačidlá Služobná / Súkromná v notifikácii po skončení jazdy. */
class CategoryActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "sk.cartracker.action.SET_CATEGORY"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_CATEGORY = "category"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val uuid = intent.getStringExtra(EXTRA_UUID) ?: return
        val cat = intent.getStringExtra(EXTRA_CATEGORY) ?: return
        val pending = goAsync()
        val app = ctx.applicationContext
        app.appScope.launch {
            try {
                val c = app.container
                val t = c.db.tripDao().tripByUuid(uuid)
                if (t == null) { Notifications.classified(app, "Jazda sa nenašla (bola zlúčená alebo zmazaná)"); return@launch }
                c.trips.updateMeta(t, cat, t.note)
                c.settings.update { sk.cartracker.data.TripClassifier.learn(it, t, cat) }
                val s = c.settings.current()
                if (s.autoSync) sk.cartracker.sync.SyncWorker.enqueueNow(app, s.syncWifiOnly)
                sk.cartracker.data.DiagLog.log(app, sk.cartracker.data.DiagLog.APP, "Účel jazdy z notifikácie: ${Notifications.categoryLabel(cat)}")
                Notifications.classified(app, "✓ Jazda je ${Notifications.categoryLabel(cat).lowercase()}" + if (s.learnCategory) " – zapamätané pre túto trasu" else "")
            } finally { pending.finish() }
        }
    }
}
