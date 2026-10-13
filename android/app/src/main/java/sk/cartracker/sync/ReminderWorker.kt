package sk.cartracker.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import sk.cartracker.container
import sk.cartracker.data.ReminderState
import sk.cartracker.service.Notifications
import java.util.concurrent.TimeUnit

/** 2× denne skontroluje pripomienky (STK, olej…) a upozorní na blížiace sa termíny. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.session.isLoggedIn) return Result.success()
        val now = System.currentTimeMillis()
        for (r in c.reminders.active()) {
            val st = c.reminders.status(r, now)
            if (st.state != ReminderState.SOON && st.state != ReminderState.OVERDUE) continue
            // blížiaci sa termín pripomenúť raz za 3 dni, po termíne raz denne
            val every = if (st.state == ReminderState.OVERDUE) 86_400_000L else 3 * 86_400_000L
            if (now - r.lastNotifiedAt < every) continue
            val vehicle = c.vehicles.byUuid(r.vehicleUuid)
            Notifications.reminder(applicationContext, r, st, vehicle?.label)
            c.reminders.save(r.copy(lastNotifiedAt = now))
        }
        runCatching { MonthlySummary.maybeNotify(applicationContext) }
        WidgetHook.update(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Aktualizácia widgetu z miest, ktoré nepoznajú UI (služba, worker). */
object WidgetHook {
    fun update(ctx: Context) = sk.cartracker.widget.CarWidget.updateAll(ctx)
}
