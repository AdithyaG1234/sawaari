package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.routing.bestPackFor
import app.sawaari.routing.pointsOutsidePacks
import app.sawaari.routing.routingPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingCoverageTest {
    private val gubbi = GeoPoint(13.3161, 76.9426)
    private val gubbiStation = GeoPoint(13.312, 76.94)
    private val delhi = GeoPoint(28.61, 77.21) // a same-named place far outside every pack that covers Gubbi

    @Test fun allInsideOnePackIsFine() {
        assertNull(pointsOutsidePacks(listOf(gubbi, gubbiStation, gubbi)))
    }

    @Test fun namesTheStopOutsideTheBestPack() {
        val (pack, outside) = pointsOutsidePacks(listOf(gubbi, delhi, gubbiStation, gubbi))!!
        assertEquals("bengaluru-coorg", pack.id)
        assertEquals(listOf(1), outside)
    }

    @Test fun nothingCoveredGivesNull() {
        assertNull(pointsOutsidePacks(listOf(GeoPoint(22.57, 88.36), GeoPoint(20.30, 85.82)))) // Kolkata, Bhubaneswar
    }

    // The Goa trial route: Bengaluru, Tumakuru toll, Chitradurga, Haveri, Dandeli, Castle Rock, Vagator, Dharwad, Chitradurga, Bengaluru.
    private val goaTrip = listOf(
        GeoPoint(12.9716, 77.5946), GeoPoint(13.0900, 77.4500), GeoPoint(14.2251, 76.3980), GeoPoint(14.7951, 75.3991),
        GeoPoint(15.2361, 74.6173), GeoPoint(15.3990, 74.3320), GeoPoint(15.6010, 73.7360), GeoPoint(15.4589, 75.0078),
        GeoPoint(14.2251, 76.3980), GeoPoint(12.9716, 77.5946),
    )

    @Test fun goaTripFitsOneWholeTripPack() {
        assertNull("no stop should be marked outside", pointsOutsidePacks(goaTrip))
        assertEquals("bengaluru-goa", bestPackFor(goaTrip)!!.id)
    }

    @Test fun wholeStatePackCoversTheGoaTripToo() {
        val state = routingPacks.single { it.id == "karnataka-goa" }
        assertTrue(state.containsAll(goaTrip))
        // Major Karnataka and Goa towns.
        listOf(GeoPoint(17.91, 77.52), GeoPoint(12.87, 74.84), GeoPoint(12.30, 76.64), GeoPoint(15.49, 73.83), GeoPoint(15.85, 74.50))
            .forEach { assertTrue("$it", state.contains(it.lat, it.lon)) }
    }

    @Test fun aDownloadedPackWinsOverASmallerOneNotDownloaded() {
        assertEquals("karnataka-goa", bestPackFor(goaTrip, downloaded = setOf("karnataka-goa"))!!.id)
        assertEquals("bengaluru-goa", bestPackFor(goaTrip, downloaded = setOf("karnataka-goa", "bengaluru-goa"))!!.id)
    }

    @Test fun localRideStillUsesTheSmallAreaPack() {
        assertEquals("bengaluru-coorg", bestPackFor(listOf(gubbi, gubbiStation), downloaded = setOf("bengaluru-coorg", "karnataka-goa"))!!.id)
        assertEquals("bengaluru-coorg", bestPackFor(listOf(gubbi, gubbiStation))!!.id)
    }

    @Test fun noPackForATripAcrossIndia() {
        assertNull(bestPackFor(listOf(GeoPoint(12.97, 77.59), GeoPoint(28.61, 77.21))))
    }
}
