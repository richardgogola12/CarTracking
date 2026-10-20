package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedAlerterTest {
    @Test fun repeatsEvery3sWhileSpeeding() {
        val a = SpeedAlerter(3_000)
        val alerts = (0..10).map { s -> a.onFix(true, s * 1000L).alert }   // body každú sekundu 0–10 s
        assertEquals(listOf(true, false, false, true, false, false, true, false, false, true, false), alerts)
    }

    @Test fun oneOkPointDoesNotReset() {
        val a = SpeedAlerter(3_000)
        assertTrue(a.onFix(true, 0).newEpisode)
        a.onFix(false, 1000)                       // jeden bod pod limitom
        val r = a.onFix(true, 2000)
        assertFalse(r.newEpisode); assertFalse(r.alert)   // pokračuje tá istá epizóda, 3 s ešte neubehli
        assertTrue(a.onFix(true, 3000).alert)
    }

    @Test fun twoOkPointsResetAndNextSpeedingAlertsImmediately() {
        val a = SpeedAlerter(3_000)
        a.onFix(true, 0)
        a.onFix(false, 1000); a.onFix(false, 2000)
        assertFalse(a.active)
        val r = a.onFix(true, 2500)
        assertTrue(r.alert); assertTrue(r.newEpisode)
    }

    @Test fun unknownPointsIgnoredAndTickRepeats() {
        val a = SpeedAlerter(3_000)
        a.onFix(true, 0)
        a.onFix(null, 1000); a.onFix(null, 2000)   // nepresné body nič nemenia
        assertTrue(a.active)
        assertFalse(a.onTick(2900)); assertTrue(a.onTick(3000)); assertFalse(a.onTick(4000)); assertTrue(a.onTick(6000))
        a.onFix(false, 6500)
        assertFalse(a.onTick(9500))                // posledný bod pod limitom → neopakuje
    }

    @Test fun intervalZeroMeansOnce() {
        val a = SpeedAlerter(0)
        assertTrue(a.onFix(true, 0).alert)
        assertFalse(a.onFix(true, 10_000).alert)
        assertFalse(a.onTick(20_000))
    }
}
