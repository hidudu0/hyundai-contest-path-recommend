package ai.ontheway.core

import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * `./gradlew :core:savings` — what choosing by detour saves against the station that looks closest.
 * At four points of every calibration trip, [pick] is compared with the nearest Opinet station in a
 * straight line. Writes docs/savings.md, the evidence behind the savings in the README.
 */
private const val WINDOW_M = 100_000.0 // same window as docs/calibration.md
private const val FILL_L = 33.0 // KTDB 자동차이용실태조사 2012: 1회 평균 주유량

private class Row(val trip: String, val pct: Int, val straightM: Double, val near: Detour, val least: Detour, val cheap: Detour, val savedWon: Int?)

private fun bearing(a: LatLon, b: LatLon): Int {
    val dLon = Math.toRadians(b.lon - a.lon)
    val la = Math.toRadians(a.lat)
    val lb = Math.toRadians(b.lat)
    val deg = Math.toDegrees(atan2(sin(dLon) * cos(lb), cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)))
    return ((deg + 360) % 360).toInt()
}

private fun median(xs: List<Double>) = xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

private fun min1(sec: Double) = "%.1f".format(maxOf(sec, 0.0) / 60)

fun main(args: Array<String>) {
    val places = parsePlaces(File(args[0]).readText())
    val opinet = places.filter { it.kind == Kind.FUEL && it.verified }
    val p = Params()
    val osrm = Osrm(::get)
    val rows = mutableListOf<Row>()
    for (t in trips) {
        val route = osrm.route(t.from, t.to)
        for (pct in listOf(20, 40, 60, 80)) {
            val at = route.line.lengthM * pct / 100
            val pos = route.line.at(at)
            val ahead = corridor(route.line, at, min(route.line.lengthM, at + WINDOW_M), places, Kind.FUEL, p.corridorM, p.maxCandidates)
            val closest = opinet.minBy { distM(pos, it.pos) }
            val all = if (ahead.any { it.place == closest }) ahead else ahead + Nearby(closest, at, 0.0)
            val coords = (listOf(pos, t.to) + all.map { it.place.pos }).joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lon, it.lat) }
            val idx = (2 until all.size + 2).joinToString(";")
            // the car is mid-route: the bearing keeps the start on its own carriageway
            val bearings = "${bearing(pos, route.line.at(at + 50))},60" + ";".repeat(all.size + 1)
            val json = get("https://router.project-osrm.org/table/v1/driving/$coords?sources=0;$idx&destinations=1;$idx&annotations=duration,distance&bearings=$bearings")
            val detours = Osrm.parseDetours(json, all).map { it.copy(price = it.place.gasoline) }
            val near = detours.firstOrNull { it.place == closest } ?: continue
            val cands = detours.filter { d -> ahead.any { it.place == d.place } }
            val least = pick(cands, WINDOW_M / 1000, Mode.MIN_DETOUR, Level.YELLOW, p) ?: continue
            val cheap = pick(cands, WINDOW_M / 1000, Mode.CHEAPEST, Level.YELLOW, p) ?: continue
            rows += Row(t.name, pct, distM(pos, closest.pos), near, least, cheap, savingWon(cheap, near, FILL_L, p.kmPerL))
        }
    }
    val saved = rows.map { (it.near.dtSec - it.least.dtSec) / 60 }
    val won = rows.mapNotNull { it.savedWon?.toDouble() }
    val md = buildString {
        append("# 우회 시간·주유비 절약 측정\n\n")
        append("`./gradlew :core:savings`가 앱과 같은 코드(코리도 → OSRM table → `pick`)로 [보정 경로 8개](calibration.md)의 20·40·60·80% 지점 ${rows.size}곳을 돌린 결과입니다. ")
        append("각 지점에서 앞쪽 ${(WINDOW_M / 1000).toInt()} km 안 후보로 고른 주유소를, 그 지점에서 직선으로 가장 가까운 오피넷 주유소에 바로 들렀을 때와 비교합니다.\n\n")
        append("| 항목 | 직선 최단 | 최소 우회 | 최저가 |\n|---|---|---|---|\n")
        append("| ΔT 중앙값 분 | ${min1(median(rows.map { it.near.dtSec }))} | ${min1(median(rows.map { it.least.dtSec }))} | ${min1(median(rows.map { it.cheap.dtSec }))} |\n")
        append("| ΔD 중앙값 km | ${"%.1f".format(median(rows.map { it.near.ddM }) / 1000)} | ${"%.1f".format(median(rows.map { it.least.ddM }) / 1000)} | ${"%.1f".format(median(rows.map { it.cheap.ddM }) / 1000)} |\n\n")
        append("- 최소 우회가 아낀 시간: 중앙값 ${"%.1f".format(median(saved))}분, 평균 ${"%.1f".format(saved.average())}분. ${rows.size}곳 중 ${saved.count { it >= 0 }}곳에서 직선 최단보다 덜 돌아갔습니다.\n")
        append("- 최저가가 아낀 주유비: 중앙값 ${"%,d".format(median(won).roundToInt())}원(${FILL_L.toInt()} L 주유, 더 돌아가며 쓰는 연료값 차감). 가격을 비교할 수 있는 ${won.size}곳 중 ${won.count { it > 0 }}곳에서 절약했습니다.\n\n")
        append("| 경로 | 지점 | 직선 거리 m | 직선 최단 ΔT | 최소 우회 ΔT | 최저가 ΔT | 최저가 절약 원 |\n|---|---|---|---|---|---|---|\n")
        for (r in rows) append("| ${r.trip} | ${r.pct}% | ${r.straightM.roundToInt()} | ${min1(r.near.dtSec)} | ${min1(r.least.dtSec)} | ${min1(r.cheap.dtSec)} | ${r.savedWon?.let { "%,d".format(it) } ?: "-"} |\n")
        append("\n직선 최단은 비교 기준이며 실제 운전자의 선택을 잰 값은 아닙니다. 경로는 OSRM(OpenStreetMap, 실시간 교통 미반영), 가격은 앱에 들어 있는 오피넷 휘발유 가격, 연비는 앱 기본값 ${p.kmPerL.toInt()} km/L입니다. ")
        append("주유소 위치는 `places.csv` 그대로이며 공식 좌표가 아닌 곳은 매칭 오차가 있습니다([docs/stations.md](stations.md)).\n")
    }
    File(args[1]).apply { parentFile.mkdirs() }.writeText(md)
    println(md)
}
