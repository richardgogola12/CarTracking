package sk.cartracker.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Obnova widgetov tesne po polnoci – aby „dnes“ (kroky, km) neukazovalo ešte včerajšie hodnoty. */
object MidnightRefresh {
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 1); set(Calendar.SECOND, 0)
        }.timeInMillis
        val pi = PendingIntent.getBroadcast(app, 71, Intent(app, MidnightRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // nepresný alarm stačí (najviac pár minút po polnoci) a nepotrebuje zvláštne povolenie
        runCatching { app.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, next, 10 * 60_000L, pi) }
    }
}

class MidnightRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        CarWidget.updateAll(ctx)
        MidnightRefresh.schedule(ctx)
    }
}
