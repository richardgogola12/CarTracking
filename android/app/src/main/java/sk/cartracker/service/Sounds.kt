package sk.cartracker.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import sk.cartracker.R
import sk.cartracker.data.AppSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Udalosti, ku ktorým sa dá nastaviť zvuk. */
enum class SoundEvent(val label: String, val default: String) {
    DRIVE_START("Jazda autom – začiatok", "nahor"),
    DRIVE_END("Jazda autom – koniec", "nadol"),
    WALK_START("Chôdza – začiatok", SoundBank.NONE),
    WALK_END("Chôdza – koniec", SoundBank.NONE),
    SPEEDING("Prekročenie rýchlosti", "varovanie"),
    PARKING("Parkovací lístok končí", "dingdong");

    fun of(s: AppSettings): String = when (this) {
        DRIVE_START -> s.soundDriveStart; DRIVE_END -> s.soundDriveEnd
        WALK_START -> s.soundWalkStart; WALK_END -> s.soundWalkEnd
        SPEEDING -> s.soundSpeeding; PARKING -> s.soundParking
    }
}

/**
 * Zvuky syntetizované v aplikácii podľa res/raw/sounds.json (rovnaký algoritmus má aj overovací skript v Pythone).
 * Okrem nich: „Bez zvuku“, „Systémový zvuk upozornenia“ a vlastný zvuk z telefónu (uri:…).
 */
object SoundBank {
    const val NONE = "none"
    const val SYSTEM = "system"
    const val URI_PREFIX = "uri:"

    class Note(val f: Double, val f2: Double?, val t: Int, val d: Int, val w: String, val g: Double, val a: Double, val decay: Double, val vibRate: Double, val vibDepth: Double)
    class Sound(val id: String, val name: String, val notes: List<Note>)

    private var rate = 44100
    private var cache: List<Sound>? = null
    private val pcm = ConcurrentHashMap<String, ShortArray>()

    fun all(ctx: Context): List<Sound> = cache ?: synchronized(this) {
        cache ?: run {
            val json = JSONObject(ctx.resources.openRawResource(R.raw.sounds).bufferedReader().use { it.readText() })
            rate = json.optInt("rate", 44100)
            val arr = json.getJSONArray("sounds")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); val ns = o.getJSONArray("notes")
                Sound(o.getString("id"), o.getString("name"), (0 until ns.length()).map { k ->
                    val n = ns.getJSONObject(k); val vib = n.optJSONArray("vib")
                    Note(n.getDouble("f"), if (n.has("f2")) n.getDouble("f2") else null, n.getInt("t"), n.getInt("d"), n.getString("w"),
                        n.optDouble("g", 1.0), n.optDouble("a", 5.0) / 1000.0, n.optDouble("decay", 0.0),
                        vib?.optDouble(0) ?: 0.0, vib?.optDouble(1) ?: 0.0)
                })
            }.also { cache = it }
        }
    }

    /** Názov voľby pre zobrazenie. */
    fun label(ctx: Context, id: String): String = when {
        id == NONE -> "Bez zvuku"
        id == SYSTEM -> "Systémový zvuk upozornenia"
        id.startsWith(URI_PREFIX) -> runCatching {
            RingtoneManager.getRingtone(ctx, Uri.parse(id.removePrefix(URI_PREFIX)))?.getTitle(ctx)
        }.getOrNull()?.let { "Vlastný: $it" } ?: "Vlastný zvuk"
        else -> all(ctx).firstOrNull { it.id == id }?.name ?: "Bez zvuku"
    }

    fun render(ctx: Context, id: String): ShortArray? {
        pcm[id]?.let { return it }
        val s = all(ctx).firstOrNull { it.id == id } ?: return null
        val sr = rate.toDouble()
        val total = s.notes.maxOf { ((it.t + it.d) * sr / 1000).toInt() } + (0.05 * sr).toInt()
        val buf = DoubleArray(total)
        for (n in s.notes) {
            val count = (n.d * sr / 1000).toInt(); val st = (n.t * sr / 1000).toInt()
            var ph = 0.0
            for (i in 0 until count) {
                val t = i / sr; val frac = i.toDouble() / count
                var f = if (n.f2 != null) n.f * (n.f2 / n.f).pow(frac) else n.f
                if (n.vibDepth != 0.0) f *= 2.0.pow(n.vibDepth * sin(2 * PI * n.vibRate * t) / 12)
                ph += 2 * PI * f / sr
                val v = when (n.w) {
                    "sine" -> sin(ph)
                    "square" -> (if (sin(ph) >= 0) 1.0 else -1.0) * 0.7
                    "triangle" -> 2 / PI * asin(sin(ph))
                    "saw" -> (2 * ((ph / (2 * PI)) % 1.0) - 1) * 0.7
                    "bell" -> sin(ph) + 0.5 * sin(2.76 * ph) * exp(-t / 0.12) + 0.25 * sin(5.4 * ph) * exp(-t / 0.06)
                    "glass" -> sin(ph) + 0.5 * sin(2 * ph) + 0.25 * sin(3 * ph) * exp(-t / 0.3)
                    "gong" -> sin(ph) + 0.7 * sin(1.48 * ph) * exp(-t / 1.5) + 0.5 * sin(2.13 * ph) * exp(-t / 0.9) + 0.3 * sin(3.27 * ph) * exp(-t / 0.5)
                    "pluck" -> (1..6).sumOf { k -> sin(k * ph) / k * exp(-t * k * 3) }
                    else -> 0.0
                }
                var env = if (n.a > 0) min(1.0, t / n.a) else 1.0
                if (n.decay > 0) env *= exp(-t / n.decay)
                val rel = (count - i) / sr
                if (rel < 0.015) env *= rel / 0.015
                buf[st + i] += n.g * v * env
            }
        }
        // vyrovnaná hlasitosť: RMS 0,30 počas znenia, špička najviac 0,95
        val peak = buf.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val active = buf.filter { abs(it) > peak * 0.02 }.ifEmpty { buf.toList() }
        val rms = sqrt(active.sumOf { it * it } / active.size).takeIf { it > 0 } ?: 1.0
        val k = min(0.30 / rms, 0.95 / peak)
        return ShortArray(total) { (max(-1.0, min(1.0, buf[it] * k)) * 32767).toInt().toShort() }.also { pcm[id] = it }
    }
}

