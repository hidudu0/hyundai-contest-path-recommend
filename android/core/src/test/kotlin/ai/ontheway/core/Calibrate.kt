package ai.ontheway.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `./gradlew :core:calibrate` — runs the app's own corridor + OSRM detour code on real trips
 * and writes docs/calibration.md, the evidence behind the ΔT·ΔD limits in [Params].
 */
internal class Trip(val name: String, val from: LatLon, val to: LatLon)

internal val trips = listOf(
    Trip("광주 → 서울 (호남·경부고속도로)", LatLon(35.1601, 126.8526), LatLon(37.5665, 126.9780)),
    Trip("부산 → 대구 (경부고속도로)", LatLon(35.1796, 129.0756), LatLon(35.8714, 128.6014)),
    Trip("서울 강남 → 수원 (도심+고속)", LatLon(37.4979, 127.0276), LatLon(37.2636, 127.0286)),
    Trip("대전 → 청주 (국도)", LatLon(36.3504, 127.3845), LatLon(36.6424, 127.4890)),
    Trip("강릉 → 원주 (영동고속도로)", LatLon(37.7519, 128.8761), LatLon(37.3422, 127.9202)),
    Trip("서울 시청 → 인천 (도심)", LatLon(37.5665, 126.9780), LatLon(37.4563, 126.7052)),
    Trip("전주 → 군산 (국도)", LatLon(35.8242, 127.1480), LatLon(35.9676, 126.7366)),
    Trip("서울 마포 → 남양주 (강변북로)", LatLon(37.5663, 126.9019), LatLon(37.6360, 127.2165)),
)

private const val WINDOW_M = 100_000.0 // a typical SafeRange when the yellow level starts

private var last = 0L
internal fun get(url: String): String {
    Thread.sleep(maxOf(0L, 1100 - (System.currentTimeMillis() - last))) // public OSRM: about 1 request per second
    last = System.currentTimeMillis()
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "OnTheWay-calibration/0.1 (Hyundai in-car app contest prototype)")
    return (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
}

private fun pct(xs: List<Double>, q: Double) = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[min(it.size - 1, (q * it.size).toInt())] }

fun main(args: Array<String>) {
    val places = parsePlaces(File(args[0]).readText())
    val p = Params()
    val osrm = Osrm(::get)
    val limitsMin = listOf(1, 2, 3, 5, 10)
    val rows = StringBuilder()
    val all = mutableListOf<Detour>()
    val bestPerTrip = mutableListOf<Double>()
    val withinPerTrip = limitsMin.associateWith { mutableListOf<Int>() }
    for (t in trips) {
        val route = osrm.route(t.from, t.to)
        val near = corridor(route.line, 0.0, min(route.line.lengthM, WINDOW_M), places, Kind.FUEL, p.corridorM, p.maxCandidates)
        val d = osrm.detours(t.from, t.to, near)
        all += d
        val best = d.minOf { it.dtSec } / 60
        bestPerTrip += best
        val counts = limitsMin.map { l -> d.count { it.dtSec <= l * 60 } }
        limitsMin.zip(counts).forEach { (l, n) -> withinPerTrip.getValue(l) += n }
        rows.append("| ${t.name} | ${(route.distanceM / 1000).roundToInt()} | ${near.size} | ${"%.1f".format(best)} | ${counts.joinToString(" | ")} |\n")
        println("${t.name}: ${near.size} candidates, best +${"%.1f".format(best)} min, within 1/2/3/5 min = $counts")
    }
    val dd = limitsMin.associateWith { l -> all.filter { it.dtSec <= l * 60 }.map { it.ddM / 1000 } }
    val md = buildString {
        append("# 정할 값 보정 (calibration)\n\n")
        append("`./gradlew :core:calibrate`가 앱과 같은 코드(코리도 → OSRM table)로 실제 경로 ${trips.size}개를 돌린 결과입니다. ")
        append("출발 후 ${(WINDOW_M / 1000).toInt()} km(노랑 단계 SafeRange 수준) 안, 경로에서 ${p.corridorM.toInt()} m 이내 주유소를 최대 ${p.maxCandidates}곳 넣었습니다.\n\n")
        append("| 경로 | 거리 km | 후보 | 최소 ΔT 분 | ΔT ≤ 1분 | ≤ 2분 | ≤ 3분 | ≤ 5분 | ≤ 10분 |\n|---|---|---|---|---|---|---|---|---|\n")
        append(rows)
        append("\n| ΔT 한도 | 후보가 1곳 이상인 경로 | 3곳 이상인 경로 | 그 후보들의 ΔD 중앙값 km | ΔD 90% km |\n|---|---|---|---|---|\n")
        for (l in limitsMin) {
            val n = withinPerTrip.getValue(l)
            append("| $l 분 | ${n.count { it >= 1 }}/${trips.size} | ${n.count { it >= 3 }}/${trips.size} | ${"%.1f".format(pct(dd.getValue(l), 0.5))} | ${"%.1f".format(pct(dd.getValue(l), 0.9))} |\n")
        }
        append("\n앱 기본값 (`Params`): 최소 우회 ΔT ≤ ${(p.minDetourSec / 60).roundToInt()}분·ΔD ≤ ${p.minDetourM / 1000} km, ")
        append("최저가 ΔT ≤ ${(p.cheapSec / 60).roundToInt()}분·ΔD ≤ ${p.cheapM / 1000} km. ")
        append("최소 우회 ΔT 한도는 모든 경로에서 후보가 3곳 이상 남는 가장 작은 값이고, 최저가 ΔT 한도는 가격 선택지를 넓히려고 정한 값입니다. ΔD 한도는 각 ΔT 한도 안 후보들의 ΔD 90% 근처로 잡았습니다. ")
        append("ΔD 한도는 시간은 비슷해도 먼 길로 돌아가는 곳(연료를 더 쓰는 곳)을 거르는 용도입니다.\n")
        append("\n데이터: 경로 OSRM (OpenStreetMap, 실시간 교통 미반영), 주유소 오피넷 + OpenStreetMap 위치 (`tools/build_places.py`, docs/stations.md).\n")
    }
    File(args[1]).apply { parentFile.mkdirs() }.writeText(md)
    println(md)
}
