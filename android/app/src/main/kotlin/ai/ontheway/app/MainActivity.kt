package ai.ontheway.app

import ai.ontheway.core.Detour
import ai.ontheway.core.Kind
import ai.ontheway.core.LatLon
import ai.ontheway.core.Level
import ai.ontheway.core.Mode
import ai.ontheway.core.Osrm
import ai.ontheway.core.Params
import ai.ontheway.core.Place
import ai.ontheway.core.Route
import ai.ontheway.core.corridor
import ai.ontheway.core.detourLeg
import ai.ontheway.core.distM
import ai.ontheway.core.Fuel
import ai.ontheway.core.level
import ai.ontheway.core.parsePlaces
import ai.ontheway.core.pick
import ai.ontheway.core.safeRangeKm
import ai.ontheway.core.savingWon
import ai.pleos.playground.vehicle.Vehicle
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// 시연 trip: from where the navigation says the car is (Pleos emulator default: 판교) down the 경부고속도로
private val PANGYO = LatLon(37.4127, 127.0947)
private val BUSAN = Dest("부산시청", LatLon(35.1798, 129.0750))
private const val FOOD_AHEAD_M = 60_000.0 // DT·음식점: the next hour or so of driving
private const val RECOMPUTE_M = 15_000.0

/**
 * Watches fuel against the route, finds the stations that add the least time to the trip,
 * and puts one on the navigation as a waypoint (automatically when fuel is critical).
 */
class MainActivity : Activity() {
    private val p = Params()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var places: List<Place>
    private var pricesDate = "" // "11/4": the day the Opinet prices in places.csv were downloaded
    private lateinit var osrm: Osrm
    private lateinit var vehicle: Vehicle
    private lateinit var navi: Navi
    private lateinit var voice: Voice
    private var source: CarSource? = null
    private var sourceName = ""
    private var gen = 0 // bumped on every source change so late network results are dropped

    /** Detours computed from [from] towards [dest]. */
    private class Plan(val from: LatLon, val dest: Dest, val route: Route, val fuel: List<Detour>, val food: List<Detour>)

    private var car: CarState? = null
    private var lvl = Level.OK
    private var mode = Mode.MIN_DETOUR
    private var tab = Kind.FUEL
    private var plan: Plan? = null
    private var busy = false
    private var failedAt = 0L
    private var tapped: Detour? = null // a row the driver chose over the automatic pick
    private var added: Detour? = null // the stop now in the navigation
    private var announced = false

