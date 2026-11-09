package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.routing.pointsOutsidePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingCoverageTest {
    private val gubbi = GeoPoint(13.3161, 76.9426)
    private val gubbiStation = GeoPoint(13.312, 76.94)
    private val northKarnataka = GeoPoint(15.15, 74.60) // a same-named temple far outside the pack

    @Test fun allInsideOnePackIsFine() {
        assertNull(pointsOutsidePacks(listOf(gubbi, gubbiStation, gubbi)))
    }

    @Test fun namesTheStopOutsideTheBestPack() {
        val (pack, outside) = pointsOutsidePacks(listOf(gubbi, northKarnataka, gubbiStation, gubbi))!!
        assertEquals("bengaluru-coorg", pack.id)
        assertEquals(listOf(1), outside)
    }

    @Test fun nothingCoveredGivesNull() {
        assertNull(pointsOutsidePacks(listOf(GeoPoint(28.6, 77.2), GeoPoint(26.9, 75.8))))
    }
}
