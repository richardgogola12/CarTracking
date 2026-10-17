package sk.cartracker.data

import sk.cartracker.data.local.CATEGORY_BUSINESS
import sk.cartracker.data.local.CATEGORY_PRIVATE
import sk.cartracker.data.local.PlaceEntity
import sk.cartracker.data.local.TripEntity
import java.util.Calendar
import java.util.Locale

/**
 * Automatické zaradenie jazdy autom (služobná / súkromná). Poradie:
 *  1. naučené z vašich rozhodnutí na tej istej trase (začiatok aj cieľ do 400 m, aj opačným smerom),
 *  2. miesto (Domov, Práca, Klient…) s nastaveným účelom,
 *  3. pravidlá času: pracovný čas → služobná, deň voľna → súkromná,
 *  4. predvolený účel.
 */
object TripClassifier {
    data class Decision(val category: String, val reason: String)
    data class Route(val aLat: Double, val aLng: Double, val bLat: Double, val bLng: Double, val business: Int, val private: Int) {
        val total get() = business + private
    }

    private const val MATCH_M = 400f
    private const val MAX_ROUTES = 150

    fun parse(s: String): List<Route> = s.split(';').mapNotNull { e ->
        val p = e.split(',')
        if (p.size != 6) null else runCatching {
            Route(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toInt(), p[5].toInt())
        }.getOrNull()
    }

    private fun format(list: List<Route>) = list.joinToString(";") {
        String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f,%d,%d", it.aLat, it.aLng, it.bLat, it.bLng, it.business, it.private)
    }

    private fun near(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Boolean {
        return GeoMath.distanceM(lat1, lng1, lat2, lng2) <= MATCH_M
    }

    private fun Route.matches(t: TripEntity): Boolean {
        val sl = t.startLat ?: return false; val sg = t.startLng ?: return false
        val el = t.endLat ?: return false; val eg = t.endLng ?: return false
        return (near(aLat, aLng, sl, sg) && near(bLat, bLng, el, eg)) || (near(aLat, aLng, el, eg) && near(bLat, bLng, sl, sg))
    }

    /** Naučené rozhodnutie pre trasu jazdy: aspoň 2 rozhodnutia a 70 % rovnakých. */
    fun learned(t: TripEntity, s: AppSettings): Decision? {
        if (!s.learnCategory) return null
        val r = parse(s.learnedRoutes).firstOrNull { it.matches(t) } ?: return null
        if (r.total < 2) return null
        return when {
            r.business >= r.total * 0.7 -> Decision(CATEGORY_BUSINESS, "naučené z ${r.total} jázd na tejto trase")
            r.private >= r.total * 0.7 -> Decision(CATEGORY_PRIVATE, "naučené z ${r.total} jázd na tejto trase")
            else -> null
        }
    }

    fun decide(t: TripEntity, places: List<PlaceEntity>, placeMatch: (Double?, Double?) -> PlaceEntity?, s: AppSettings): Decision {
        learned(t, s)?.let { return it }
        val a = placeMatch(t.startLat, t.startLng)
        val b = placeMatch(t.endLat, t.endLng)
        listOf(a, b).firstOrNull { it?.category == CATEGORY_BUSINESS }?.let { return Decision(CATEGORY_BUSINESS, "miesto ${it.name}") }
        listOf(a, b).firstOrNull { it?.category == CATEGORY_PRIVATE }?.let { return Decision(CATEGORY_PRIVATE, "miesto ${it.name}") }
        val cal = Calendar.getInstance().apply { timeInMillis = t.startedAt }
        val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val workDay = day in s.workDaySet
        if (s.ruleWorkTime && workDay && min in s.workFromMin until s.workToMin) return Decision(CATEGORY_BUSINESS, "pracovný čas")
        if (s.ruleWeekendPrivate && !workDay) return Decision(CATEGORY_PRIVATE, "deň voľna")
        return Decision(s.defaultCategory, "predvolený účel")
    }

    /** Zapamätá si vaše rozhodnutie pre trasu (z notifikácie alebo z detailu jazdy). */
    fun learn(s: AppSettings, t: TripEntity, category: String): AppSettings {
        if (!s.learnCategory || t.isWalk) return s
        val sl = t.startLat ?: return s; val sg = t.startLng ?: return s
        val el = t.endLat ?: return s; val eg = t.endLng ?: return s
        if (near(sl, sg, el, eg)) return s   // okružná jazda (z domu domov) – trasa sa nedá rozlíšiť
        val list = parse(s.learnedRoutes).toMutableList()
        val i = list.indexOfFirst { it.matches(t) }
        val biz = if (category == CATEGORY_BUSINESS) 1 else 0
        val r = if (i >= 0) list.removeAt(i).let { it.copy(business = (it.business + biz).coerceAtMost(50), private = (it.private + 1 - biz).coerceAtMost(50)) }
            else Route(sl, sg, el, eg, biz, 1 - biz)
        list.add(r)   // naposledy použité na koniec – pri preplnení sa zahodia najstaršie
        return s.copy(learnedRoutes = format(list.takeLast(MAX_ROUTES)))
    }
}
