package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import sk.cartracker.data.local.TripEntity
import sk.cartracker.ui.screens.Purposes

class PurposesTest {
    private fun t(ts: Long, dest: String, note: String) = TripEntity(uuid = "$ts", userId = 1, startedAt = ts, endAddress = dest, note = note)

    @Test fun destinationFirstThenTemplatesThenRecent() {
        val h = listOf(t(1, "Hlavná 1, Nitra", "Klient ABC"), t(2, "Hlavná 1, Nitra", "Klient ABC"), t(3, "Hlavná 1, Nitra", "Porada"),
            t(4, "Lesná 5, Imeľ", "Nákup"), t(5, "Iné", ""))
        val r = Purposes.suggest(h, "hlavná 1, nitra", listOf("Porada", "Servis"), current = "")
        assertEquals(listOf("Klient ABC", "Porada", "Servis", "Nákup"), r)
    }

    @Test fun currentIsSkippedAndTemplatesParsed() {
        assertEquals(listOf("A", "B"), Purposes.templates(" A \n\nB\nA"))
        assertEquals(listOf("B"), Purposes.suggest(emptyList(), null, listOf("A", "B"), current = "A"))
    }
}
