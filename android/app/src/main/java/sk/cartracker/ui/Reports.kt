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
