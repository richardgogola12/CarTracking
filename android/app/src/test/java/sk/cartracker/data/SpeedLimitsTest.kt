package sk.cartracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitsTest {
    @Test fun parseNumbersAndCodes() {
        assertEquals(50, SpeedLimits.parse("50"))
        assertEquals(70, SpeedLimits.parse(" 70 "))
        assertEquals(48, SpeedLimits.parse("30 mph"))
        assertEquals(50, SpeedLimits.parse("SK:urban"))
        assertEquals(90, SpeedLimits.parse("SK:rural"))
        assertEquals(130, SpeedLimits.parse("SK:motorway"))
        assertEquals(30, SpeedLimits.parse("SK:zone30"))
        assertEquals(20, SpeedLimits.parse("SK:living_street"))
        assertNull(SpeedLimits.parse("none"))
        assertNull(SpeedLimits.parse("signals"))
        assertNull(SpeedLimits.parse(""))
        assertNull(SpeedLimits.parse(null))
    }

    @Test fun limitOfPrefersDirection() {
        val tags = mapOf("maxspeed" to "90", "maxspeed:forward" to "70", "maxspeed:backward" to "50")
        assertEquals(70, SpeedLimits.limitOf(tags, true))
        assertEquals(50, SpeedLimits.limitOf(tags, false))
        assertEquals(30, SpeedLimits.limitOf(mapOf("zone:maxspeed" to "SK:30"), true))
        assertNull(SpeedLimits.limitOf(mapOf("highway" to "residential"), true))
    }

    @Test fun customLimitRadiusAndDirection() {
        val list = SpeedLimits.parseCustom("48.00000,18.00000,70,90;48.10000,18.10000,30")
        assertEquals(2, list.size)
        assertEquals(70, SpeedLimits.custom(list, 48.0005, 18.0, 95f))     // ~55 m, rovnaký smer
        assertNull(SpeedLimits.custom(list, 48.0005, 18.0, 270f))           // opačný smer
        assertNull(SpeedLimits.custom(list, 48.01, 18.0, 90f))              // 1,1 km ďaleko
        assertEquals(30, SpeedLimits.custom(list, 48.1, 18.1, null))        // bez smeru
        assertEquals(list, SpeedLimits.parseCustom(SpeedLimits.formatCustom(list)))
    }

    @Test fun waysRoundTripAndMatch() {
        val json = JSONObject("""{"elements":[{"type":"way","id":7,"tags":{"highway":"primary","maxspeed":"70","surface":"asphalt"},
            "geometry":[{"lat":48.0,"lon":18.0},{"lat":48.0,"lon":18.009}]}]}""")
        val ways = SpeedLimits.parseWays(json)
        assertEquals(1, ways.size)
        assertNull(ways[0].tags["surface"])                    // nepotrebné tagy sa neukladajú
        val again = SpeedLimits.parseWays(SpeedLimits.toJson(ways))
        assertEquals(ways[0].tags, again[0].tags)
        assertEquals(18.009, again[0].lng[1], 1e-9)
        // overenie, že sa to dá aj nájsť: bod 10 m od cesty, jazda na východ
        SpeedLimits.putForTest(48.0001, 18.004, again)
        val m = SpeedLimits.match(48.0001, 18.004, 90f)
        assertNotNull(m); assertEquals(70, m!!.limit)
        assertNull(SpeedLimits.match(48.0001, 18.004, 0f))    // kolmo na cestu (križovatka)
    }
}
