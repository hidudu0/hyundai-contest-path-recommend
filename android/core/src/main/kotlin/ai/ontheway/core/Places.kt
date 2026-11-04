package ai.ontheway.core

import kotlin.math.cos

/** FUEL: 주유소. FOOD: 드라이브스루·휴게소 (no price, ranked by detour only). */
enum class Kind { FUEL, FOOD }

enum class Fuel { GASOLINE, DIESEL }

/**
 * A stop. Fuel stations from Opinet are [verified] (operating, with today's prices); stations only
 * OpenStreetMap knows are real places but unverified and without prices.
 */
data class Place(
    val id: String,
    val kind: Kind,
    val name: String,
    val brand: String,
    val pos: LatLon,
    val self: Boolean = false,
    val gasoline: Int? = null,
    val diesel: Int? = null,
    val verified: Boolean = false,
) {
    fun price(fuel: Fuel): Int? = if (fuel == Fuel.DIESEL) diesel else gasoline
}

/** places.csv from tools/build_places.py: `kind,id,lat,lon,name,brand,self,gasoline,diesel,src`, no commas inside fields. */
fun parsePlaces(csv: String): List<Place> = csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
    val c = line.split(',')
    Place(
        c[1], Kind.valueOf(c[0]), c[4], c[5], LatLon(c[2].toDouble(), c[3].toDouble()),
        self = c[6] == "Y", gasoline = c[7].toIntOrNull(), diesel = c[8].toIntOrNull(), verified = c[9] == "opinet",
    )
}.toList()

/** A place near the route ahead: where along the route it sits and how far off the road (straight line). */
data class Nearby(val place: Place, val alongM: Double, val offM: Double)

/**
 * Places of [kind] within [radiusM] of the route between [fromM] and [toM], closest to the road first.
 * Straight-line distance only shortlists; the router decides the real detour.
 * ponytail: keeps the [max] nearest to the road; in dense cities that can cluster near the start, spread by alongM if it matters.
 */
fun corridor(route: Polyline, fromM: Double, toM: Double, places: List<Place>, kind: Kind, radiusM: Double, max: Int): List<Nearby> {
    val samples = route.sample(fromM, toM, 200.0)
    if (samples.isEmpty()) return emptyList()
    val padLat = radiusM / 111_000.0
    val padLon = padLat / cos(Math.toRadians(samples.first().second.lat))
    val minLat = samples.minOf { it.second.lat } - padLat
    val maxLat = samples.maxOf { it.second.lat } + padLat
    val minLon = samples.minOf { it.second.lon } - padLon
    val maxLon = samples.maxOf { it.second.lon } + padLon
    return places.asSequence()
        .filter { it.kind == kind && it.pos.lat in minLat..maxLat && it.pos.lon in minLon..maxLon }
        .mapNotNull { p ->
            val (along, pt) = samples.minBy { distM(it.second, p.pos) }
            val off = distM(pt, p.pos)
            if (off <= radiusM) Nearby(p, along, off) else null
        }
        .sortedBy { it.offM }
        .take(max)
        .toList()
}
