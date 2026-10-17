package sk.cartracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.cartracker.container

/**
 * Kontrola zálohy na serveri: porovná, čo je v telefóne a čo na serveri, a všetko, čo na server
 * ešte nikdy nedorazilo (jazdy aj s trasami, vozidlá, miesta, tankovania, pripomienky, nastavenia),
 * označí na odoslanie a hneď odošle. Spúšťa sa automaticky raz po aktualizácii / prihlásení a potom raz týždenne.
 */
object ServerBackup {
    data class Report(val trips: Int, val vehicles: Int, val items: Int, val settings: Boolean, val uploaded: Int) {
        val missing get() = trips + vehicles + items + (if (settings) 1 else 0)
        override fun toString() = if (missing == 0) "Všetko je na serveri ✓"
            else "Doplnené na server: jazdy $trips, vozidlá $vehicles, miesta/tankovania/pripomienky $items" + if (settings) ", nastavenia" else ""
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    fun verifiedKey(userId: Int) = "server_backup_verified_$userId"
    fun isVerified(ctx: Context, userId: Int) = prefs(ctx).getBoolean(verifiedKey(userId), false)
    fun lastCheck(ctx: Context): Pair<Long, String>? = prefs(ctx).getLong("server_backup_at", 0L).takeIf { it > 0 }
        ?.let { it to (prefs(ctx).getString("server_backup_result", "") ?: "") }

    suspend fun verifyAndUpload(ctx: Context): Report = withContext(Dispatchers.IO) {
        val c = ctx.container
        val user = c.session.user() ?: error("Nie ste prihlásený")
        val api = c.api.get()
        val tripDao = c.db.tripDao()
        val ex = c.db.extrasDao()

        // jazdy
        val remoteTrips = HashSet<String>()
        var offset = 0
        while (true) {
            val page = api.trips(1000, offset).trips
            page.forEach { remoteTrips += it.uuid }
            if (page.size < 1000) break
            offset += page.size
        }
        var nTrips = 0
        for (t in tripDao.allTrips(user.id).filter { it.finished && it.uuid !in remoteTrips }) {
            tripDao.markPointsUnsynced(t.id)
            tripDao.markTripUnsynced(t.id)
            nTrips++
        }

        // vozidlá (vlastné)
        val remoteVeh = api.vehicles().vehicles.map { it.uuid }.toSet()
        var nVeh = 0
        for (v in tripDao.allVehicles(user.id).filter { it.ownerName == null && it.uuid !in remoteVeh }) {
            tripDao.updateVehicle(v.copy(synced = false)); nVeh++
        }

        // miesta, tankovania, pripomienky
        val remoteItems = api.items().items.map { it.uuid }.toSet()
        var nItems = 0
        ex.allPlaces(user.id).filter { !it.deleted && it.uuid !in remoteItems }.forEach { ex.updatePlace(it.copy(synced = false)); nItems++ }
        ex.allRefuels(user.id).filter { !it.deleted && it.uuid !in remoteItems }.forEach { ex.updateRefuel(it.copy(synced = false)); nItems++ }
        ex.allReminders(user.id).filter { !it.deleted && it.uuid !in remoteItems }.forEach { ex.updateReminder(it.copy(synced = false)); nItems++ }
        ex.allExpenses(user.id).filter { !it.deleted && it.uuid !in remoteItems }.forEach { ex.updateExpense(it.copy(synced = false)); nItems++ }

        // nastavenia – ak na serveri ešte nie sú, odošlú sa súčasné
        val noSettings = runCatching { api.settings().updatedAt <= 0 }.getOrDefault(false)
        if (noSettings) {
            prefs(ctx).edit().putBoolean("settings_ready_${user.id}", true).remove("settings_pushed_${user.id}").apply()
        }

        // odoslanie
        val uploaded = c.trips.syncAll()
        c.items.syncAll()
        ServerRestore.pushSettings(ctx)

        val r = Report(nTrips, nVeh, nItems, noSettings, uploaded)
        prefs(ctx).edit().putBoolean(verifiedKey(user.id), true)
            .putLong("server_backup_at", System.currentTimeMillis()).putString("server_backup_result", r.toString()).apply()
        r
    }
}
