package sk.cartracker.ui

import android.app.Activity
import android.content.Context
import sk.cartracker.container
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import sk.cartracker.sync.MonthlySummary
import java.util.Calendar

/** Kniha jázd za predchádzajúci mesiac jedným ťuknutím (z mesačnej notifikácie alebo zo Štatistík). */
object MonthlyLogbook {
    data class Part(val vehicle: VehicleEntity?, val trips: List<TripEntity>, val odometerStart: Double?)
    data class Month(val from: Long, val to: Long, val title: String)

    fun previousMonth(now: Calendar = Calendar.getInstance()): Month {
        val prev = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val from = MonthlySummary.monthStart(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH))
        val to = MonthlySummary.monthStart(now.get(Calendar.YEAR), now.get(Calendar.MONTH))
        return Month(from, to, "${MonthlySummary.MONTHS[prev.get(Calendar.MONTH)].replaceFirstChar { it.uppercase() }} ${prev.get(Calendar.YEAR)}")
    }

    /** Jazdy autom za mesiac rozdelené podľa vozidla (kniha jázd sa vedie pre každé auto zvlášť). */
    suspend fun parts(ctx: Context, m: Month): List<Part> {
        val c = ctx.container
        val vehicles = c.vehicles.all().associateBy { it.uuid }
        return c.trips.tripsBetween(m.from, m.to).filter { !it.isWalk }.sortedBy { it.startedAt }
            .groupBy { it.vehicleUuid }
            .map { (uuid, list) -> val v = uuid?.let { vehicles[it] }; Part(v, list, c.trips.odometerAt(list.first().startedAt, v)) }
            .sortedByDescending { p -> p.trips.sumOf { it.distanceM } }
    }

    /** Tlač / PDF. Viac vozidiel = viac strán v jednom dokumente. @return false, ak v mesiaci nie sú jazdy */
    suspend fun print(activity: Activity, m: Month = previousMonth()): Boolean {
        val c = activity.container
        val s = c.settings.current()
        val driver = c.session.user()?.name ?: ""
        val docs = parts(activity, m).map { Reports.logbookHtml(it.trips, s, m.title, driver, it.odometerStart, it.vehicle) }
        if (docs.isEmpty()) return false
        val bodies = docs.drop(1).joinToString("") { d ->
            "<div style='page-break-before:always'></div>" + d.substringAfter("<body>").substringBeforeLast("</body>")
        }
        val html = docs.first().replace("</body>", "$bodies</body>")
        Reports.printHtml(activity, "Kniha jázd ${m.title}", html = html, landscape = true)
        return true
    }

    /** CSV pre Excel (pri viacerých vozidlách oddelené prázdnym riadkom). */
    suspend fun shareCsv(ctx: Context, m: Month = previousMonth()): Boolean {
        val s = ctx.container.settings.current()
        val parts = parts(ctx, m)
        if (parts.isEmpty()) return false
        val csv = parts.joinToString("\r\n") { p ->
            (if (parts.size > 1) "\"${p.vehicle?.label ?: "Bez vozidla"}\"\r\n" else "") + Reports.logbookCsv(p.trips, s, p.odometerStart, p.vehicle)
        }
        Reports.shareFile(ctx, "kniha-jazd-${m.title.replace(' ', '-')}.csv", csv, "text/csv")
        return true
    }
}
