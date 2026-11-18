package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.routing.RawStep
import app.sawaari.routing.Route
import app.sawaari.routing.RoutingException
import app.sawaari.routing.Sign
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavSessionTest {
    private val home = GeoPoint(12.9700, 77.5900)
    private val dest = GeoPoint(12.9900, 77.5900)

    /** A straight road north from [from] to the last point, with a finish step. */
    private fun straight(points: List<GeoPoint>): Route {
        val line = (0..20).map { i -> GeoPoint(points.first().lat + (points.last().lat - points.first().lat) * i / 20, 77.59) }
        return Route.build(
            line,
            listOf(RawStep("Head north", Sign.CONTINUE, "", line.first()), RawStep("Arrive", Sign.FINISH, "", line.last())),
            2200.0, 300_000L,
        )
    }

    private fun TestScope.session(spoken: MutableList<String>, fail: Boolean = false) =
        NavSession({ pts -> if (fail) throw RoutingException("No route found between these places.") else straight(pts) }, this) { spoken += it }

    @Test
    fun plansNavigatesAndArrives() = runTest {
        val spoken = mutableListOf<String>()
        val nav = session(spoken)
        nav.onLocation(home, 5.0)
        nav.chooseDestination(dest)
        nav.planRoute()
        advanceUntilIdle()
        assertNotNull(nav.route.value)
        nav.start()
        assertTrue(nav.navigating.value)
        assertEquals("Head north", spoken.first())
        nav.onLocation(GeoPoint(12.9800, 77.5900), 5.0)
        assertTrue(nav.progress.value!!.distanceRemaining in 1000.0..1300.0)
        nav.onLocation(dest, 5.0)
        assertFalse(nav.navigating.value)
        assertTrue(nav.plan.value.isEmpty)
        assertEquals("You have arrived at your destination.", spoken.last())
    }

    @Test
    fun routingErrorsAreShown() = runTest {
        val nav = session(mutableListOf(), fail = true)
        nav.onLocation(home, 5.0)
        nav.chooseDestination(dest)
        nav.planRoute()
        advanceUntilIdle()
        assertNull(nav.route.value)
        assertEquals("No route found between these places.", nav.message.value)
        assertFalse(nav.busy.value)
    }

    @Test
    fun reroutesAfterLeavingTheRoute() = runTest {
        val spoken = mutableListOf<String>()
        val nav = session(spoken)
        var reroutes = 0
        nav.onReroute = { reroutes++ }
        nav.onLocation(home, 5.0)
        nav.chooseDestination(dest)
        nav.planRoute(); advanceUntilIdle()
        nav.start()
        repeat(3) { nav.onLocation(GeoPoint(12.975, 77.60), 5.0) } // about 1 km east of the road
        advanceUntilIdle()
        assertEquals(1, reroutes)
        assertTrue("Recalculating." in spoken)
    }

    @Test
    fun editingThePlanDropsTheRoute() = runTest {
        val nav = session(mutableListOf())
        nav.onLocation(home, 5.0)
        nav.chooseDestination(dest)
        nav.planRoute(); advanceUntilIdle()
        nav.addStop(GeoPoint(12.98, 77.59))
        assertNull(nav.route.value)
        assertEquals(1, nav.plan.value.stops.size)
        nav.makeRoundTrip(true)
        assertTrue(nav.isRoundTrip)
        assertEquals(home, nav.plan.value.end)
    }
}
