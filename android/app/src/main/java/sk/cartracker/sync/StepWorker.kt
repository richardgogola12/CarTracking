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
        val s = c.settings.current()
        if (!s.allDaySteps) return Result.success()
        runCatching { StepCounter.sample(applicationContext) }
        // odoslanie na server podľa nastaveného intervalu (s rezervou 5 min, worker nebeží presne)
        // worker nebeží presne – s rezervou (napr. pri intervale 15 min aj po 13 min)
        val due = System.currentTimeMillis() - StepCounter.lastSync(applicationContext) >= (s.stepSyncMin.coerceIn(1, 1440) * 60_000L * 85 / 100)
        if (due && StepCounter.syncDue(applicationContext, s.copy(stepSyncMin = 1))) runCatching { c.steps.sync() }
        WidgetHook.update(applicationContext)
        return Result.success()
    }

    companion object {
        /** Naplánuje čítanie krokomera každých [minutes] min (Android dovolí najmenej 15), alebo ho zruší. */
        fun schedule(ctx: Context, enabled: Boolean, minutes: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork("steps"); return }
            val p = ctx.getSharedPreferences("step_counter", Context.MODE_PRIVATE)
            val m = minutes.coerceAtLeast(15)
            // pri zmene intervalu plán nahradiť, inak ponechať (nech sa pri každom štarte nereštartuje)
            val policy = if (p.getInt("scheduled_min", -1) == m) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
            wm.enqueueUniquePeriodicWork("steps", policy, PeriodicWorkRequestBuilder<StepWorker>(m.toLong(), TimeUnit.MINUTES).build())
            p.edit().putInt("scheduled_min", m).apply()
        }
    }
}
