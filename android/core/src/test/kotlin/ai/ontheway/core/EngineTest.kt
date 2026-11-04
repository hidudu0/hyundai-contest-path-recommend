package ai.ontheway.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineTest {
    private val p = Params()
    private fun place(id: String, verified: Boolean) = Place(id, Kind.FUEL, id, "", LatLon(0.0, 0.0), verified = verified)
    private fun detour(id: String, min: Double, km: Double = 0.5, reachKm: Double = 30.0, price: Int? = null, verified: Boolean = true) =
        Detour(place(id, verified), 0.0, reachKm * 1000, min * 60, km * 1000, price)

    // the plan's example: A is +1분 at 1,868원, B is +4분 at 1,792원
    private val a = detour("A", 1.0, price = 1868)
    private val b = detour("B", 4.0, km = 2.5, price = 1792)

    @Test fun safeRangeMatchesThePlanExample() = assertEquals(96.0, safeRangeKm(10.0, 12.0, p), 1e-9)

    @Test fun levels() {
        assertEquals(Level.OK, level(200.0, null, p))
        assertEquals(Level.YELLOW, level(120.0, null, p))
        assertEquals(Level.RED, level(40.0, null, p))
        assertEquals(Level.YELLOW, level(120.0, 300.0, p))
        assertEquals(Level.OK, level(40.0, 30.0, p), "destination in reach: no stop")
    }

    @Test fun minDetourPicksSmallestDetour() = assertEquals("A", pick(listOf(b, a), 96.0, Mode.MIN_DETOUR, Level.YELLOW, p)?.place?.id)

    @Test fun cheapestPicksLowestPriceInWiderBudget() = assertEquals("B", pick(listOf(a, b), 96.0, Mode.CHEAPEST, Level.YELLOW, p)?.place?.id)

    @Test fun redAlwaysTakesSmallestDetour() = assertEquals("A", pick(listOf(a, b), 40.0, Mode.CHEAPEST, Level.RED, p)?.place?.id)

    @Test fun unreachableStationsNeverPicked() {
        val far = detour("far", 0.0, reachKm = 120.0)
        assertEquals("A", pick(listOf(far, a), 96.0, Mode.MIN_DETOUR, Level.YELLOW, p)?.place?.id)
        assertNull(pick(listOf(far), 96.0, Mode.MIN_DETOUR, Level.YELLOW, p))
    }

    @Test fun overLimitStillPicksLeastDetourWhenNothingFits() {
        val slow = detour("slow", 9.0)
        val slower = detour("slower", 12.0)
        assertEquals("slow", pick(listOf(slower, slow), 96.0, Mode.MIN_DETOUR, Level.YELLOW, p)?.place?.id)
    }

    @Test fun cheapestWithoutPricesFallsBackToMinDetour() {
        val x = detour("x", 2.0)
        val y = detour("y", 1.0)
        assertEquals("y", pick(listOf(x, y), 96.0, Mode.CHEAPEST, Level.YELLOW, p)?.place?.id)
    }

    @Test fun cheapestIgnoresStationsBeyondItsBudget() {
        val cheapButFar = detour("C", 8.0, price = 1700)
        assertEquals("B", pick(listOf(a, b, cheapButFar), 96.0, Mode.CHEAPEST, Level.YELLOW, p)?.place?.id)
    }

    @Test fun opinetConfirmedStationBeatsUnverifiedOneInsideTheLimit() {
        val osmOnly = detour("osm", 0.0, verified = false)
        assertEquals("A", pick(listOf(osmOnly, a), 96.0, Mode.MIN_DETOUR, Level.RED, p)?.place?.id)
        assertEquals("osm", pick(listOf(osmOnly, detour("far", 9.0)), 96.0, Mode.MIN_DETOUR, Level.RED, p)?.place?.id, "unless only it fits the limit")
    }

    @Test fun savingCountsExtraDetourFuel() {
        // 40 L × 76원 = 3,040원, minus 2 km extra ÷ 12 km/L × 1,792원 ≈ 299원
        assertEquals(2741, savingWon(b, a, 40.0, 12.0))
    }
}
