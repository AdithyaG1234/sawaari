package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.places.PlaceCategory
import app.sawaari.places.PlaceMatcher
import app.sawaari.routing.RawStep
import app.sawaari.routing.Route
import app.sawaari.routing.Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMatcherTest {
    @Test fun parsesPlacesFile() {
        val p = PlaceMatcher.parse("n42\tfood\t12.90000\t77.50000\tKamat\t+91 80 1234")!!
        assertEquals(PlaceCategory.FOOD, p.category)
        assertEquals("Kamat", p.displayName)
        assertEquals("Fuel station", PlaceMatcher.parse("w7\tfuel\t12.9\t77.5\t\t")!!.displayName)
        assertNull(PlaceMatcher.parse("n1\tbank\t12.9\t77.5\tX\t"))
        assertNull(PlaceMatcher.parse("garbage"))
    }

    @Test fun findsPlacesNearTheRouteInOrder() {
        // A 20 km route due north along longitude 77.5.
        val pts = (0..200).map { GeoPoint(12.80 + it * 0.0009, 77.5) }
        val route = Route.build(pts, listOf(RawStep("Go", Sign.CONTINUE, "", pts.first())), 20_000.0, 1_000_000L)
        val places = listOf(
            PlaceMatcher.parse("n1\tfood\t12.95\t77.503\tNear late\t")!!,   // ~300 m off, ~16.7 km along
            PlaceMatcher.parse("n2\tfood\t12.82\t77.502\tNear early\t")!!,  // ~200 m off, ~2.2 km along
            PlaceMatcher.parse("n3\tfood\t12.90\t77.53\tFar away\t")!!,     // ~3.3 km off
        )
        val found = PlaceMatcher.alongRoute(route, places)
        assertEquals(listOf("n2", "n1"), found.map { it.place.id })
        assertTrue(found[0].offRouteMeters < 400)
        assertTrue(found[0].alongMeters < found[1].alongMeters)
    }
}
