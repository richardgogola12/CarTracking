package sk.cartracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.cartracker.data.local.ExpenseEntity
import sk.cartracker.data.local.RefuelEntity

class CarCostsTest {
    private fun e(type: String, a: Double, del: Boolean = false) = ExpenseEntity(uuid = "$type$a", userId = 1, vehicleUuid = "v", ts = 0, type = type, amount = a, deleted = del)
    private fun r(p: Double) = RefuelEntity(uuid = "r$p", userId = 1, vehicleUuid = "v", ts = 0, liters = 40.0, totalPrice = p, odometerKm = 0)

    @Test fun refuelsWinOverEstimate() {
        val s = CarCosts.compute(1000.0, listOf(r(60.0), r(70.0)), listOf(e("insurance", 300.0), e("service", 120.0), e("parking", 5.0, del = true)), 999.0, 12.0)
        assertTrue(s.fuelFromRefuels)
        assertEquals(130.0, s.fuel, 1e-9)
        assertEquals(420.0, s.expenses, 1e-9)
        assertEquals(0.55, s.perKm!!, 1e-9)
        assertEquals(550.0 / 12, s.perMonth!!, 1e-9)
        assertEquals("insurance", s.byType.first().first)
    }

    @Test fun estimateWithoutRefuels() {
        val s = CarCosts.compute(0.0, emptyList(), emptyList(), 45.5, 1.0)
        assertFalse(s.fuelFromRefuels)
        assertEquals(45.5, s.total, 1e-9)
        assertEquals(null, s.perKm)
    }
}
