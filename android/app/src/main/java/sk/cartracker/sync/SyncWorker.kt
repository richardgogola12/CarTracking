package sk.cartracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import retrofit2.HttpException
import sk.cartracker.container
import sk.cartracker.data.RuntimeState
import java.io.IOException
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.session.isLoggedIn) return Result.success()
        return try {
            c.trips.syncAll()
            c.items.syncAll()
            runCatching { c.steps.sync() }
            runCatching { c.vehicles.pullShared() }
            runCatching { sk.cartracker.data.ServerRestore.pushSettings(applicationContext) }
            RuntimeState.setLastSyncAt(applicationContext, System.currentTimeMillis())
            Result.success()
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 422) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        fun enqueueNow(ctx: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(wifiOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync_now", ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(ctx: Context, wifiOnly: Boolean) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(wifiOnly))
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancelPeriodic(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork("sync_periodic")
    }
}
