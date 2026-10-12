package sk.cartracker.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val locale = Locale("sk", "SK")

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format(locale, "%.1f km", m / 1000.0)

fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

fun formatDateTime(ts: Long): String = SimpleDateFormat("d. M. yyyy HH:mm", locale).format(Date(ts))
fun formatTime(ts: Long): String = SimpleDateFormat("HH:mm", locale).format(Date(ts))
fun formatDay(ts: Long): String = SimpleDateFormat("EEEE d. MMMM yyyy", locale).format(Date(ts))

fun formatMoney(eur: Double): String = String.format(locale, "%.2f €", eur)
fun formatMonth(ts: Long): String = SimpleDateFormat("LLLL yyyy", locale).format(Date(ts)).replaceFirstChar { it.uppercase() }
fun formatDate(ts: Long): String = SimpleDateFormat("d. M. yyyy", locale).format(Date(ts))
