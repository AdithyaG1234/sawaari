package app.sawaari.offline

import app.sawaari.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteCorridorTest {
    // The Goa trial: Bengaluru, Tumakuru toll, Chitradurga, Haveri, Dandeli, Castle Rock, Vagator, Dharwad, Chitradurga, Bengaluru.
    private val goaTrip = listOf(
        GeoPoint(12.9716, 77.5946), GeoPoint(13.0900, 77.4500), GeoPoint(14.2251, 76.3980), GeoPoint(14.7951, 75.3991),
        GeoPoint(15.2361, 74.6173), GeoPoint(15.3990, 74.3320), GeoPoint(15.6010, 73.7360), GeoPoint(15.4589, 75.0078),
        GeoPoint(14.2251, 76.3980), GeoPoint(12.9716, 77.5946),
    )

    /** Fills in points every ~1 km, like a real route line. */
    private fun dense(points: List<GeoPoint>): List<GeoPoint> = points.zipWithNext { a, b ->
        (0 until 100).map { i -> val t = i / 100.0; GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t) }
    }.flatten() + points.last()

    @Test fun coversEveryPointOfTheRouteAndTheRoadside() {
        val line = dense(goaTrip)
        val boxes = RouteCorridor.boxes(line)
        line.forEach { assertTrue(RouteCorridor.covers(boxes, it), "not covered: $it") }
        // 3 km off the road (inside the 4 km buffer) is still on the saved map.
        line.forEach { assertTrue(RouteCorridor.covers(boxes, GeoPoint(it.lat + 0.027, it.lon)), "roadside not covered near $it") }
    }

    @Test fun leavesOutPlacesFarFromTheRoute() {
        val boxes = RouteCorridor.boxes(dense(goaTrip))
        assertFalse(RouteCorridor.covers(boxes, GeoPoint(12.87, 74.84)), "Mangaluru is far off this route")
        assertFalse(RouteCorridor.covers(boxes, GeoPoint(17.38, 78.48)), "Hyderabad is far off this route")
    }

    @Test fun wholeTripMapIsPhoneSized() {
        val boxes = RouteCorridor.boxes(dense(goaTrip))
        val mb = RouteCorridor.estimateBytes(boxes) / 1_000_000
        assertTrue(mb in 20..400, "Goa trip map estimate $mb MB")
        // Far smaller than downloading the whole box the trip spans.
        val box = GeoBounds(north = 15.7, east = 77.7, south = 12.9, west = 73.7)
        assertTrue(RouteCorridor.tileCount(boxes) * 4 < RouteCorridor.tileCount(listOf(box)))
    }

    @Test fun outAndBackOnTheSameRoadIsNotCountedTwice() {
        val oneWay = dense(listOf(GeoPoint(12.97, 77.59), GeoPoint(14.22, 76.40)))
        val there = RouteCorridor.tileCount(RouteCorridor.boxes(oneWay))
        val roundTrip = RouteCorridor.tileCount(RouteCorridor.boxes(oneWay + oneWay.reversed()))
        assertEquals(there, roundTrip)
    }

    @Test fun mergesCellsIntoFewRectangles() {
        val boxes = RouteCorridor.boxes(dense(goaTrip))
        assertTrue(boxes.size < 1500, "${boxes.size} rectangles")
        boxes.forEach { assertTrue(it.west < it.east && it.south < it.north) }
    }

    @Test fun emptyRouteGivesNothing() {
        assertTrue(RouteCorridor.boxes(emptyList()).isEmpty())
        assertEquals(0L, RouteCorridor.tileCount(emptyList()))
    }

    @Test fun tileCountMatchesKnownZoom() {
        // A 1x1 degree box near Goa at zoom 8 spans about 1-2 tiles each way.
        val n = RouteCorridor.tileCount(listOf(GeoBounds(16.0, 74.5, 15.0, 73.5)), 8, 8)
        assertTrue(n in 1..9, "$n")
    }
}
