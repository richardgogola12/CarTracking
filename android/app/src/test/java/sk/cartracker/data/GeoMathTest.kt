package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test fun distanceNoveZamkyKomarno() {
        // Nové Zámky (48.0,18.0) → Komárno (47.76,18.13): ~28,3 km
        val d = GeoMath.distanceM(48.0, 18.0, 47.76, 18.13)
        assertEquals(28_300.0, d, 400.0)
    }

    @Test fun distanceSmall() {
        // 0,001° zemepisnej šírky ≈ 111 m
        assertEquals(111.2, GeoMath.distanceM(48.0, 18.0, 48.001, 18.0), 0.5)
        assertEquals(0.0, GeoMath.distanceM(48.0, 18.0, 48.0, 18.0), 1e-9)
    }

    @Test fun angleDiffWraps() {
        assertEquals(20.0, GeoMath.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, GeoMath.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(90.0, GeoMath.angleDiff(45.0, 315.0), 1e-9)
    }
}

class DistanceFilterTest {
    @Test fun stoppedJitterIsIgnored() {
        // státie na semafore: GPS „tancuje“ 12 m pri 1 km/h
        assertNull(DistanceFilter.carStep(12.0, 2.0, 1.0, true, 8f, 10f).meters)
    }

    @Test fun gpsJumpIsIgnored() {
        // 400 m za 3 s pri 50 km/h = odraz od budov
        val r = DistanceFilter.carStep(400.0, 3.0, 50.0, true, 10f, 10f)
        assertNull(r.meters); assertTrue(r.jump)
    }

    @Test fun zigZagIsCapped() {
        // 50 km/h ≈ 13,9 m/s; za 2 s max 13,9*2*1,1+3 = 33,6 m
        assertEquals(50.0 / 3.6 * 2 * 1.1 + 3, DistanceFilter.carStep(45.0, 2.0, 50.0, true, 5f, 5f).meters!!, 1e-9)
    }

    @Test fun normalStepKept() {
        assertEquals(27.0, DistanceFilter.carStep(27.0, 2.0, 50.0, true, 5f, 5f).meters!!, 1e-9)
    }

    @Test fun highwayDistanceAccurate() {
        // 10 km diaľnice po 1 s pri 130 km/h so šumom ±1 m → súčet do 0,5 %
        val v = 130 / 3.6; var sum = 0.0
        for (i in 0 until (10_000 / v).toInt()) sum += DistanceFilter.carStep(v + if (i % 2 == 0) 1.0 else -1.0, 1.0, 130.0, true, 5f, 5f).meters ?: 0.0
        assertEquals((10_000 / v).toInt() * v, sum, 50.0)
    }

    @Test fun withoutDopplerOldRule() {
        assertNull(DistanceFilter.carStep(4.0, 1.0, 2.0, false, 5f, 5f).meters)
        assertEquals(30.0, DistanceFilter.carStep(30.0, 1.0, 2.0, false, 5f, 5f).meters!!, 1e-9)
    }
}

class DrivingScoreTest {
    @Test fun perfectDrive() = assertEquals(100, DrivingScore.compute(20_000.0, 1_200_000, 0, 0, 0))

    @Test fun eventsPer10km() {
        // 20 km, 2 brzdenia → 12/2 = 6 bodov
        assertEquals(94, DrivingScore.compute(20_000.0, 1_200_000, 2, 0, 0))
    }

    @Test fun speedingCapped() {
        assertEquals(60, DrivingScore.compute(10_000.0, 600_000, 0, 0, 600))
    }
}
