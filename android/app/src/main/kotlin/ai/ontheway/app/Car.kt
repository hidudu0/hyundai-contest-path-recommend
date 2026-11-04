package ai.ontheway.app

import ai.ontheway.core.Fuel
import ai.ontheway.core.Kind
import ai.ontheway.core.Params
import ai.ontheway.core.Place
import ai.ontheway.core.Route
import ai.ontheway.core.LatLon
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.max
import kotlin.math.min

private const val TAG = "OnTheWay"
private const val ASSUMED_FUEL_PCT = 25

/**
 * One reading of everything the recommender needs. [toDestM] is the remaining route length.
 * [fuelAssumed]: the car has no fuel signal (the Pleos emulator is an EV), so fuel is a fixed stand-in.
 */
data class CarState(
    val fuelL: Double,
    val tankL: Double,
    val kmPerL: Double,
    val pos: LatLon?,
    val dest: Dest?,
    val toDestM: Double?,
    val fuelAssumed: Boolean = false,
    val fuel: Fuel = Fuel.GASOLINE,
)

interface CarSource {
    fun stop()
}

/**
 * 시연: drives a real road route [stepM] metres per second (≈ 1 km/s), burning fuel at [kmPerL].
 * Reaching the waypoint ends the stop: a fuel station fills the tank to 90 %.
 */
class DemoDrive(
    private val route: Route,
    private val dest: Dest,
    startPct: Double,
    private val tankL: Double,
    private val kmPerL: Double,
    private val onState: (CarState) -> Unit,
    private val onArrive: (Place) -> Unit,
    private val stepM: Double = 1_000.0,
) : CarSource {
    private val handler = Handler(Looper.getMainLooper())
    private var along = 0.0
    private var fuelL = tankL * startPct / 100
    private var stop: Pair<Place, Double>? = null

    fun waypoint(p: Place) {
        stop = p to route.line.along(p.pos, 200.0)
    }

    private val tick = object : Runnable {
        override fun run() {
            along = min(along + stepM, route.line.lengthM)
            fuelL = max(0.0, fuelL - stepM / 1000 / kmPerL)
            stop?.let { (place, at) ->
                if (along >= at) {
                    stop = null
                    if (place.kind == Kind.FUEL) fuelL = tankL * 0.9
                    onArrive(place)
                }
            }
            onState(CarState(fuelL, tankL, kmPerL, route.line.at(along), dest, route.line.lengthM - along))
            if (along < route.line.lengthM) handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        handler.post(tick)
    }

    override fun stop() = handler.removeCallbacksAndMessages(null)
}

/**
 * 실차: fuel % and fuel economy from the Pleos Vehicle SDK, tank size from AAOS INFO_FUEL_CAPACITY,
 * position, destination and remaining distance from the Pleos navigation.
 */
class PleosCar(context: Context, private val vehicle: Vehicle, private val navi: Navi, private val p: Params, private val onState: (CarState) -> Unit) : CarSource {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pct: Int? = null
    @Volatile private var assumed = false
    @Volatile private var kmPerL = p.kmPerL
    @Volatile private var tankL = p.tankL
    @Volatile private var fuel = Fuel.GASOLINE

    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) {
            pct = value
            assumed = false
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "fuel percent", e)
        }
    }
    private val economyListener = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(value: Float?) {
            if (value != null && value > 0f) kmPerL = 100.0 / value // SDK reports L/100 km
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "fuel economy", e)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            navi.poll()
            pct?.let { onState(CarState(tankL * it / 100, tankL, kmPerL, navi.pos, navi.dest, navi.remainM, assumed, fuel)) }
            handler.postDelayed(this, 2000)
        }
    }

    init {
        runCatching {
            val fuel = vehicle.getFuel()
            fuel.registerFuelPercent(fuelListener)
            fuel.registerCurrentFuelEconomy(economyListener)
            fuel.getFuelPercent({ pct = it }, {
                Log.w(TAG, "fuel percent get", it)
                if (pct == null) {
                    pct = ASSUMED_FUEL_PCT
                    assumed = true
                    tankL = p.tankL // an EV reports a capacity that is not a fuel tank
                }
            })
        }.onFailure { Log.w(TAG, "Pleos fuel", it) }
        runCatching {
            val car = Car.createCar(context)
            val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val mL = mgr.getProperty<Float>(VehiclePropertyIds.INFO_FUEL_CAPACITY, 0).value
            if (mL != null && mL > 0f && !assumed) tankL = mL / 1000.0
            // FuelType: 3 DIESEL_1, 4 DIESEL_2, 5 BIODIESEL → compare diesel prices
            val types = runCatching { mgr.getProperty<Array<Int>>(VehiclePropertyIds.INFO_FUEL_TYPE, 0).value }.getOrNull()
            if (types != null && types.any { it in 3..5 }) fuel = Fuel.DIESEL
            car.disconnect()
        }.onFailure { Log.w(TAG, "fuel capacity", it) }
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getFuel().unregisterFuelPercent(fuelListener)
            vehicle.getFuel().unregisterCurrentFuelEconomy(economyListener)
        }
    }
}
