package sk.cartracker.data

/**
 * Kedy upozorniť na prekročenie rýchlosti (čistá logika, testovaná):
 *  - prvé prekročenie → upozornenie hneď,
 *  - kým je rýchlosť stále nad limitom → opakovane každých [intervalMs] (0 = len raz),
 *  - epizóda končí až po [okToReset] po sebe idúcich bodoch pod limitom (jeden bod pod limitom ju nepreruší),
 *  - ďalšie prekročenie po skončení epizódy → znova hneď.
 * Body bez známeho limitu alebo s nepresnou polohou sa nepočítajú ([onFix] s speeding = null).
 */
class SpeedAlerter(var intervalMs: Long = 3_000, private val okToReset: Int = 2) {
    var active = false; private set
    var speedingNow = false; private set
    private var okCount = 0
    private var lastAlertAt = 0L

    /** Nový bod GPS. @return true = upozorniť teraz; [newEpisode] = začalo nové prekročenie. */
    fun onFix(speeding: Boolean?, now: Long): Result {
        if (speeding == null) return Result(false, false)
        speedingNow = speeding
        if (speeding) {
            okCount = 0
            if (!active) { active = true; lastAlertAt = now; return Result(true, true) }
            return Result(due(now).also { if (it) lastAlertAt = now }, false)
        }
        if (active && ++okCount >= okToReset) { active = false; okCount = 0 }
        return Result(false, false)
    }

    /** Pravidelná kontrola medzi bodmi GPS (pri riedkych bodoch na diaľnici). */
    fun onTick(now: Long): Boolean = (active && speedingNow && due(now)).also { if (it) lastAlertAt = now }

    private fun due(now: Long) = intervalMs > 0 && now - lastAlertAt >= intervalMs

    fun reset() { active = false; speedingNow = false; okCount = 0; lastAlertAt = 0 }

    data class Result(val alert: Boolean, val newEpisode: Boolean)
}
