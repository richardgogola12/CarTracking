package sk.cartracker.detection

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.tasks.await
import sk.cartracker.data.RuntimeState

/**
 * Registruje u Google Play služieb "Activity Transition" udalosti.
 * Systém ich doručí do [ActivityTransitionReceiver] aj vtedy, keď appka vôbec nebeží –
 * vďaka tomu sa sledovanie spúšťa automaticky a takmer bez spotreby batérie.
 */
object ActivityDetectionManager {
    const val ACTION_TRANSITION = "sk.cartracker.ACTIVITY_TRANSITION"
    private const val TAG = "ActivityDetection"

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ActivityTransitionReceiver::class.java).setAction(ACTION_TRANSITION)
        // FLAG_MUTABLE je povinný – Play služby do intentu dopĺňajú výsledok
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 4711, intent, flags)
    }

    private fun transitions(): List<ActivityTransition> {
        fun t(type: Int, transition: Int) =
            ActivityTransition.Builder().setActivityType(type).setActivityTransition(transition).build()
        return listOf(
            t(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            t(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
            t(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            t(DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
            t(DetectedActivity.ON_BICYCLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun register(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!hasPermission(app)) {
            RuntimeState.setDetectionRegistered(app, false)
            return false
        }
        return try {
            ActivityRecognition.getClient(app)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions()), pendingIntent(app))
                .await()
            RuntimeState.setDetectionRegistered(app, true)
            Log.i(TAG, "Detekcia jazdy zaregistrovaná")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Registrácia zlyhala", e)
            RuntimeState.setDetectionRegistered(app, false)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        val app = ctx.applicationContext
        if (hasPermission(app)) {
            ActivityRecognition.getClient(app).removeActivityTransitionUpdates(pendingIntent(app))
        }
        RuntimeState.setDetectionRegistered(app, false)
    }

    fun activityName(type: Int) = when (type) {
        DetectedActivity.IN_VEHICLE -> "v aute"
        DetectedActivity.WALKING -> "chôdza"
        DetectedActivity.RUNNING -> "beh"
        DetectedActivity.ON_BICYCLE -> "bicykel"
        DetectedActivity.STILL -> "stojím"
        else -> "neznáme"
    }
}
