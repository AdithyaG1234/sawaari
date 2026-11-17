package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.routing.RawStep
import app.sawaari.routing.Route
import app.sawaari.routing.Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTrackerTest {

    // An L-shaped route near Bengaluru: ~1.1 km north, then ~1.1 km east.
    private val a = GeoPoint(12.9700, 77.5900)
    private val b = GeoPoint(12.9800, 77.5900)
    private val c = GeoPoint(12.9800, 77.6000)
    private val points = listOf(a, GeoPoint(12.9750, 77.5900), b, GeoPoint(12.9800, 77.5950), c)
    private val route = Route.build(
        points,
        listOf(
            RawStep("Continue onto MG Road", Sign.CONTINUE, "MG Road", a),
            RawStep("Turn right onto Church Street", Sign.RIGHT, "Church Street", b),
            RawStep("Arrive at destination", Sign.FINISH, "", c),
        ),
        distanceMeters = 2200.0,
        timeMillis = 300_000,
    )

    @Test fun stepsAreLocatedOnTheLine() {
        assertEquals(listOf(0, 2, 4), route.steps.map { it.startIndex })
        assertEquals(distanceMeters(a, b), route.steps[1].startDistance, 1.0)
    }

    @Test fun nextManeuverIsTheRightTurn() {
        val tracker = NavigationTracker(route)
        val p = tracker.update(GeoPoint(12.9750, 77.59001)) // halfway up the first leg, ~1 m off
        assertEquals(1, p.nextStepIndex)
        assertEquals(distanceMeters(a, b) / 2, p.distanceToNextStep, 5.0)
        assertTrue(p.offRouteMeters < 3)
        assertFalse(p.arrived)
    }

    @Test fun afterTheTurnNextIsArrival() {
        val tracker = NavigationTracker(route)
        tracker.update(GeoPoint(12.9790, 77.5900))
        val p = tracker.update(GeoPoint(12.9800, 77.5960))
        assertEquals(2, p.nextStepIndex)
        assertEquals(distanceMeters(GeoPoint(12.98, 77.596), c), p.distanceToNextStep, 5.0)
    }

    @Test fun detectsOffRoute() {
        val p = NavigationTracker(route).update(GeoPoint(12.9750, 77.5950)) // ~540 m east of the first leg
        assertTrue(p.offRouteMeters > 400)
    }

    @Test fun detectsArrival() {
        val tracker = NavigationTracker(route)
        tracker.update(GeoPoint(12.9800, 77.5990))
        assertTrue(tracker.update(GeoPoint(12.98, 77.59995)).arrived)
    }

    @Test fun voicePromptsSpeakEachThresholdOnce() {
        val v = VoicePrompts()
        assertNull(v.check(1, 1500.0, "Turn right"))
        assertEquals("In 1 kilometre, turn right", v.check(1, 990.0, "Turn right"))
        assertNull(v.check(1, 900.0, "Turn right"))
        assertEquals("In 300 metres, turn right", v.check(1, 290.0, "Turn right"))
        assertEquals("Turn right", v.check(1, 35.0, "Turn right"))
        assertNull(v.check(1, 20.0, "Turn right"))
        // Starting already close to a turn gives only the nearest prompt.
        assertEquals("In 150 metres, keep left", v.check(2, 160.0, "Keep left"))
    }
}
