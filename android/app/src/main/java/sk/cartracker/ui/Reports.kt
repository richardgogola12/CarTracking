package sk.cartracker.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import sk.cartracker.data.AppSettings
import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.cost
import sk.cartracker.data.liters
import sk.cartracker.data.local.TripEntity
import sk.cartracker.data.local.VehicleEntity
import java.io.File
import sk.cartracker.ui.screens.DaySegment
import java.util.Locale

object Reports {
    private val sk = Locale("sk", "SK")
    private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun km(m: Double) = String.format(sk, "%.1f", m / 1000.0)

    /** Kniha jázd v HTML pripravená na tlač (A4 na šírku). */
    fun logbookHtml(trips: List<TripEntity>, s: AppSettings, period: String, driver: String, odometerStart: Double?, vehicle: VehicleEntity?): String {
        var odo = odometerStart
        val rows = StringBuilder()
        trips.forEachIndexed { i, t ->
            val odoFrom = odo
            val odoTo = odo?.plus(t.distanceM / 1000.0)
            odo = odoTo
            rows.append("<tr>")
                .append("<td class='c'>${i + 1}</td>")
                .append("<td>${formatDate(t.startedAt)}</td>")
                .append("<td class='c'>${formatTime(t.startedAt)}–${t.endedAt?.let { formatTime(it) } ?: ""}</td>")
                .append("<td>${esc(t.startAddress ?: "—")}</td>")
                .append("<td>${esc(t.endAddress ?: "—")}</td>")
                .append("<td>${esc(t.note.ifBlank { if (t.category == CATEGORY_BUSINESS) "služobná cesta" else "" })}</td>")
                .append("<td class='c'>${if (t.category == CATEGORY_BUSINESS) "S" else "P"}</td>")
                .append("<td class='r'>${odoFrom?.let { it.toLong().toString() } ?: ""}</td>")
                .append("<td class='r'>${odoTo?.let { it.toLong().toString() } ?: ""}</td>")
                .append("<td class='r'><b>${km(t.distanceM)}</b></td>")
                .append("<td class='r'>${String.format(sk, "%.2f", t.liters(vehicle, s))}</td>")
                .append("<td class='r'>${String.format(sk, "%.2f", t.cost(vehicle, s))}</td>")
                .append("</tr>\n")
        }
        val total = trips.sumOf { it.distanceM }
        val business = trips.filter { it.category == CATEGORY_BUSINESS }.sumOf { it.distanceM }
        return """
<!doctype html><html lang="sk"><head><meta charset="utf-8"><title>Kniha jázd – ${esc(period)}</title>
<style>
 @page { size: A4 landscape; margin: 12mm; }
 body { font-family: 'Roboto', Arial, sans-serif; font-size: 9.5pt; color: #111; }
 h1 { font-size: 17pt; margin: 0 0 2mm; color: #0d47a1; }
 .meta { display: flex; gap: 10mm; margin-bottom: 4mm; font-size: 9.5pt; }
 .meta b { color: #333; }
 table { width: 100%; border-collapse: collapse; }
 th { background: #0d47a1; color: #fff; font-weight: 600; padding: 4px; font-size: 8.5pt; text-align: left; }
 td { border-bottom: 1px solid #ddd; padding: 3.5px 4px; vertical-align: top; }
 tr:nth-child(even) td { background: #f5f8fc; }
 thead { display: table-header-group; } tr { page-break-inside: avoid; }
 .c { text-align: center; } .r { text-align: right; white-space: nowrap; }
 .sum { margin-top: 5mm; display: flex; gap: 8mm; }
 .box { border: 1px solid #c5d3e8; border-radius: 4px; padding: 3mm 5mm; }
 .box div { font-size: 8pt; color: #555; } .box b { font-size: 12pt; }
 .sign { margin-top: 14mm; display: flex; justify-content: space-between; font-size: 9pt; }
 .sign span { border-top: 1px solid #333; padding-top: 2mm; width: 70mm; text-align: center; }
 .foot { margin-top: 4mm; font-size: 7.5pt; color: #777; }
</style></head><body>
<h1>Kniha jázd</h1>
<div class="meta">
 <div><b>Obdobie:</b> ${esc(period)}</div>
 <div><b>Vodič:</b> ${esc(driver)}</div>
 <div><b>Vozidlo:</b> ${esc(vehicle?.name ?: "—")}</div>
 <div><b>EČV:</b> ${esc(vehicle?.plate?.ifBlank { null } ?: "—")}</div>
 <div><b>Spotreba:</b> ${String.format(sk, "%.1f", (vehicle?.consumptionX10 ?: s.fuelConsumptionX10) / 10.0)} ${vehicle?.unit ?: "l"}/100 km, ${String.format(sk, "%.2f", (vehicle?.fuelPriceCents ?: s.fuelPriceCents) / 100.0)} €/${vehicle?.unit ?: "l"}</div>
</div>
<table><thead><tr>
 <th>#</th><th>Dátum</th><th>Čas</th><th>Odkiaľ</th><th>Kam</th><th>Účel jazdy</th><th>Typ</th>
 <th>Tach. začiatok</th><th>Tach. koniec</th><th>km</th><th>Palivo (${vehicle?.unit ?: "l"})</th><th>Náklady (€)</th>
</tr></thead><tbody>
$rows
</tbody></table>
<div class="sum">
 <div class="box"><div>Jázd</div><b>${trips.size}</b></div>
 <div class="box"><div>Spolu</div><b>${km(total)} km</b></div>
 <div class="box"><div>Služobné (S)</div><b>${km(business)} km</b></div>
 <div class="box"><div>Súkromné (P)</div><b>${km(total - business)} km</b></div>
 <div class="box"><div>Palivo</div><b>${String.format(sk, "%.1f", trips.sumOf { it.liters(vehicle, s) })} ${vehicle?.unit ?: "l"}</b></div>
 <div class="box"><div>Náklady (odhad)</div><b>${String.format(sk, "%.2f", trips.sumOf { it.cost(vehicle, s) })} €</b></div>
</div>
<div class="sign"><span>Dátum</span><span>Podpis vodiča</span></div>
<div class="foot">Vygenerované aplikáciou Car Tracking ${esc(formatDateTime(System.currentTimeMillis()))}. Vzdialenosti sú merané GPS,
náklady na palivo sú odhad podľa nastavenej spotreby a ceny. Typ: S = služobná, P = súkromná.</div>
</body></html>"""
    }

