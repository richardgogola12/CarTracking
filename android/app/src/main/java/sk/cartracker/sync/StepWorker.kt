package sk.cartracker.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import sk.cartracker.container
import sk.cartracker.data.StepCounter
import java.util.concurrent.TimeUnit

/** Každých 15 min prečíta krokomer (celodenné kroky) a raz za hodinu ich odošle na server. */
class StepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.session.isLoggedIn) return Result.success()
        runCatching { StepCounter.sample(applicationContext) }
        val p = applicationContext.getSharedPreferences("step_counter", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("synced_at", 0L) > 55 * 60_000L && c.settings.current().autoSync) {
            if (runCatching { c.steps.sync() }.isSuccess) p.edit().putLong("synced_at", now).apply()
        }
        WidgetHook.update(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("steps", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
