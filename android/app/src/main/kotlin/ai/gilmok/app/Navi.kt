package ai.gilmok.app

import ai.gilmok.core.LatLon
import ai.gilmok.core.Place
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context

data class Dest(val name: String, val pos: LatLon)

/** "GS칼텍스 흥덕주유소": the brand first, the way maps and the navigation list fuel stations. */
fun Place.label() = if (brand.isBlank() || name.contains(brand.take(2))) name else "$brand $name"

/** Pleos navigation (NaviHelper): position and destination in, route and waypoint out. */
class Navi(context: Context, private val log: (String) -> Unit) {
    private val helper = NaviHelper(context)
    @Volatile private var ready = false
    @Volatile private var ours = false // a stop we added is on the route
    @Volatile var pos: LatLon? = null
        private set
    @Volatile var dest: Dest? = null
        private set
    @Volatile var remainM: Double? = null
        private set
    /** Called on a binder thread. */
    var onWaypointArrived: () -> Unit = {}

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() = poll()
        override fun onRoutingCompleted() = poll()

        // the navigation sometimes answers with an empty destination while it reroutes: keep the last one
        // until the route is cancelled or the destination reached
        override fun onDestinationInfo(info: DestinationInfo) {
            if (info.latitude == 0.0 && info.longitude == 0.0) return
            dest = Dest(info.name.ifBlank { "목적지" }, LatLon(info.latitude, info.longitude))
            remainM = info.remainDistance.takeIf { it > 0 }?.toDouble()
        }

        override fun onRouteCancelled() = clearDest()

        override fun onDestinationArrived(info: DestinationArrivedInfo) = clearDest()

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            pos = LatLon(info.latitude, info.longitude)
        }

        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            ours = false
            onWaypointArrived()
        }

        override fun onError(errorCode: NaviErrorCode) = log("내비: $errorCode")
    }

    init {
        runCatching {
            helper.initialize(
                onConnected = { ready = true; helper.addListener(listener); poll() },
                onFailed = { log("내비 연결 실패: ${it.message}") },
            )
        }.onFailure { log("내비 연결 실패: ${it.message}") }
    }

    private fun clearDest() {
        dest = null
        remainM = null
        ours = false
    }

    fun poll() {
        if (ready) runCatching { helper.getCurrentLocationInfo(); helper.getDestinationInfo() }
    }

    fun route(d: Dest) = call {
        ours = false
        helper.requestRoute(routeInfo(d))
    }

    /**
     * First stop: addWaypoint. Changing our stop: one requestRouteWithWaypoints, because a
     * removeWaypoint + addWaypoint pair races in the navigation and the removal can land last.
     */
    fun addWaypoint(p: Place, dest: Dest?) = call {
        val wp = RequestWaypointInfo(
            latitude = p.pos.lat, longitude = p.pos.lon, poiName = p.name,
            waypointIndex = WaypointIndex.FIRST, poiId = "", address = "", poiSubId = "0",
        )
        if (ours && dest != null) helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(dest), listOf(wp)))
        else helper.addWaypoint(wp)
        ours = true
    }

    private fun routeInfo(d: Dest) = RouteInfo(latitude = d.pos.lat, longitude = d.pos.lon, poiName = d.name, poiId = "", address = "", poiSubId = "0")

    private fun call(block: () -> Unit) {
        if (!ready) return log("내비가 아직 연결되지 않았어요")
        runCatching(block).onFailure { log("내비 요청 실패: ${it.message}") }
    }

    fun release() = runCatching { helper.removeListener(listener); helper.release() }
}