    /** CSV (oddeľovač „;“ – Excel v slovenskom prostredí ho otvorí priamo). */
    fun logbookCsv(trips: List<TripEntity>, s: AppSettings, odometerStart: Double?, vehicle: VehicleEntity?): String {
        fun q(v: String?) = "\"" + (v ?: "").replace("\"", "\"\"") + "\""
        val sb = StringBuilder("﻿") // BOM kvôli diakritike v Exceli
        vehicle?.let { sb.append("Vozidlo;").append(q(it.name)).append(";EČV;").append(q(it.plate)).append("\r\n") }
        sb.append("Dátum;Začiatok;Koniec;Odkiaľ;Kam;Účel;Typ;Tachometer začiatok;Tachometer koniec;km;Trvanie min;Ø km/h;Max km/h;Palivo l;Náklady €;Skóre štýlu;Prudké brzdenia;Prudké zrýchlenia\r\n")
        var odo = odometerStart
        for (t in trips) {
            val from = odo
            val to = odo?.plus(t.distanceM / 1000.0)
            odo = to
            sb.append(listOf(
                formatDate(t.startedAt), formatTime(t.startedAt), t.endedAt?.let { formatTime(it) } ?: "",
                q(t.startAddress), q(t.endAddress), q(t.note),
                if (t.category == CATEGORY_BUSINESS) "služobná" else "súkromná",
                from?.toLong()?.toString() ?: "", to?.toLong()?.toString() ?: "",
                String.format(sk, "%.2f", t.distanceM / 1000.0),
                (((t.endedAt ?: t.startedAt) - t.startedAt) / 60_000).toString(),
                t.avgSpeedKmh.toInt().toString(), t.maxSpeedKmh.toInt().toString(),
                String.format(sk, "%.2f", t.liters(vehicle, s)), String.format(sk, "%.2f", t.cost(vehicle, s)),
                t.score?.toString() ?: "", t.hardBrakes.toString(), t.hardAccels.toString(),
            ).joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }

    private const val CSS = """
 @page { size: A4 portrait; margin: 14mm; }
 body { font-family: 'Roboto', Arial, sans-serif; font-size: 9.5pt; color: #111; }
 h1 { font-size: 17pt; margin: 0 0 2mm; color: #0d47a1; }
 .meta { display: flex; flex-wrap: wrap; gap: 4mm 10mm; margin-bottom: 4mm; }
 table { width: 100%; border-collapse: collapse; }
 th { background: #0d47a1; color: #fff; font-weight: 600; padding: 4px; font-size: 8.5pt; text-align: left; }
 td { border-bottom: 1px solid #ddd; padding: 3.5px 4px; vertical-align: top; }
 tr:nth-child(even) td { background: #f5f8fc; }
 thead { display: table-header-group; } tr { page-break-inside: avoid; }
 .r { text-align: right; white-space: nowrap; } .c { text-align: center; }
 tfoot td { font-weight: bold; border-top: 2px solid #0d47a1; background: #fff !important; }
 .sum { margin-top: 5mm; display: flex; gap: 6mm; flex-wrap: wrap; }
 .box { border: 1px solid #c5d3e8; border-radius: 4px; padding: 3mm 5mm; }
 .box div { font-size: 8pt; color: #555; } .box b { font-size: 12pt; }
 .sign { margin-top: 16mm; display: flex; justify-content: space-between; font-size: 9pt; }
 .sign span { border-top: 1px solid #333; padding-top: 2mm; width: 65mm; text-align: center; }
 .foot { margin-top: 4mm; font-size: 7.5pt; color: #777; }
"""
    private fun money(v: Double) = String.format(sk, "%.2f", v)
    private fun route(t: TripEntity) = listOf(t.startPlace ?: t.startAddress ?: "—", t.endPlace ?: t.endAddress ?: "—").joinToString(" → ")

    /** Riadok cestovných náhrad za jednu služobnú jazdu. */
    data class Reimb(val trip: TripEntity, val km: Double, val basic: Double, val fuel: Double) { val total get() = basic + fuel }

    fun reimbursements(trips: List<TripEntity>, v: VehicleEntity, s: AppSettings): List<Reimb> =
        trips.filter { !it.isWalk && it.category == CATEGORY_BUSINESS }.sortedBy { it.startedAt }.map { t ->
            val km = t.distanceM / 1000.0
            Reimb(t, km, km * v.ratePerKm, t.cost(v, s))
        }

    /** Výkaz cestovných náhrad (základná náhrada za km + náhrada za palivo) za služobné jazdy vozidla. */
    fun reimbursementHtml(rows: List<Reimb>, v: VehicleEntity, period: String, driver: String): String {
        val body = StringBuilder()
        rows.forEachIndexed { i, r ->
            body.append("<tr><td class='c'>${i + 1}</td><td>${formatDate(r.trip.startedAt)}</td><td>${esc(route(r.trip))}</td>")
                .append("<td>${esc(r.trip.note.ifBlank { "služobná cesta" })}</td><td class='r'>${String.format(sk, "%.1f", r.km)}</td>")
                .append("<td class='r'>${money(r.basic)}</td><td class='r'>${money(r.fuel)}</td><td class='r'><b>${money(r.total)}</b></td></tr>\n")
        }
        val km = rows.sumOf { it.km }
        return """<!doctype html><html lang="sk"><head><meta charset="utf-8"><title>Cestovné náhrady – ${esc(period)}</title><style>$CSS</style></head><body>
<h1>Cestovné náhrady – použitie vlastného vozidla</h1>
<div class="meta">
 <div><b>Obdobie:</b> ${esc(period)}</div><div><b>Zamestnanec:</b> ${esc(driver)}</div>
 <div><b>Vozidlo:</b> ${esc(v.name)}</div><div><b>EČV:</b> ${esc(v.plate.ifBlank { "—" })}</div>
 <div><b>Sadzba základnej náhrady:</b> ${String.format(sk, "%.4f", v.ratePerKm)} €/km</div>
 <div><b>Spotreba:</b> ${String.format(sk, "%.1f", v.consumptionX10 / 10.0)} ${v.unit}/100 km · <b>cena PHL:</b> ${money(v.fuelPriceCents / 100.0)} €/${v.unit}</div>
</div>
<table><thead><tr><th>#</th><th>Dátum</th><th>Trasa</th><th>Účel</th><th>km</th><th>Základná náhrada €</th><th>Náhrada za PHL €</th><th>Spolu €</th></tr></thead>
<tbody>$body</tbody>
<tfoot><tr><td></td><td colspan="3">Spolu (${rows.size} jázd)</td><td class='r'>${String.format(sk, "%.1f", km)}</td>
<td class='r'>${money(rows.sumOf { it.basic })}</td><td class='r'>${money(rows.sumOf { it.fuel })}</td><td class='r'>${money(rows.sumOf { it.total })}</td></tr></tfoot></table>
<div class="sum">
 <div class="box"><div>Služobné km</div><b>${String.format(sk, "%.1f", km)} km</b></div>
 <div class="box"><div>Základná náhrada</div><b>${money(rows.sumOf { it.basic })} €</b></div>
 <div class="box"><div>Náhrada za PHL</div><b>${money(rows.sumOf { it.fuel })} €</b></div>
 <div class="box"><div>Na vyplatenie</div><b>${money(rows.sumOf { it.total })} €</b></div>
</div>
<div class="sign"><span>Podpis zamestnanca</span><span>Schválil (zamestnávateľ)</span></div>
<div class="foot">Vygenerované aplikáciou Car Tracking ${esc(formatDateTime(System.currentTimeMillis()))}. Vzdialenosti sú merané GPS.
Náhrada za PHL = km × spotreba / 100 × cena PHL podľa údajov vozidla. Sadzbu základnej náhrady a spôsob výpočtu overte podľa platných predpisov a pokynov zamestnávateľa.</div>
</body></html>"""
    }

    fun reimbursementCsv(rows: List<Reimb>, v: VehicleEntity): String {
        fun q(x: String?) = "\"" + (x ?: "").replace("\"", "\"\"") + "\""
        val sb = StringBuilder("﻿")
        sb.append("Vozidlo;").append(q(v.name)).append(";EČV;").append(q(v.plate)).append(";Sadzba €/km;").append(String.format(sk, "%.4f", v.ratePerKm)).append("\r\n")
        sb.append("Dátum;Trasa;Účel;km;Základná náhrada €;Náhrada za PHL €;Spolu €\r\n")
        rows.forEach { r ->
            sb.append(listOf(formatDate(r.trip.startedAt), q(route(r.trip)), q(r.trip.note), String.format(sk, "%.2f", r.km),
                money(r.basic), money(r.fuel), money(r.total)).joinToString(";")).append("\r\n")
        }
        sb.append(listOf("Spolu", "", "", String.format(sk, "%.2f", rows.sumOf { it.km }), money(rows.sumOf { it.basic }),
            money(rows.sumOf { it.fuel }), money(rows.sumOf { it.total })).joinToString(";")).append("\r\n")
        return sb.toString()
    }

    /** Spoločná kniha jázd zdieľaného vozidla – jazdy všetkých vodičov. */
    fun sharedLogbookHtml(trips: List<sk.cartracker.data.remote.SharedTrip>, v: VehicleEntity, period: String): String {
        val body = StringBuilder()
        trips.sortedBy { it.startedAt }.forEachIndexed { i, t ->
            val business = t.category == CATEGORY_BUSINESS
            body.append("<tr><td class='c'>${i + 1}</td><td>${formatDate(t.startedAt)}</td>")
                .append("<td class='c'>${formatTime(t.startedAt)}–${t.endedAt?.let { formatTime(it) } ?: ""}</td>")
                .append("<td>${esc(t.driverName)}</td><td>${esc(t.startPlace ?: t.startAddress ?: "—")}</td><td>${esc(t.endPlace ?: t.endAddress ?: "—")}</td>")
                .append("<td>${esc(t.note?.ifBlank { null } ?: if (business) "služobná cesta" else "")}</td><td class='c'>${if (business) "S" else "P"}</td>")
                .append("<td class='r'><b>${km(t.distanceM)}</b></td><td class='r'>${money(v.cost(t.distanceM))}</td></tr>\n")
        }
        val byDriver = trips.groupBy { it.driverName ?: "?" }.map { (d, l) -> "<div class='box'><div>${esc(d)}</div><b>${km(l.sumOf { it.distanceM })} km</b></div>" }
        return """<!doctype html><html lang="sk"><head><meta charset="utf-8"><title>Spoločná kniha jázd – ${esc(period)}</title><style>$CSS
 @page { size: A4 landscape; }</style></head><body>
<h1>Kniha jázd – všetci vodiči</h1>
<div class="meta"><div><b>Obdobie:</b> ${esc(period)}</div><div><b>Vozidlo:</b> ${esc(v.name)}</div><div><b>EČV:</b> ${esc(v.plate.ifBlank { "—" })}</div>
<div><b>Vlastník:</b> ${esc(v.ownerName ?: "ja")}</div></div>
<table><thead><tr><th>#</th><th>Dátum</th><th>Čas</th><th>Vodič</th><th>Odkiaľ</th><th>Kam</th><th>Účel</th><th>Typ</th><th>km</th><th>Náklady €</th></tr></thead>
<tbody>$body</tbody>
<tfoot><tr><td></td><td colspan="7">Spolu ${trips.size} jázd</td><td class='r'>${km(trips.sumOf { it.distanceM })}</td><td class='r'>${money(v.cost(trips.sumOf { it.distanceM }))}</td></tr></tfoot></table>
<div class="sum">${byDriver.joinToString("")}</div>
<div class="foot">Vygenerované aplikáciou Car Tracking ${esc(formatDateTime(System.currentTimeMillis()))} zo servera. Typ: S = služobná, P = súkromná.</div>
</body></html>"""
    }

    /** Pracovný výkaz chôdze (kroky a vzdialenosť v pracovnom čase). */
    fun workWalkHtml(days: List<sk.cartracker.ui.screens.WorkDay>, period: String, name: String, s: AppSettings): String {
        val body = StringBuilder()
        days.forEach { d ->
            body.append("<tr><td>${formatDay(d.day)}</td><td class='r'>${String.format(sk, "%,d", d.steps)}</td>")
                .append("<td class='r'>${String.format(sk, "%.2f", d.distanceM / 1000)}</td><td class='r'>${formatDuration(d.ms)}</td><td class='c'>${d.records}</td></tr>\n")
        }
        val steps = days.sumOf { it.steps }
        val hours = "%02d:%02d–%02d:%02d".format(s.workFromMin / 60, s.workFromMin % 60, s.workToMin / 60, s.workToMin % 60)
        return """<!doctype html><html lang="sk"><head><meta charset="utf-8"><title>Pracovný výkaz chôdze – ${esc(period)}</title><style>$CSS</style></head><body>
<h1>Pracovný výkaz chôdze</h1>
<div class="meta"><div><b>Obdobie:</b> ${esc(period)}</div><div><b>Meno:</b> ${esc(name)}</div><div><b>Pracovný čas:</b> $hours</div></div>
<table><thead><tr><th>Deň</th><th>Kroky</th><th>km</th><th>Čas chôdze</th><th>Záznamov</th></tr></thead>
<tbody>$body</tbody>
<tfoot><tr><td>Spolu (${days.size} dní)</td><td class='r'>${String.format(sk, "%,d", steps)}</td><td class='r'>${String.format(sk, "%.2f", days.sumOf { it.distanceM } / 1000)}</td>
<td class='r'>${formatDuration(days.sumOf { it.ms })}</td><td class='c'>${days.sumOf { it.records }}</td></tr></tfoot></table>
<div class="sum">
 <div class="box"><div>Priemer krokov / deň</div><b>${if (days.isNotEmpty()) String.format(sk, "%,d", steps / days.size) else "0"}</b></div>
 <div class="box"><div>Priemer km / deň</div><b>${if (days.isNotEmpty()) String.format(sk, "%.2f", days.sumOf { it.distanceM } / 1000 / days.size) else "0"}</b></div>
</div>
<div class="foot">Vygenerované aplikáciou Car Tracking ${esc(formatDateTime(System.currentTimeMillis()))}. Kroky z krokomera telefónu, vzdialenosť = kroky × dĺžka kroku alebo GPS.
Chôdza mimo pracovného času sa nezapočítava; záznam presahujúci pracovný čas sa započíta pomerne.</div>
</body></html>"""
    }

    fun workWalkCsv(days: List<sk.cartracker.ui.screens.WorkDay>): String {
        val sb = StringBuilder("﻿")
        sb.append("Dátum;Kroky;km;Čas chôdze (min);Záznamov\r\n")
        days.forEach { d ->
            sb.append(listOf(formatDate(d.day), d.steps.toString(), String.format(sk, "%.2f", d.distanceM / 1000),
                (d.ms / 60_000).toString(), d.records.toString()).joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }

    /** Časová os dňa na tlač. */
    fun timelineHtml(seg: List<DaySegment>, day: Long, vehicles: Map<String, VehicleEntity>, s: AppSettings, name: String): String {
        val K = DaySegment.Kind
        val rows = StringBuilder()
        seg.forEach { g ->
            val t = g.trip
            val (icon, what) = when (g.kind) {
                K.CAR -> "🚗" to "Jazda"
                K.WALK -> "🚶" to "Chôdza"
                K.STAY -> "🅿️" to "Pobyt"
            }
            val desc = if (t == null) esc(g.place ?: "na mieste")
                else esc((t.startPlace ?: t.startAddress ?: "?") + " → " + (t.endPlace ?: t.endAddress ?: "?")) +
                    (if (t.note.isNotBlank()) "<div style='color:#666'>${esc(t.note)}</div>" else "")
            val detail = when {
                t == null -> ""
                g.kind == K.CAR -> "Ø ${t.avgSpeedKmh.toInt()} km/h" + (vehicles[t.vehicleUuid]?.let { " · " + esc(it.name) } ?: "") +
                    (if (t.category == CATEGORY_BUSINESS) " · služobná" else "")
                else -> String.format(sk, "%,d krokov", t.steps)
            }
            rows.append("<tr class='${g.kind.name.lowercase()}'><td class='c'>${formatTime(g.from)}–${formatTime(g.to)}</td><td>$icon $what</td><td>$desc</td>")
                .append("<td class='r'>${t?.let { km(it.distanceM) } ?: ""}</td><td class='r'>${formatDuration(g.ms)}</td><td>$detail</td></tr>\n")
        }
        val cars = seg.filter { it.kind == K.CAR }.mapNotNull { it.trip }
        val walks = seg.filter { it.kind == K.WALK }.mapNotNull { it.trip }
        return """<!doctype html><html lang="sk"><head><meta charset="utf-8"><title>Časová os – ${esc(formatDay(day))}</title><style>$CSS
 tr.car td:first-child { border-left: 4px solid #2e7d32; } tr.walk td:first-child { border-left: 4px solid #1565c0; } tr.stay td { color: #555; }
 tr.stay td:first-child { border-left: 4px solid #bcaaa4; }</style></head><body>
<h1>Časová os dňa</h1>
<div class="meta"><div><b>Deň:</b> ${esc(formatDay(day))}</div><div><b>Meno:</b> ${esc(name)}</div></div>
<table><thead><tr><th>Čas</th><th>Činnosť</th><th>Odkiaľ → kam / miesto</th><th>km</th><th>Trvanie</th><th>Podrobnosti</th></tr></thead>
<tbody>$rows</tbody></table>
<div class="sum">
 <div class="box"><div>Autom</div><b>${km(cars.sumOf { it.distanceM })} km</b></div>
 <div class="box"><div>Jázd / za volantom</div><b>${cars.size} · ${formatDuration(cars.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt })}</b></div>
 <div class="box"><div>Pešo</div><b>${km(walks.sumOf { it.distanceM })} km · ${String.format(sk, "%,d", walks.sumOf { it.steps })} krokov</b></div>
 <div class="box"><div>Náklady na palivo</div><b>${money(cars.sumOf { it.cost(vehicles[it.vehicleUuid], s) })} €</b></div>
</div>
<div class="foot">Vygenerované aplikáciou Car Tracking ${esc(formatDateTime(System.currentTimeMillis()))}.</div>
</body></html>"""
    }

    fun timelineCsv(seg: List<DaySegment>, vehicles: Map<String, VehicleEntity>): String {
        fun q(x: String?) = "\"" + (x ?: "").replace("\"", "\"\"") + "\""
        val sb = StringBuilder("﻿")
        sb.append("Dátum;Od;Do;Činnosť;Odkiaľ;Kam / miesto;km;Trvanie min;Kroky;Vozidlo;Typ;Účel\r\n")
        seg.forEach { g ->
            val t = g.trip
            sb.append(listOf(formatDate(g.from), formatTime(g.from), formatTime(g.to),
                when (g.kind.name) { "CAR" -> "jazda"; "WALK" -> "chôdza"; else -> "pobyt" },
                q(t?.let { it.startPlace ?: it.startAddress }), q(t?.let { it.endPlace ?: it.endAddress } ?: g.place),
                t?.let { String.format(sk, "%.2f", it.distanceM / 1000) } ?: "", (g.ms / 60_000).toString(),
                t?.steps?.takeIf { it > 0 }?.toString() ?: "", q(t?.vehicleUuid?.let { vehicles[it]?.name }),
                t?.let { if (it.isWalk) "" else if (it.category == CATEGORY_BUSINESS) "služobná" else "súkromná" } ?: "", q(t?.note),
            ).joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }

    fun shareFile(ctx: Context, fileName: String, content: String, mime: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeText(content, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Exportovať").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // WebView musí žiť počas celej tlačovej úlohy
    @SuppressLint("StaticFieldLeak")
    private var printView: WebView? = null

    /** Otvorí systémovú tlač – dá sa vytlačiť alebo uložiť ako PDF. */
    fun printHtml(activity: Activity, jobName: String, html: String? = null, url: String? = null, landscape: Boolean = false) {
        val wv = WebView(activity)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                val media = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4
                activity.getSystemService(PrintManager::class.java)
                    .print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().setMediaSize(media).build())
            }
        }
        printView = wv
        if (url != null) wv.loadUrl(url) else wv.loadDataWithBaseURL(null, html ?: "", "text/html", "UTF-8", null)
    }
}
