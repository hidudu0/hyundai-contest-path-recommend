package ai.ontheway.core

import org.json.JSONArray
import org.json.JSONObject

data class Route(val line: Polyline, val durationSec: Double, val distanceM: Double)

/**
 * ΔT/ΔD: how much longer the trip gets by stopping at [place], against the direct route.
 * [reachM] is the road distance to the place (must fit in SafeRange).
 */
data class Detour(
    val place: Place,
    val alongM: Double,
    val reachM: Double,
    val dtSec: Double,
    val ddM: Double,
    val price: Int? = null,
)

/**
 * OSRM HTTP API on the real road network, so highway exits, ICs and U-turns are part of every detour.
 * [fetch] does the GET (the app adds caching; tests pass canned JSON).
 */
class Osrm(private val fetch: (String) -> String, private val base: String = "https://router.project-osrm.org") {

    fun route(from: LatLon, to: LatLon): Route =
        parseRoute(fetch("$base/route/v1/driving/${coord(from)};${coord(to)}?overview=full&geometries=geojson"))

    /** The route that stops at [via] on the way: what the driver actually drives for a detour. */
    fun via(from: LatLon, via: LatLon, to: LatLon): Route =
        parseRoute(fetch("$base/route/v1/driving/${coord(from)};${coord(via)};${coord(to)}?overview=full&geometries=geojson"))

    /** One table request: origin → every place and every place → destination, plus origin → destination. */
    fun detours(from: LatLon, to: LatLon, near: List<Nearby>): List<Detour> {
        if (near.isEmpty()) return emptyList()
        val coords = (listOf(from, to) + near.map { it.place.pos }).joinToString(";") { coord(it) }
        val idx = (2 until near.size + 2).joinToString(";")
        return parseDetours(fetch("$base/table/v1/driving/$coords?sources=0;$idx&destinations=1;$idx&annotations=duration,distance"), near)
    }

    companion object {
        private fun coord(p: LatLon) = "%.6f,%.6f".format(java.util.Locale.ROOT, p.lon, p.lat)

        private fun ok(json: String): JSONObject = JSONObject(json).also {
            require(it.optString("code") == "Ok") { "OSRM: ${it.optString("code")} ${it.optString("message")}" }
        }

        fun parseRoute(json: String): Route {
            val r = ok(json).getJSONArray("routes").getJSONObject(0)
            val c = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until c.length()).map { c.getJSONArray(it).let { p -> LatLon(p.getDouble(1), p.getDouble(0)) } }
            return Route(Polyline(pts), r.getDouble("duration"), r.getDouble("distance"))
        }

        /** Row 0 is the origin, row k the k-th place; column 0 the destination, column k the k-th place. */
        fun parseDetours(json: String, near: List<Nearby>): List<Detour> {
            val o = ok(json)
            val dur = o.getJSONArray("durations")
            val dis = o.getJSONArray("distances")
            fun at(m: JSONArray, r: Int, c: Int): Double? = m.getJSONArray(r).let { if (it.isNull(c)) null else it.getDouble(c) }
            val baseT = at(dur, 0, 0) ?: return emptyList()
            val baseD = at(dis, 0, 0) ?: return emptyList()
            return near.mapIndexedNotNull { i, n ->
                val k = i + 1
                val toT = at(dur, 0, k) ?: return@mapIndexedNotNull null
                val fromT = at(dur, k, 0) ?: return@mapIndexedNotNull null
                val toD = at(dis, 0, k) ?: return@mapIndexedNotNull null
                val fromD = at(dis, k, 0) ?: return@mapIndexedNotNull null
                Detour(n.place, n.alongM, toD, toT + fromT - baseT, toD + fromD - baseD)
            }
        }
    }
}
