package sk.cartracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sk.cartracker.container
import sk.cartracker.data.local.TripEntity

/** Návrhy účelu jazdy: najprv účely použité pri jazdách do toho istého cieľa, potom šablóny a naposledy použité. */
object Purposes {
    fun templates(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Čistá funkcia (testovateľná): poradie návrhov. */
    fun suggest(history: List<TripEntity>, destination: String?, templates: List<String>, current: String, max: Int = 10): List<String> {
        val dest = destination?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        fun TripEntity.destKey() = (endPlace ?: endAddress)?.trim()?.lowercase()
        val withNote = history.filter { it.note.isNotBlank() && !it.isWalk }
        val forDest = if (dest == null) emptyList() else withNote.filter { it.destKey() == dest }
            .groupingBy { it.note.trim() }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val recent = withNote.sortedByDescending { it.startedAt }.map { it.note.trim() }.distinct()
        return (forDest + templates + recent).distinct().filter { it != current.trim() && it.length <= 80 }.take(max)
    }
}

/** Riadok s ťukacími návrhmi účelu – ťuknutie vloží text do poľa. */
@Composable
fun PurposeChips(current: String, destination: String?, onPick: (String) -> Unit) {
    val c = LocalContext.current.container
    var list by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(destination, current) {
        val now = System.currentTimeMillis()
        val history = c.trips.tripsBetween(now - 180L * 86_400_000, now + 86_400_000)
        list = Purposes.suggest(history, destination, Purposes.templates(c.settings.current().purposeTemplates), current)
    }
    if (list.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { p -> AssistChip(onClick = { onPick(p) }, label = { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
    }
}