    private val levelView by lazy { findViewById<TextView>(R.id.level) }
    private val fuelPct by lazy { findViewById<TextView>(R.id.fuelPct) }
    private val fuelL by lazy { findViewById<TextView>(R.id.fuelL) }
    private val fuelBar by lazy { findViewById<FuelBar>(R.id.fuelBar) }
    private val safe by lazy { findViewById<TextView>(R.id.safe) }
    private val formula by lazy { findViewById<TextView>(R.id.formula) }
    private val toDest by lazy { findViewById<TextView>(R.id.toDest) }
    private val map by lazy { findViewById<MapView>(R.id.map) }
    private val cardTag by lazy { findViewById<TextView>(R.id.cardTag) }
    private val cardName by lazy { findViewById<TextView>(R.id.cardName) }
    private val cardDetour by lazy { findViewById<TextView>(R.id.cardDetour) }
    private val cardMeta by lazy { findViewById<TextView>(R.id.cardMeta) }
    private val cardPrice by lazy { findViewById<TextView>(R.id.cardPrice) }
    private val cardSave by lazy { findViewById<TextView>(R.id.cardSave) }
    private val add by lazy { findViewById<TextView>(R.id.add) }
    private val list by lazy { findViewById<LinearLayout>(R.id.list) }
    private val banner by lazy { findViewById<TextView>(R.id.banner) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        places = parsePlaces(assets.open("places.csv").bufferedReader().readText())
        pricesDate = assets.open("prices_date.txt").bufferedReader().readText().trim().split("-").let { "${it[1].toInt()}/${it[2].toInt()}" }
        osrm = Osrm(Net(cacheDir)::get)
        vehicle = Vehicle(this).also { runCatching { it.initialize() } }
        navi = Navi(this) { msg -> runOnUiThread { note(msg) } }
        navi.onWaypointArrived = { runOnUiThread { added?.let { onArrive(it.place) } } }
        voice = Voice(this) { msg -> runOnUiThread { note(msg) } }

        for (id in listOf(R.id.fuelCard, R.id.card, R.id.list, R.id.map)) findViewById<View>(id).fill(Color.WHITE, 28f)
        map.outlineProvider = ViewOutlineProvider.BACKGROUND
        map.clipToOutline = true
        map.onPick = { id -> pickOnMap(id) }
        click(R.id.demoYellow) { startDemo("여유", 30.0) }
        click(R.id.demoRed) { startDemo("긴급", 9.0) }
        click(R.id.live) { startLive() }
        click(R.id.tabFuel) { tab = Kind.FUEL; tapped = null; focusedAt = 0 }
        click(R.id.tabFood) { tab = Kind.FOOD; tapped = null; focusedAt = 0 }
        click(R.id.modeMin) { mode = Mode.MIN_DETOUR; tapped = null; focusedAt = 0 }
        click(R.id.modeCheap) { mode = Mode.CHEAPEST; tapped = null; focusedAt = 0 }
        add.setOnClickListener { shown()?.let { if (it.place.id != added?.place?.id) addWaypoint(it) } }

        val wanted = arrayOf(
            "pleos.car.permission.CAR_ENERGY", "pleos.car.permission.CAR_INFO", "android.car.permission.CAR_INFO",
            "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH", "pleos.car.permission.TTS_SERVICE",
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        note("주유소 ${places.count { it.kind == Kind.FUEL }}곳 (오피넷 ${pricesDate} 가격 ${places.count { it.verified }}곳) · DT·음식점 ${places.count { it.kind != Kind.FUEL }}곳")
        render()
    }

    private fun click(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener { action(); render() }

    private fun startDemo(name: String, startPct: Double) {
        reset(name)
        val g = gen
        val from = navi.pos ?: PANGYO
        note("${BUSAN.name}까지 경로를 불러오는 중…")
        worker.execute {
            val r = runCatching { osrm.route(from, BUSAN.pos) }
            runOnUiThread {
                if (g != gen) return@runOnUiThread
                r.onSuccess { route ->
                    navi.route(BUSAN)
                    comeBack()
                    source = DemoDrive(route, BUSAN, startPct, p.tankL, p.kmPerL, ::onCar, ::onArrive).also { it.start() }
                    note("시연: ${BUSAN.name}까지 ${(route.distanceM / 1000).roundToInt()} km, 연료 ${startPct.roundToInt()}%로 출발 (1초 = 1 km)")
                }.onFailure { note("경로 서버에 연결할 수 없어요: ${it.message}") }
            }
        }
    }

    /** The navigation comes to the front when it gets a route or waypoint; show the result there briefly, then return. */
    private fun comeBack() = map.postDelayed({
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }, 2500)

    private fun startLive() {
        reset("실차")
        source = PleosCar(this, vehicle, navi, p, ::onCar)
        note("실차 신호: 연료·연비는 Vehicle SDK, 위치·목적지는 내비에서 받아요")
    }

    private fun reset(name: String) {
        source?.stop()
        source = null
        sourceName = name
        gen++
        car = null
        plan = null
        busy = false
        tapped = null
        added = null
        announced = false
        lvl = Level.OK
        render()
    }

    private fun onCar(s: CarState) {
        car = s
        val safeKm = safeRangeKm(s.fuelL, s.kmPerL, p)
        val next = level(safeKm, s.toDestM?.let { it / 1000 }, p)
        val raised = next.ordinal > lvl.ordinal
        if (next != lvl) announced = false
        lvl = next
        val pl = plan
        val pos = s.pos
        val dest = s.dest
        val stale = pl == null || pl.dest != dest || raised || (added == null && pos != null && distM(pl.from, pos) > RECOMPUTE_M)
        if (pos != null && dest != null && stale && !busy && System.currentTimeMillis() - failedAt > 30_000) compute(pos, dest, safeKm)
        else if (raised) act()
        render()
    }

    private fun compute(from: LatLon, dest: Dest, safeKm: Double) {
        busy = true
        val fuel = car?.fuel ?: Fuel.GASOLINE
        val g = gen
        worker.execute {
            val r = runCatching {
                val route = osrm.route(from, dest.pos)
                val len = route.line.lengthM
                val fuelNear = corridor(route.line, 0.0, min(len, safeKm * 1000), places, Kind.FUEL, p.corridorM, p.maxCandidates)
                // each kind gets its own quota: restaurants hug the road and would push rest areas out of one shared list
                val foodNear = listOf(Kind.FOOD, Kind.EAT).flatMap { corridor(route.line, 0.0, min(len, FOOD_AHEAD_M), places, it, p.corridorM, 20) }
                Plan(
                    from, dest, route,
                    osrm.detours(from, dest.pos, fuelNear).map { it.copy(price = it.place.price(fuel)) },
                    osrm.detours(from, dest.pos, foodNear).groupBy { it.place.name }.map { (_, same) -> same.minBy { it.dtSec } },
                )
            }
            runOnUiThread {
                if (g != gen) return@runOnUiThread
                busy = false
                r.onSuccess { pl ->
                    plan = pl
                    tapped = tapped?.let { t -> (pl.fuel + pl.food).find { it.place.id == t.place.id } }
                    act()
                }.onFailure {
                    failedAt = System.currentTimeMillis()
                    note("경로 계산 실패, 30초 뒤 다시 시도: ${it.message}")
                }
                render()
            }
        }
    }

    /** What the car does by itself: speak at YELLOW, put the least-detour station on the route at RED. */
    private fun act() {
        val pl = plan ?: return
        val s = car ?: return
        if (added != null) return
        val best = pick(ahead(pl, s, Kind.FUEL), safeRangeKm(s.fuelL, s.kmPerL, p), mode, lvl, p) ?: return
        when (lvl) {
            Level.RED -> {
                addWaypoint(best)
                say("연료가 부족해요. 가장 덜 돌아가는 ${josa(best.place.label(), "을", "를")} 경유지로 넣었어요. ${spoken(best)}")
            }
            Level.YELLOW -> if (!announced) {
                announced = true
                say("가는 길에 ${josa(best.place.label(), "이", "가")} 있어요. ${spoken(best)} 화면에서 경유지로 넣을 수 있어요.")
            }
            Level.OK -> Unit
        }
    }

    private fun addWaypoint(d: Detour) {
        added = d
        tapped = null
        navi.addWaypoint(d.place, car?.dest)
        comeBack()
        (source as? DemoDrive)?.waypoint(d.place)
        note("경유지 추가: ${d.place.name} (${minutes(d)})")
        render()
    }

    private fun onArrive(place: Place) {
        added = null
        tapped = null
        plan = null
        announced = false
        say(if (place.kind == Kind.FUEL) "${place.name}에서 주유했어요. 목적지로 다시 안내해요." else "${place.name}에 도착했어요.")
        render()
    }

    /** The card: the stop already added, else the driver's tap, else the automatic pick. */
    private fun shown(): Detour? {
        added?.let { if (it.place.kind == tab) return it }
        val pl = plan ?: return null
        val s = car ?: return null
        val list = ahead(pl, s, tab)
        // a tapped stop the car has since passed is no choice any more
        tapped = tapped?.let { t -> list.find { it.place.id == t.place.id } ?: if (t.place.kind == tab) null else t }
        tapped?.let { if (it.place.kind == tab) return it }
        return if (tab == Kind.FUEL) pick(list, safeRangeKm(s.fuelL, s.kmPerL, p), mode, lvl, p) else list.minByOrNull { it.dtSec }
    }

    /** Candidates still in front of the car; the plan is only refreshed every [RECOMPUTE_M], so reach is re-measured from here. */
    private fun ahead(pl: Plan, s: CarState, kind: Kind): List<Detour> {
        val at = s.pos?.let { pl.route.line.along(it, 200.0) } ?: 0.0
        return (if (kind == Kind.FUEL) pl.fuel else pl.food).filter { it.alongM > at + 300 }.map { it.copy(reachM = it.reachM - at) }
    }

    private fun say(text: String) {
        voice.say(text)
        note("음성 · $text")
    }

    private fun note(msg: String) {
        banner.text = msg
    }

    private fun render() {
        chip(R.id.demoYellow, sourceName == "여유")
        chip(R.id.demoRed, sourceName == "긴급")
        chip(R.id.live, sourceName == "실차")
        chip(R.id.tabFuel, tab == Kind.FUEL)
        chip(R.id.tabFood, tab == Kind.FOOD)
        chip(R.id.modeMin, mode == Mode.MIN_DETOUR)
        chip(R.id.modeCheap, mode == Mode.CHEAPEST)
        val modes = if (tab == Kind.FUEL) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.modeMin).visibility = modes
        findViewById<View>(R.id.modeCheap).visibility = modes

        val s = car
        if (s == null) {
            pill(if (source == null && sourceName == "") "신호를 고르세요" else "신호 대기 중", Color.parseColor("#F1F3F7"), Palette.muted)
            fuelPct.text = "—"
            fuelL.text = ""
            fuelBar.set(0.0, 0.0, 0.0)
            safe.text = "—"
            formula.text = "남은 연료(L) × 연비(km/L) × ${p.safety}"
            toDest.text = ""
            map.message(if (sourceName == "") "왼쪽 아래에서 시연 또는 실차 신호를 고르세요" else "신호를 기다리는 중…")
            card(null, null, s, 0.0)
            rows(emptyList(), null, 0.0, 0.0)
            return
        }
        val pct = s.fuelL / s.tankL * 100
        val safeKm = safeRangeKm(s.fuelL, s.kmPerL, p)
        when (lvl) {
            Level.OK -> pill("여유 있어요", Palette.mint, Palette.teal)
            Level.YELLOW -> pill("노랑 · 주유 추천", Palette.yellowBg, Color.parseColor("#9A6A00"))
            Level.RED -> pill("빨강 · 긴급", Palette.redBg, Palette.red)
        }
        fuelPct.text = "${pct.roundToInt()}%"
        fuelL.text = if (s.fuelAssumed) "연료 신호 없음 · ${pct.roundToInt()}% 가정값"
        else "%.1f L / %.0f L · 연비 %.1f km/L".format(s.fuelL, s.tankL, s.kmPerL)
        val fullKm = s.tankL * s.kmPerL * p.safety
        fuelBar.set(pct, p.redKm / fullKm, p.yellowKm / fullKm)
        safe.text = "${safeKm.roundToInt()} km"
        formula.text = "%.1f L × %.1f km/L × %.1f".format(s.fuelL, s.kmPerL, p.safety)
        toDest.text = s.dest?.let { d -> s.toDestM?.let { "${d.name}까지 ${(it / 1000).roundToInt()} km" } ?: d.name }
            ?: "내비에서 목적지를 설정하세요"

        val pl = plan
        if (pl == null) {
            map.message(if (busy) "가는 길을 계산하는 중…" else if (s.dest == null) "목적지가 정해지면 가는 길을 계산해요" else "곧 계산해요")
            card(null, null, s, safeKm)
            rows(emptyList(), null, safeKm, 0.0)
            return
        }
        val line = pl.route.line
        val candidates = ahead(pl, s, tab)
        val picked = shown()
        val carAlong = s.pos?.let { line.along(it, 200.0) } ?: 0.0
        val showTo = if (tab == Kind.FUEL) {
            min(line.lengthM, max(carAlong + safeKm * 1100, (picked?.alongM ?: 0.0) + 5_000))
        } else {
            min(line.lengthM, carAlong + FOOD_AHEAD_M)
        }
        drawMap(pl, s, candidates, picked, carAlong, showTo, safeKm)
        card(picked, pl, s, safeKm)
        rows(candidates, picked, safeKm, carAlong)
    }

    private var mapKey = ""
    private var fitKey = ""
    private var legKey = ""
    private var focusedAt = 0L

    /** The car moves every tick; the plan goes to the map only when something on it changed. */
    private fun drawMap(pl: Plan, s: CarState, candidates: List<Detour>, picked: Detour?, carAlong: Double, showTo: Double, safeKm: Double) {
        s.pos?.let { map.car(it) }
        val line = pl.route.line
        val safeAlong = if (tab == Kind.FUEL) carAlong + safeKm * 1000 else null
        val key = listOf(System.identityHashCode(pl), tab, mode, picked?.place?.id, candidates.size, safeAlong?.let { (it / 2000).toInt() }, (carAlong / 5000).toInt())
            .joinToString("|")
        if (key != mapKey) {
            mapKey = key
            val pts = line.sample(carAlong, showTo, max((showTo - carAlong) / 400, 50.0))
            val pins = JSONArray()
            candidates.sortedBy { it.dtSec }.forEachIndexed { i, d ->
                pins.put(
                    JSONObject().put("id", d.place.id).put("lat", d.place.pos.lat).put("lon", d.place.pos.lon)
                        .put("label", minutes(d)).put("name", d.place.name).put("color", "#%06X".format(0xFFFFFF and dtColor(d)))
                        .put("rank", i).put("top", i < 6).put("pick", d.place.id == picked?.place?.id),
                )
            }
            // refit when there is a new plan, unless the driver just zoomed in on a stop
            val fk = "${System.identityHashCode(pl)}|$tab"
            val fit = fk != fitKey && System.currentTimeMillis() - focusedAt > 20_000
            if (fit) fitKey = fk
            map.show(
                JSONObject()
                    .put("ahead", JSONArray(pts.filter { safeAlong == null || it.first <= safeAlong }.map { it.second }.toJson()))
                    .put("beyond", JSONArray(pts.filter { safeAlong != null && it.first >= safeAlong }.map { it.second }.toJson()))
                    .put("safe", safeAlong?.takeIf { it < showTo }?.let { JSONArray(listOf(line.at(it)).toJson()).get(0) } ?: JSONObject.NULL)
                    .put("dest", if (showTo >= line.lengthM - 1) JSONArray(listOf(line.pts.last()).toJson()).get(0) else JSONObject.NULL)
                    .put("pins", pins)
                    .put("fit", fit),
            )
        }
        // the picked stop's real way off the road and back
        val lk = "${System.identityHashCode(pl)}|${picked?.place?.id}"
        if (lk != legKey) {
            legKey = lk
            val from = s.pos
            val to = s.dest
            if (picked == null || from == null || to == null) {
                map.detour(emptyList())
            } else {
                worker.execute {
                    val leg = runCatching { detourLeg(line, osrm.via(from, picked.place.pos, to.pos).line, picked.place.pos) }.getOrDefault(emptyList())
                    runOnUiThread { if (legKey == lk) map.detour(leg) }
                }
            }
        }
    }

    private fun pickOnMap(id: String) {
        val pl = plan ?: return
        val s = car ?: return
        tapped = ahead(pl, s, tab).find { it.place.id == id } ?: return
        render()
    }

    private fun dtColor(d: Detour) = when {
        d.dtSec <= p.minDetourSec -> Palette.teal
        d.dtSec <= p.cheapSec -> Palette.yellow
        else -> Palette.faint
    }

    private fun card(d: Detour?, pl: Plan?, s: CarState?, safeKm: Double) {
        if (d == null || pl == null || s == null) {
            cardTag.text = when {
                pl != null && tab == Kind.FUEL -> "SafeRange 안에 가는 길 주유소가 없어요"
                pl != null -> "가는 길에 DT·음식점이 없어요"
                else -> "추천"
            }
            cardTag.setTextColor(Palette.faint)
            cardName.text = ""
            cardDetour.text = ""
            cardMeta.text = ""
            cardPrice.text = ""
            cardSave.text = ""
            add.visibility = View.INVISIBLE
            return
        }
        val isAdded = d.place.id == added?.place?.id
        cardTag.text = when {
            isAdded -> "경유지에 추가됨"
            d.place.id == tapped?.place?.id -> "직접 고른 곳"
            tab == Kind.FOOD -> "가장 덜 돌아가는 곳"
            lvl == Level.RED -> "긴급 · 가장 덜 돌아가는 곳"
            lvl == Level.OK -> "참고 · 지금은 주유하지 않아도 돼요"
            mode == Mode.CHEAPEST -> "최저가 · ${(p.cheapSec / 60).roundToInt()}분 이내 우회에서"
            else -> "최소 우회 · ${(p.minDetourSec / 60).roundToInt()}분 한도"
        }
        cardTag.setTextColor(
            when {
                isAdded -> Palette.teal
                lvl == Level.RED -> Palette.red
                lvl == Level.OK && tab == Kind.FUEL -> Palette.faint
                else -> Palette.teal
            },
        )
        cardName.text = d.place.name
        cardDetour.text = minutes(d)
        val ahead = ((d.alongM - (s.pos?.let { pl.route.line.along(it, 200.0) } ?: 0.0)) / 1000).roundToInt()
        cardMeta.text = "%+.1f km · %d km 앞".format(d.ddM / 1000, ahead)
        val fuelName = if (s.fuel == Fuel.DIESEL) "경유" else "휘발유"
        cardPrice.text = when {
            d.place.kind != Kind.FUEL -> d.place.brand
            d.price != null -> listOfNotNull(d.place.brand.ifBlank { null }, if (d.place.self) "셀프" else null, "$fuelName %,d원".format(d.price))
                .joinToString(" · ")
            else -> listOfNotNull(d.place.brand.ifBlank { null }, "가격 정보 없음 · 오피넷 미확인").joinToString(" · ")
        }
        val ref = if (tab == Kind.FUEL && mode == Mode.CHEAPEST) pick(ahead(pl, s, Kind.FUEL), safeKm, Mode.MIN_DETOUR, lvl, p) else null
        cardSave.setTextColor(Palette.teal)
        cardSave.text = when {
            d.place.kind == Kind.FUEL && d.reachM > safeKm * 1000 -> "SafeRange 밖이에요".also { cardSave.setTextColor(Palette.red) }
            ref != null && ref.place.id != d.place.id ->
                savingWon(d, ref, s.tankL - s.fuelL, s.kmPerL)?.let { "최소 우회보다 가득 주유 시 %,d원 아껴요".format(it) } ?: ""
            else -> ""
        }
        add.visibility = View.VISIBLE
        add.text = if (isAdded) "경유지에 추가됨 ✓" else "경유지 추가"
        add.setTextColor(if (isAdded) Palette.teal else Color.WHITE)
        add.fill(if (isAdded) Palette.mint else Palette.teal, 20f)
    }

    private var rowsKey = ""
    private val rowRight = ArrayList<TextView>()

    /** Rebuilt only when the rows change: rebuilding every tick would swallow the driver's tap. */
    private fun rows(candidates: List<Detour>, picked: Detour?, safeKm: Double, carAlong: Double) {
        val ordered = when {
            tab == Kind.FUEL && mode == Mode.CHEAPEST -> candidates.sortedWith(
                compareBy<Detour> { !(it.dtSec <= p.cheapSec && it.ddM <= p.cheapM && it.reachM <= safeKm * 1000) }
                    .thenBy { it.price ?: Int.MAX_VALUE }.thenBy { it.dtSec },
            )
            else -> candidates.sortedBy { it.dtSec }
        }.take(6)
        fun right(d: Detour) = when {
            d.place.kind != Kind.FUEL -> "${((d.alongM - carAlong) / 1000).roundToInt()} km 앞"
            d.price != null -> "%,d원".format(d.price)
            else -> "가격 없음"
        }
        val key = listOf(tab, mode, picked?.place?.id, candidates.size).joinToString("|") + ordered.joinToString("|") {
            "${it.place.id}:${minutes(it)}:${it.price}:${it.place.kind != Kind.FUEL || it.reachM <= safeKm * 1000}"
        }
        if (key == rowsKey) {
            // a new plan can keep the rows but moves alongM to its own start, so read this call's list
            rowRight.forEachIndexed { i, view -> view.text = right(ordered[i]) }
            return
        }
        rowsKey = key
        rowRight.clear()
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = if (candidates.isEmpty()) "" else "가는 길 ${if (tab == Kind.FUEL) "주유소" else "DT·음식점"} ${candidates.size}곳 · " +
                (if (tab == Kind.FUEL && mode == Mode.CHEAPEST) "가격 순" else "우회 시간 순") +
                (if (tab == Kind.FUEL) " · 가격 오피넷 $pricesDate" else "")
            textSize = 20f
            setTextColor(Palette.faint)
            setPadding(16, 4, 16, 8)
        })
        for (d in ordered) {
            val reachable = d.place.kind != Kind.FUEL || d.reachM <= safeKm * 1000
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 0, 16, 0)
                alpha = if (reachable) 1f else 0.4f
                if (d.place.id == picked?.place?.id) fill(Color.parseColor("#EBF2FE"), 14f)
                setOnClickListener {
                    tapped = d
                    focusedAt = System.currentTimeMillis()
                    map.focus(d.place.pos)
                    render()
                }
            }
            row.addView(cell(minutes(d), 110, 30f, dtColor(d), true))
            row.addView(cell(d.place.name, 0, 24f, Palette.navy, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val r = cell(right(d), 0, 24f, if (d.price == null && d.place.kind == Kind.FUEL) Palette.faint else Palette.muted, false)
            rowRight += r
            row.addView(r)
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 70))
        }
    }

    private fun cell(s: String, width: Int, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (width > 0) this.width = width
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun pill(s: String, bg: Int, fg: Int) {
        levelView.text = s
        levelView.setTextColor(fg)
        levelView.fill(bg, 40f)
    }

    private fun chip(id: Int, on: Boolean) = findViewById<TextView>(id).run {
        setTextColor(if (on) Color.WHITE else Palette.navy)
        background = GradientDrawable().apply {
            cornerRadius = 40f
            setColor(if (on) Palette.navy else Color.WHITE)
            setStroke(3, if (on) Palette.navy else Palette.line)
        }
    }

    private fun View.fill(color: Int, radius: Float) {
        background = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    }

    private fun spoken(d: Detour) = (d.dtSec / 60).roundToInt().let { if (it <= 0) "거의 돌아가지 않아요." else "${it}분 더 걸려요." }

    /** Korean particle by final consonant: 을/를, 이/가. */
    private fun josa(word: String, withFinal: String, without: String): String {
        val c = word.lastOrNull() ?: return word
        return word + if (c in '가'..'힣' && (c - '가') % 28 != 0) withFinal else without
    }

    override fun onDestroy() {
        source?.stop()
        worker.shutdownNow()
        navi.release()
        voice.release()
        runCatching { vehicle.release() }
        super.onDestroy()
    }
}
