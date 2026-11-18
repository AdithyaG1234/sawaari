package app.sawaari.routing

import app.sawaari.geo.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValhallaTest {
    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-6, "$a != $b")

    @Test
    fun decodesThePolylineExample() {
        // Google's documented example, read with 6 decimal places instead of 5.
        val pts = Valhalla.decodePolyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        near(pts[0].lat, 3.85); near(pts[0].lon, -12.02)
        near(pts[1].lat, 4.07); near(pts[1].lon, -12.095)
        near(pts[2].lat, 4.3252); near(pts[2].lon, -12.6453)
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val pts = listOf(GeoPoint(12.971599, 77.594566), GeoPoint(13.370154, 77.683534), GeoPoint(-33.8, 151.2))
        val back = Valhalla.decodePolyline6(Valhalla.encodePolyline6(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> near(a.lat, b.lat); near(a.lon, b.lon) }
    }

    @Test
    fun requestNamesEveryPointAndMotorcycle() {
        val body = Valhalla.request(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)))
        assertTrue(body.contains("\"costing\":\"motorcycle\""))
        assertTrue(body.contains("\"lat\":3.0"))
    }

    private fun leg(points: List<GeoPoint>, maneuvers: String) =
        """{"shape":"${Valhalla.encodePolyline6(points).replace("\\", "\\\\")}","maneuvers":[$maneuvers]}"""

    @Test
    fun twoLegsBecomeOneRouteWithAStop() {
        val a = GeoPoint(12.97, 77.59); val b = GeoPoint(12.98, 77.59); val c = GeoPoint(12.98, 77.60)
        val d = GeoPoint(12.99, 77.60)
        val json = """{"trip":{"legs":[
            ${leg(listOf(a, b, c), """{"type":1,"instruction":"Drive north on MG Road.","street_names":["MG Road"],"begin_shape_index":0},
                {"type":10,"instruction":"Turn right onto Brigade Road.","street_names":["Brigade Road"],"begin_shape_index":1},
                {"type":4,"instruction":"You have arrived at your destination.","begin_shape_index":2}""")},
            ${leg(listOf(c, d), """{"type":2,"instruction":"Drive north.","begin_shape_index":0},
                {"type":6,"instruction":"Your destination is on the left.","begin_shape_index":1}""")}
        ],"summary":{"length":3.5,"time":420}}}"""
        val r = Valhalla.parse(json)
        assertEquals(4, r.points.size)
        assertEquals(3500.0, r.distanceMeters)
        assertEquals(420_000L, r.timeMillis)
        assertEquals(listOf(Sign.CONTINUE, Sign.RIGHT, Sign.VIA_REACHED, Sign.FINISH), r.steps.map { it.sign })
        assertEquals(listOf(0, 1, 2, 3), r.steps.map { it.startIndex })
        assertEquals("Turn right onto Brigade Road", r.steps[1].text)
        assertEquals("Brigade Road", r.steps[1].streetName)
        assertEquals("You've reached stop 1", r.steps[2].text)
        assertTrue(r.steps[3].startDistance > r.steps[2].startDistance)
    }

    @Test
    fun errorsBecomeRiderFriendlyMessages() {
        val e = assertFailsWith<RoutingException> {
            Valhalla.parse("""{"error_code":171,"error":"No suitable edges near location","status_code":400}""")
        }
        assertEquals("No road found near the start, a stop or the destination.", e.message)
        assertFailsWith<RoutingException> { Valhalla.parse("not json") }
        assertFailsWith<RoutingException> { Valhalla.parse("""{"trip":{"legs":[]}}""") }
    }

    @Test
    fun manoeuvreTypesMapToSigns() {
        assertEquals(Sign.LEFT, Valhalla.sign(15, true))
        assertEquals(Sign.SLIGHT_LEFT, Valhalla.sign(16, true))
        assertEquals(Sign.ROUNDABOUT, Valhalla.sign(26, true))
        assertEquals(Sign.KEEP_RIGHT, Valhalla.sign(20, true))
        assertEquals(Sign.CONTINUE, Valhalla.sign(8, true))
    }
}