/**
 * Prehrávanie zvukov udalostí. Pri voľbe „aj v tichom režime“ ide zvuk cez kanál budíka (USAGE_ALARM) – znie aj pri
 * stlmenom zvonení / vibráciách; ak je hlasitosť budíka na nule, na chvíľu sa zvýši a potom vráti.
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private val main = Handler(Looper.getMainLooper())
    private var restoreVolume: Runnable? = null

    fun event(ctx: Context, e: SoundEvent, s: AppSettings) {
        if (!s.sound) return
        play(ctx, e.of(s), s)
    }

    fun play(ctx: Context, id: String, s: AppSettings) {
        if (id == SoundBank.NONE) return
        val app = ctx.applicationContext
        val vol = (s.soundVolume.coerceIn(0, 100) / 100f)
        if (vol <= 0f) return
        val usage = if (s.soundIgnoreSilent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
        val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            val isUri = id == SoundBank.SYSTEM || id.startsWith(SoundBank.URI_PREFIX)
            // hlasitosť budíka upraviť ešte pred spustením (dĺžka: syntéza presne, súbor najviac 8 s)
            val estMs = if (isUri) 8000L else (SoundBank.render(app, id)?.size ?: 0) * 1000L / 44100
            if (s.soundIgnoreSilent && estMs > 0) ensureAlarmAudible(app, estMs)
            if (isUri) playUri(app, id, attrs, vol) else playSynth(app, id, attrs, vol)
        } catch (e: Exception) {
            Log.e(TAG, "Zvuk sa nepodarilo prehrať: $id", e)
        }
    }

    private fun playSynth(ctx: Context, id: String, attrs: AudioAttributes, vol: Float): Long {
        val data = SoundBank.render(ctx, id) ?: return 0
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, data.size)
        track.setVolume(vol)
        track.play()
        val ms = data.size * 1000L / 44100
        main.postDelayed({ runCatching { track.stop(); track.release() } }, ms + 300)
        return ms
    }

    private fun playUri(ctx: Context, id: String, attrs: AudioAttributes, vol: Float): Long {
        val uri = if (id == SoundBank.SYSTEM) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else Uri.parse(id.removePrefix(SoundBank.URI_PREFIX))
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri)
            mp.setVolume(vol, vol)
            mp.prepare()
            val ms = mp.duration.toLong().coerceIn(200, 8000)
            mp.setOnCompletionListener { it.release() }
            mp.start()
            // dlhé skladby sa po 8 s zastavia
            main.postDelayed({ runCatching { if (mp.isPlaying) mp.stop(); mp.release() } }, ms + 200)
            ms
        } catch (e: Exception) {
            mp.release()
            // vlastný súbor už neexistuje (napr. iný telefón po obnove) → systémový zvuk
            if (id != SoundBank.SYSTEM) playUri(ctx, SoundBank.SYSTEM, attrs, vol) else 0
        }
    }

    /** Ak je hlasitosť budíka 0, nastaví ju dočasne na polovicu (inak by zvuk nebolo počuť). */
    private fun ensureAlarmAudible(ctx: Context, durMs: Long) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
        if (cur > 0 && restoreVolume == null) return
        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val original = if (restoreVolume == null) cur else 0
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, max(1, maxV / 2), 0) }
        restoreVolume?.let { main.removeCallbacks(it) }
        val r = Runnable { runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, original, 0) }; restoreVolume = null }
        restoreVolume = r
        main.postDelayed(r, durMs + 600)
    }
}
