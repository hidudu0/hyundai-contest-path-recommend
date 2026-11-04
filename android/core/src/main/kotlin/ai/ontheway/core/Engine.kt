package ai.ontheway.core

import kotlin.math.roundToInt

/** Values marked "calibrated" come from `./gradlew :core:calibrate` (docs/calibration.md). */
data class Params(
    val safety: Double = 0.8,
    val tankL: Double = 50.0, // when the car does not report its tank size
    val kmPerL: Double = 12.0, // until the car reports fuel economy
    val yellowKm: Double = 150.0,
    val redKm: Double = 50.0,
    val minDetourSec: Double = 180.0, // calibrated: smallest limit that leaves ≥ 3 stations on every test route
    val minDetourM: Double = 2_000.0, // calibrated: ~90th percentile ΔD of those stations
    val cheapSec: Double = 300.0, // wider budget for the cheapest mode
    val cheapM: Double = 3_000.0, // calibrated: ~90th percentile ΔD within 5 min
    val corridorM: Double = 1_500.0,
    val maxCandidates: Int = 40,
)

/** OK: quiet. YELLOW: suggest while there is slack. RED: add the least-detour station right away. */
enum class Level { OK, YELLOW, RED }

/** 최소 우회 (default) or 최저가 within a wider detour budget. */
enum class Mode { MIN_DETOUR, CHEAPEST }

/** SafeRange = 남은 연료(L) × 연비(km/L) × Safety Factor */
fun safeRangeKm(fuelL: Double, kmPerL: Double, p: Params): Double = fuelL * kmPerL * p.safety

fun level(safeKm: Double, toDestKm: Double?, p: Params): Level = when {
    toDestKm != null && safeKm >= toDestKm -> Level.OK // the destination is in reach: no stop needed
    safeKm <= p.redKm -> Level.RED
    safeKm <= p.yellowKm -> Level.YELLOW
    else -> Level.OK
}

/**
 * The one station to put on the route. Only stations within SafeRange count, and stations Opinet
 * confirms (operating, priced) come before ones only OpenStreetMap knows.
 * MIN_DETOUR (and always at RED): smallest ΔT within the ΔT·ΔD limit, or overall if none fits.
 * CHEAPEST: lowest price within the wider limit, falling back to MIN_DETOUR without prices.
 */
fun pick(cands: List<Detour>, safeKm: Double, mode: Mode, level: Level, p: Params): Detour? {
    val reachable = cands.filter { it.reachM <= safeKm * 1000 }
    val inLimit = reachable.filter { it.dtSec <= p.minDetourSec && it.ddM <= p.minDetourM }
    val byDetour = compareBy<Detour> { it.dtSec }.thenBy { it.price ?: Int.MAX_VALUE }
    val minDetour = sequenceOf(inLimit.filter { it.place.verified }, inLimit, reachable.filter { it.place.verified }, reachable)
        .firstOrNull { it.isNotEmpty() }?.minWithOrNull(byDetour)
    if (mode == Mode.MIN_DETOUR || level == Level.RED) return minDetour
    return reachable.filter { it.price != null && it.dtSec <= p.cheapSec && it.ddM <= p.cheapM }
        .minWithOrNull(compareBy<Detour> { it.price }.thenBy { it.dtSec }) ?: minDetour
}

/** 원 saved by filling [litres] at [cheap] instead of [ref], minus the fuel burnt on the longer detour. */
fun savingWon(cheap: Detour, ref: Detour, litres: Double, kmPerL: Double): Int? {
    val a = cheap.price ?: return null
    val b = ref.price ?: return null
    val extraL = (cheap.ddM - ref.ddM) / 1000 / kmPerL
    return ((b - a) * litres - extraL * a).roundToInt()
}
