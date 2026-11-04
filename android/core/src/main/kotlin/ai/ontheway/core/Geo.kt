package ai.ontheway.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Great-circle distance in metres. */
fun distM(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(h))
}

/** A road route as a polyline, measured in metres along its length. */
class Polyline(val pts: List<LatLon>) {
    private val cum = DoubleArray(pts.size).also { for (i in 1 until pts.size) it[i] = it[i - 1] + distM(pts[i - 1], pts[i]) }
    val lengthM get() = cum.last()

    /** The point [m] metres along the route (clamped to the ends). */
    fun at(m: Double): LatLon {
        if (m <= 0) return pts.first()
        if (m >= lengthM) return pts.last()
        val found = cum.binarySearch(m)
        if (found >= 0) return pts[found]
        val i = -found - 1 // first vertex beyond m
        val f = (m - cum[i - 1]) / (cum[i] - cum[i - 1])
        val a = pts[i - 1]
        val b = pts[i]
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** Points every [stepM] from [fromM] to [toM], each with its distance along the route. */
    fun sample(fromM: Double, toM: Double, stepM: Double): List<Pair<Double, LatLon>> =
        generateSequence(fromM) { it + stepM }.takeWhile { it <= toM }.map { it to at(it) }.toList()

    /** Distance along the route of the point nearest to [p], to within [stepM]. */
    fun along(p: LatLon, stepM: Double = 100.0): Double = sample(0.0, lengthM, stepM).minBy { distM(it.second, p) }.first
}

/**
 * The stretch of [via] (the route through a stop) that leaves [original] near [stop]:
 * the exit, the way to the pump and back. Empty when the stop is right on the road.
 */
fun detourLeg(original: Polyline, via: Polyline, stop: LatLon, offM: Double = 30.0, radiusM: Double = 5_000.0): List<LatLon> {
    val road = original.sample(0.0, original.lengthM, 25.0).map { it.second }.filter { distM(it, stop) < radiusM + 1_000 }
    val off = via.pts.indices.filter { i -> distM(via.pts[i], stop) < radiusM && road.none { distM(it, via.pts[i]) <= offM } }
    if (off.isEmpty()) return emptyList()
    return via.pts.subList((off.first() - 1).coerceAtLeast(0), (off.last() + 2).coerceAtMost(via.pts.size))
}
