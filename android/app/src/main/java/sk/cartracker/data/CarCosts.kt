package sk.cartracker.data

import sk.cartracker.data.local.ExpenseEntity
import sk.cartracker.data.local.RefuelEntity

/** Celkové náklady na auto za obdobie: palivo + výdavky (servis, poistenie, známka…), cena za km a za mesiac. */
object CarCosts {
    data class Summary(
        val km: Double, val fuel: Double, val fuelFromRefuels: Boolean, val expenses: Double,
        val byType: List<Pair<String, Double>>, val months: Double,
    ) {
        val total get() = fuel + expenses
        val perKm: Double? get() = if (km >= 1) total / km else null
        val perMonth: Double? get() = if (months > 0) total / months else null
    }

    /**
     * [estimatedFuel] = odhad ceny paliva z jázd (spotreba × cena); použije sa, ak v období nie je zadané žiadne tankovanie.
     * [months] = dĺžka obdobia v mesiacoch.
     */
    fun compute(km: Double, refuels: List<RefuelEntity>, expenses: List<ExpenseEntity>, estimatedFuel: Double, months: Double): Summary {
        val r = refuels.filter { !it.deleted }
        val e = expenses.filter { !it.deleted }
        val fuelR = r.sumOf { it.totalPrice }
        val byType = e.groupBy { it.type }.mapValues { (_, l) -> l.sumOf { it.amount } }.entries
            .sortedByDescending { it.value }.map { it.key to it.value }
        return Summary(km, if (r.isNotEmpty()) fuelR else estimatedFuel, r.isNotEmpty(), e.sumOf { it.amount }, byType, months)
    }
}
