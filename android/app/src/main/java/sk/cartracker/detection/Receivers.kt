package sk.cartracker.detection

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.RuntimeState
import sk.cartracker.service.TrackingService
import sk.cartracker.sync.SyncWorker

/** Spracuje asynchrónnu prácu v receiveri bez toho, aby ho systém zabil. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try { block() } catch (e: Exception) { Log.e("Receiver", "Chyba", e) } finally { pending.finish() }
    }
}

/** Nastúpil som do auta / vystúpil som / idem pešo alebo na bicykli. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        async {
            val c = ctx.container
            val s = c.settings.current()
            if (!s.autoDetection || !c.session.isLoggedIn) return@async

            val nowNanos = SystemClock.elapsedRealtimeNanos()
            for (ev in result.transitionEvents.sortedBy { it.elapsedRealTimeNanos }) {
                // ignoruj staré udalosti (napr. doručené po reštarte)
                if (nowNanos - ev.elapsedRealTimeNanos > 5 * 60 * 1_000_000_000L) continue
                val enter = ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                RuntimeState.setLastActivity(ctx, (if (enter) "začiatok: " else "koniec: ") + ActivityDetectionManager.activityName(ev.activityType))

                when {
                    ev.activityType == DetectedActivity.IN_VEHICLE && enter -> {
                        if (s.bluetoothRequired && !RuntimeState.isCarBtConnected(ctx)) continue
                        TrackingService.startCandidate(ctx, TrackingService.SOURCE_AUTO)
                    }
                    ev.activityType == DetectedActivity.IN_VEHICLE && !enter ->
                        TrackingService.signal(ctx, TrackingService.ACTION_EXIT_SIGNAL)
                    enter && ev.activityType in setOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE) ->
                        TrackingService.signal(ctx, TrackingService.ACTION_EXIT_SIGNAL)
                }
            }
        }
    }
}

/** Po reštarte telefónu / aktualizácii appky znovu zapne automatickú detekciu. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        async {
            val c = ctx.container
            val s = c.settings.current()
            if (!c.session.isLoggedIn) return@async
            val isBoot = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
            if (s.autoDetection && (s.startOnBoot || !isBoot)) ActivityDetectionManager.register(ctx)
            RuntimeState.setCarBtConnected(ctx, false)
            SyncWorker.schedulePeriodic(ctx, s.syncWifiOnly)
        }
    }
}

/** Voliteľné: pripojenie k Bluetooth autu = silný signál, že sedím v aute. */
class BluetoothCarReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        async {
            val c = ctx.container
            val s = c.settings.current()
            if (s.carBtAddress.isEmpty() || !s.carBtAddress.equals(address, ignoreCase = true)) return@async
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    RuntimeState.setCarBtConnected(ctx, true)
                    RuntimeState.setLastActivity(ctx, "pripojené Bluetooth auta")
                    if (s.autoDetection && s.bluetoothTrigger && c.session.isLoggedIn) {
                        TrackingService.startCandidate(ctx, TrackingService.SOURCE_BLUETOOTH)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    RuntimeState.setCarBtConnected(ctx, false)
                    RuntimeState.setLastActivity(ctx, "odpojené Bluetooth auta")
                    TrackingService.signal(ctx, TrackingService.ACTION_EXIT_SIGNAL)
                }
            }
        }
    }
}
