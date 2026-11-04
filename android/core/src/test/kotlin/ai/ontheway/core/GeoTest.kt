package ai.ontheway.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    // a straight 10 km road heading north
    private val road = Polyline((0..10).map { LatLon(37.0 + it * 0.009, 127.0) })

    @Test fun distanceOfOneDegreeLatitude() = assertEquals(111_195.0, distM(LatLon(37.0, 127.0), LatLon(38.0, 127.0)), 1.0)

    @Test fun pointAlongRoute() {
        assertEquals(37.0, road.at(-5.0).lat, 1e-9)
        assertEquals(37.0 + 0.0045, road.at(road.lengthM / 20).lat, 1e-6)
        assertEquals(37.09, road.at(1e9).lat, 1e-9)
    }

    @Test fun alongFindsNearestPoint() = assertEquals(5000.0, road.along(LatLon(37.045, 127.001)), 150.0)

    @Test fun corridorKeepsNearbyOfKindNearestFirst() {
        fun at(id: String, kind: Kind, lat: Double, lon: Double) = Place(id, kind, id, "", LatLon(lat, lon))
        val places = listOf(
            at("roadside", Kind.FUEL, 37.05, 127.0005), // ~45 m off
            at("town", Kind.FUEL, 37.03, 127.01), // ~890 m off
            at("far", Kind.FUEL, 37.03, 127.05), // ~4.4 km off
            at("dt", Kind.FOOD, 37.05, 127.0),
            at("behind", Kind.FUEL, 36.95, 127.0),
        )
        val got = corridor(road, 0.0, road.lengthM, places, Kind.FUEL, 1500.0, 10)
        assertEquals(listOf("roadside", "town"), got.map { it.place.id })
        assertTrue(got[0].alongM in 5000.0..6000.0)
    }

    @Test fun detourLegIsTheWayOffTheRoadAndBack() {
        // a station 500 m east of the 5 km mark: the via route turns off, reaches it and comes back
        val station = LatLon(37.045, 127.00565)
        val out = (1..5).map { LatLon(37.045, 127.0 + it * 0.00113) }
        val via = Polyline(road.pts.take(6) + out + out.reversed().drop(1) + road.pts.drop(5))
        val leg = detourLeg(road, via, station)
        assertTrue(leg.containsAll(out), "the whole spur is in the leg")
        assertTrue(leg.size <= out.size * 2 + 2, "the road before and after is not")
        assertEquals(emptyList(), detourLeg(road, road, LatLon(37.045, 127.0)), "a stop on the road has no leg")
    }

    @Test fun parsesPlacesCsv() {
        val p = parsePlaces(
            "kind,id,lat,lon,name,brand,self,gasoline,diesel,src\n" +
                "FUEL,A0000035,37.5,127.1,평창주유소,HD현대오일뱅크,Y,1999,1994,opinet\n" +
                "FUEL,n7,37.6,127.2,주유소,,,,,osm\n",
        )
        assertEquals(Place("A0000035", Kind.FUEL, "평창주유소", "HD현대오일뱅크", LatLon(37.5, 127.1), true, 1999, 1994, true), p[0])
        assertEquals(1994, p[0].price(Fuel.DIESEL))
        assertEquals(null, p[1].price(Fuel.GASOLINE))
        assertEquals(false, p[1].verified)
    }
}
