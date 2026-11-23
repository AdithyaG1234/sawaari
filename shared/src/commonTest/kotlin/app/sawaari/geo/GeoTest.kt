package app.sawaari.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
    private val panaji = GeoPoint(15.4909, 73.8278)
    private val margao = GeoPoint(15.2832, 73.9862)

    @Test fun distanceBetweenTownsIsRight() {
        // Panaji to Margao is about 28 km in a straight line.
        val d = distanceMeters(panaji, margao)
        assertTrue(d in 27_000.0..29_000.0, "was $d")
        assertEquals(d, distanceMeters(margao, panaji), 0.001)
        assertEquals(0.0, distanceMeters(panaji, panaji), 0.0)
    }

    @Test fun bearingPointsTheRightWay() {
        val north = bearingDegrees(GeoPoint(15.0, 74.0), GeoPoint(15.1, 74.0))
        val east = bearingDegrees(GeoPoint(15.0, 74.0), GeoPoint(15.0, 74.1))
        val south = bearingDegrees(GeoPoint(15.1, 74.0), GeoPoint(15.0, 74.0))
        val west = bearingDegrees(GeoPoint(15.0, 74.1), GeoPoint(15.0, 74.0))
        assertTrue(north < 0.5 || north > 359.5, "north was $north")
        assertTrue(abs(east - 90) < 0.5, "east was $east")
        assertTrue(abs(south - 180) < 0.5, "south was $south")
        assertTrue(abs(west - 270) < 0.5, "west was $west")
    }

    @Test fun projectionLandsOnTheRoad() {
        val a = GeoPoint(15.0, 74.0)
        val b = GeoPoint(15.0, 74.01)
        val mid = projectOntoSegment(GeoPoint(15.0005, 74.005), a, b)
        assertEquals(0.5, mid.fraction, 0.01)
        assertTrue(mid.distance in 50.0..60.0, "was ${mid.distance}") // 0.0005 deg of latitude is about 55 m
        // Past the end it clamps to the end point.
        assertEquals(1.0, projectOntoSegment(GeoPoint(15.0, 74.02), a, b).fraction, 0.0)
        // A zero-length segment doesn't divide by zero.
        assertEquals(0.0, projectOntoSegment(GeoPoint(15.1, 74.0), a, a).fraction, 0.0)
    }

    @Test fun distanceToRouteLine() {
        val line = listOf(GeoPoint(15.0, 74.0), GeoPoint(15.0, 74.01), GeoPoint(15.01, 74.01))
        assertTrue(distanceToPolyline(GeoPoint(15.0, 74.005), line)!! < 1.0)
        assertTrue(distanceToPolyline(GeoPoint(15.005, 74.0105), line)!! in 40.0..70.0)
        assertNull(distanceToPolyline(GeoPoint(15.0, 74.0), listOf(GeoPoint(15.0, 74.0))))
    }
}
