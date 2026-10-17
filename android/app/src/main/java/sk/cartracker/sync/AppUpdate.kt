package sk.cartracker.sync

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import sk.cartracker.BuildConfig
import sk.cartracker.R
import sk.cartracker.container
import sk.cartracker.service.Notifications
import sk.cartracker.ui.MainActivity
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Aktualizácia aplikácie priamo z GitHub Releases (github.com/richardgogola12/CarTracking).
 * Každé zostavenie má tag build-N a súbor Trasnik-N.apk; versionCode aplikácie = 100 + N.
 * APK je podpísané rovnakým kľúčom – Android ho nainštaluje ako aktualizáciu (údaje ostanú).
 */
object AppUpdate {
    private const val LATEST = "https://api.github.com/repos/richardgogola12/CarTracking/releases/latest"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Release(val build: Int, val name: String, val apkUrl: String, val sizeBytes: Long, val publishedAt: String) {
        val versionName get() = "2.0.$build"
    }

    /** Číslo zostavenia z tagu „build-67“. */
    fun buildOf(tag: String?): Int? = tag?.removePrefix("build-")?.toIntOrNull()

    fun currentBuild(): Int = BuildConfig.VERSION_CODE - 100

    fun parse(json: JSONObject): Release? {
        val build = buildOf(json.optString("tag_name")) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return Release(build, json.optString("name"),
                a.optString("browser_download_url"), a.optLong("size"), json.optString("published_at"))
        }
        return null
    }

    /** Najnovšie zostavenie, ak je novšie ako nainštalované; inak null. Pri chybe siete výnimka. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Trasnik/${BuildConfig.VERSION_NAME}").build()
        val json = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub odpovedal ${r.code}")
            JSONObject(r.body?.string() ?: "{}")
        }
        parse(json)?.takeIf { it.build > currentBuild() }
    }

    /** Stiahne APK do vyrovnávacej pamäte. [progress] 0–100. */
    suspend fun download(ctx: Context, r: Release, progress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "Trasnik-${r.build}.apk")
        client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "Trasnik").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("Sťahovanie zlyhalo (${resp.code})")
            val body = resp.body ?: throw java.io.IOException("Prázdna odpoveď")
            val total = body.contentLength().takeIf { it > 0 } ?: r.sizeBytes
            body.byteStream().use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        }
        f
    }

    /** Smie aplikácia inštalovať? (Android 8+: „Inštalácia neznámych aplikácií“ pre Trasník) */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Otvorí systémovú inštaláciu stiahnutého APK. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Notifikácia o novej verzii (raz pre každé zostavenie). */
    fun notify(ctx: Context, r: Release) {
        val prefs = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        if (prefs.getInt("update_notified", 0) >= r.build) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 60, Intent(ctx, MainActivity::class.java).setAction("sk.cartracker.shortcut.UPDATE")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, Notifications.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("⬆️ Nová verzia Trasníka ${r.versionName}")
            .setContentText("Máte ${BuildConfig.VERSION_NAME}. Ťuknite – stiahne sa a nainštaluje (údaje ostanú).")
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_car, "Aktualizovať", open)
            .build()
        NotificationManagerCompat.from(ctx).notify(61, n)
        prefs.edit().putInt("update_notified", r.build).apply()
    }
}

/** Raz denne skontroluje novú verziu (iba ak je zapnutá kontrola aktualizácií). */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!applicationContext.container.settings.current().updateCheck) return Result.success()
        runCatching { AppUpdate.check() }.getOrNull()?.let { AppUpdate.notify(applicationContext, it) }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("app_update", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
