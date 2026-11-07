package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.group.RideGroup
import app.sawaari.group.Rider
import app.sawaari.group.Role
import app.sawaari.routing.RawStep
import app.sawaari.routing.Route
import app.sawaari.routing.Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidePlanTest {
    private val a = GeoPoint(12.90, 77.50)
    private val b = GeoPoint(12.95, 77.55)
    private val c = GeoPoint(13.00, 77.60)

    @Test fun waypointsStartFromMeUnlessAStartIsSet() {
        val plan = RidePlan(stops = listOf(b), end = c)
        assertEquals(listOf(a, b, c), plan.waypoints(a))
        assertNull(plan.waypoints(null))
        assertEquals(listOf(b, b, c), plan.copy(start = b).waypoints(a))
        assertNull(RidePlan(stops = listOf(b)).waypoints(a))
    }

    @Test fun stopsAreCappedAndRemovable() {
        var plan = RidePlan()
        repeat(10) { plan = plan.withStop(GeoPoint(12.0 + it, 77.0)) }
        assertEquals(RidePlan.MAX_STOPS, plan.stops.size)
        assertEquals(12.0 + 1, plan.withoutStop(0).stops.first().lat, 1e-9)
    }

    @Test fun countsStopsPassed() {
        val pts = listOf(a, b, c)
        val route = Route.build(
            pts,
            listOf(
                RawStep("Continue", Sign.CONTINUE, "", a),
                RawStep("Waypoint 1", Sign.VIA_REACHED, "", b),
                RawStep("Arrive", Sign.FINISH, "", c),
            ),
            1000.0, 1000L,
        )
        val atB = route.steps[1].startDistance
        assertEquals(0, RidePlan.stopsPassed(route, atB - 500))
        assertEquals(1, RidePlan.stopsPassed(route, atB - 10))
    }

    @Test fun leaderInTrouble() {
        val now = 100 * 60_000L
        fun group(l: Rider?) = RideGroup("ABCDEF", "Ride", "L", listOfNotNull(l, Rider("me", "Me", Role.RIDER, null, null, 0f, 0)))
        val ok = Rider("L", "Adi", Role.LEADER, 12.0, 77.0, 30f, now - 60_000L)
        assertFalse(group(ok).leaderInTrouble(now))
        assertTrue(group(ok.copy(updatedAt = now - 11 * 60_000L)).leaderInTrouble(now))
        assertTrue(group(ok.copy(sosAt = now - 5 * 60_000L)).leaderInTrouble(now))
        assertTrue(group(null).leaderInTrouble(now))
    }
}
