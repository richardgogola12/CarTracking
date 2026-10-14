package sk.cartracker.data

import android.content.Context
import java.io.File

/**
 * Diagnostika detekcie – krátky záznam udalostí (čo hlásil systém, aké rýchlosti namerala GPS,
 * prečo sa jazda spustila / zahodila / ukončila). Ukladá sa do súboru v telefóne (max. ~400 kB).
 */
object DiagLog {
    const val SYSTEM = "Systém"
    const val BT = "Bluetooth"
    const val CHECK = "Overovanie"
    const val GPS = "GPS"
    const val DRIVE = "Jazda"
    const val WALK = "Chôdza"
    const val ERROR = "Problém"
    const val APP = "Aplikácia"

    data class Entry(val ts: Long, val category: String, val message: String)

    @Volatile var enabled: Boolean = true
    private const val MAX_BYTES = 400_000L
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "diagnostika.log")

    fun log(ctx: Context, category: String, message: String) {
        if (!enabled) return
        runCatching {
            synchronized(lock) {
                val f = file(ctx)
                f.appendText("${System.currentTimeMillis()}\t$category\t${message.replace('\n', ' ').replace('\t', ' ')}\n")
                if (f.length() > MAX_BYTES) {
                    val lines = f.readLines()
                    f.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun read(ctx: Context): List<Entry> = runCatching {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) emptyList() else f.readLines().mapNotNull { l ->
                val p = l.split('\t', limit = 3)
                if (p.size < 3) null else p[0].toLongOrNull()?.let { Entry(it, p[1], p[2]) }
            }
        }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) = synchronized(lock) { file(ctx).delete() }

    fun asText(entries: List<Entry>): String = entries.joinToString("\n") {
        sk.cartracker.ui.formatDateTime(it.ts) + ":" + String.format("%02d", (it.ts / 1000) % 60) + "  [" + it.category + "]  " + it.message
    }
}
