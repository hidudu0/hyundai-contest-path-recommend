package ai.ontheway.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OsrmTest {
    private fun near(id: String) = Nearby(Place(id, Kind.FUEL, id, "", LatLon(0.0, 0.0)), 0.0, 0.0)

    @Test fun detourIsViaMinusDirect() {
        // origin→dest 600 s / 10 km; via A: 200 + 460 s, 4 + 6.5 km; B unreachable from origin
        val json = """{"code":"Ok",
            "durations":[[600,200,null],[460,0,0],[300,0,0]],
            "distances":[[10000,4000,null],[6500,0,0],[5000,0,0]]}"""
        val d = Osrm.parseDetours(json, listOf(near("A"), near("B")))
        assertEquals(1, d.size)
        assertEquals(60.0, d[0].dtSec)
        assertEquals(500.0, d[0].ddM)
        assertEquals(4000.0, d[0].reachM)
    }

    @Test fun routeGeometryIsLatLon() {
        val r = Osrm.parseRoute("""{"code":"Ok","routes":[{"duration":60,"distance":900,
            "geometry":{"coordinates":[[127.0,37.0],[127.0,37.008]]}}]}""")
        assertEquals(LatLon(37.0, 127.0), r.line.pts.first())
        assertEquals(889.0, r.line.lengthM, 2.0)
    }

    @Test fun errorCodeFails() {
        assertFailsWith<IllegalArgumentException> { Osrm.parseRoute("""{"code":"NoRoute","message":"x"}""") }
    }
}
