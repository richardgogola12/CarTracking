package sk.cartracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import sk.cartracker.data.AppSettings

enum class Perm { LOCATION, BACKGROUND_LOCATION, ACTIVITY, NOTIFICATIONS, BATTERY, BLUETOOTH }

data class PermItem(val perm: Perm, val title: String, val why: String, val granted: Boolean, val required: Boolean)

private fun Context.has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

fun permissionItems(ctx: Context, s: AppSettings): List<PermItem> {
    val list = mutableListOf(
        PermItem(Perm.LOCATION, "Presná poloha", "GPS na zaznamenanie trasy a rýchlosti.",
            ctx.has(Manifest.permission.ACCESS_FINE_LOCATION), true),
        PermItem(Perm.BACKGROUND_LOCATION, "Poloha vždy (aj na pozadí)",
            "Aby sa jazda spustila automaticky aj pri zatvorenej aplikácii. V systéme zvoľte „Povoliť vždy“.",
            Build.VERSION.SDK_INT < 29 || ctx.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION), true),
        PermItem(Perm.ACTIVITY, "Fyzická aktivita", "Rozpozná, že sedíte v aute – odlíši ho od chôdze a bicykla.",
            Build.VERSION.SDK_INT < 29 || ctx.has(Manifest.permission.ACTIVITY_RECOGNITION), true),
        PermItem(Perm.NOTIFICATIONS, "Notifikácie", "Oznámenie o zapnutí a vypnutí režimu jazdy.",
            Build.VERSION.SDK_INT < 33 || ctx.has(Manifest.permission.POST_NOTIFICATIONS), true),
        PermItem(Perm.BATTERY, "Bez obmedzenia batérie",
            "Android inak nedovolí spustiť sledovanie na pozadí a môže službu uspať.",
            ctx.isIgnoringBatteryOptimizations(), true),
    )
    if (s.bluetoothTrigger || s.bluetoothRequired) {
        list += PermItem(Perm.BLUETOOTH, "Zariadenia v okolí (Bluetooth)", "Spoznanie pripojenia k Bluetooth vášho auta.",
            Build.VERSION.SDK_INT < 31 || ctx.has(Manifest.permission.BLUETOOTH_CONNECT), true)
    }
    return list
}

fun permissionString(p: Perm): String? = when (p) {
    Perm.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
    Perm.BACKGROUND_LOCATION -> if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null
    Perm.ACTIVITY -> if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null
    Perm.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    Perm.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null
    Perm.BATTERY -> null
}

fun batteryOptimizationIntent(ctx: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

fun appSettingsIntent(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
