package sk.cartracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import retrofit2.HttpException
import sk.cartracker.container
import sk.cartracker.data.ServerRestore
import java.util.concurrent.TimeUnit

/** Stiahne všetky údaje a nastavenia zo servera po prihlásení – beží na pozadí, aj keď sa aplikácia zavrie. */
class RestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.session.isLoggedIn) { ServerRestore.setPending(applicationContext, false); return Result.success() }
        return try {
            val sum = ServerRestore.run(applicationContext) { step, done, total ->
                setProgress(workDataOf(KEY_STEP to step, KEY_DONE to done, KEY_TOTAL to total))
            }
            finish()
            Result.success(workDataOf(KEY_RESULT to sum.toString()))
        } catch (e: HttpException) {
            if (e.code() == 401 || runAttemptCount >= 4) { finish(); Result.failure(workDataOf(KEY_RESULT to c.api.errorMessage(e))) } else Result.retry()
        } catch (e: Exception) {
            if (runAttemptCount >= 4) { finish(); Result.failure(workDataOf(KEY_RESULT to c.api.errorMessage(e))) } else Result.retry()
        }
    }

    private suspend fun finish() {
        ServerRestore.setPending(applicationContext, false)
        val c = applicationContext.container
        c.vehicles.ensureDefault()                      // ak server nemal žiadne vozidlo
        sk.cartracker.widget.CarWidget.updateAll(applicationContext)
        val s = c.settings.current()
        SyncWorker.enqueueNow(applicationContext, s.syncWifiOnly)
        // potom skontrolovať, či je na serveri všetko z tohto telefónu
        BackupCheckWorker.runOnce(applicationContext, s.syncWifiOnly)
        BackupCheckWorker.scheduleWeekly(applicationContext, s.syncWifiOnly)
    }

    companion object {
        const val NAME = "restore"
        const val KEY_STEP = "step"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_RESULT = "result"

        fun start(ctx: Context) {
            ServerRestore.setPending(ctx, true)
            val req = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** Odoslanie zmenených nastavení na server (s krátkym oneskorením, aby sa viac zmien poslalo naraz). */
class SettingsPushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        ServerRestore.pushSettings(applicationContext); Result.success()
    } catch (e: HttpException) {
        if (e.code() == 401 || e.code() == 404) Result.failure() else Result.retry()
    } catch (e: Exception) { Result.retry() }

    companion object {
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SettingsPushWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("settings_push", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Kontrola zálohy: čo na serveri ešte nikdy nebolo, sa doplní (raz po aktualizácii/prihlásení a potom týždenne). */
class BackupCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.session.isLoggedIn) return Result.success()
        if (sk.cartracker.data.ServerRestore.isPending(applicationContext)) return Result.retry() // najprv nech dobehne obnova
        return try {
            val r = sk.cartracker.data.ServerBackup.verifyAndUpload(applicationContext)
            Result.success(workDataOf(RestoreWorker.KEY_RESULT to r.toString()))
        } catch (e: HttpException) {
            if (e.code() == 401) Result.failure() else Result.retry()
        } catch (e: Exception) { Result.retry() }
    }

    companion object {
        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

        /** Jednorazová kontrola (ihneď, ako bude sieť). */
        fun runOnce(ctx: Context, wifiOnly: Boolean, replace: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<BackupCheckWorker>()
                .setConstraints(constraints(wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("backup_check", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }

        fun scheduleWeekly(ctx: Context, wifiOnly: Boolean) {
            val req = androidx.work.PeriodicWorkRequestBuilder<BackupCheckWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints(wifiOnly)).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("backup_check_weekly", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
