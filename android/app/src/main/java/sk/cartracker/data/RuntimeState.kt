package sk.cartracker.data

import android.content.Context

/** Malý synchrónny stav, ktorý potrebujú broadcast receivery a služba (aj keď UI nebeží). */
object RuntimeState {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    fun isCarBtConnected(ctx: Context) = prefs(ctx).getBoolean("car_bt_connected", false)
    fun setCarBtConnected(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("car_bt_connected", v).apply()

    fun lastActivity(ctx: Context): String? = prefs(ctx).getString("last_activity", null)
    fun lastActivityAt(ctx: Context): Long = prefs(ctx).getLong("last_activity_at", 0L)
    fun setLastActivity(ctx: Context, text: String) =
        prefs(ctx).edit().putString("last_activity", text).putLong("last_activity_at", System.currentTimeMillis()).apply()

    fun isDetectionRegistered(ctx: Context) = prefs(ctx).getBoolean("detection_registered", false)
    fun setDetectionRegistered(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("detection_registered", v).apply()

    fun lastSyncAt(ctx: Context): Long = prefs(ctx).getLong("last_sync_at", 0L)
    fun setLastSyncAt(ctx: Context, t: Long) = prefs(ctx).edit().putLong("last_sync_at", t).apply()
}
