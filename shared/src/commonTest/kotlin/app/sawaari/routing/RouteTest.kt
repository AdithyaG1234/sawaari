package app.sawaari.routing

import app.sawaari.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTest {
    private val points = listOf(GeoPoint(15.0, 74.0), GeoPoint(15.0, 74.01), GeoPoint(15.01, 74.01), GeoPoint(15.02, 74.01))

    @Test fun stepsAreFoundOnTheLineInOrder() {
        val route = Route.build(
            points,
            listOf(
                RawStep("Head east", Sign.CONTINUE, "", points[0]),
                RawStep("Turn left", Sign.LEFT, "NH66", points[1]),
                RawStep("Arrive", Sign.FINISH, "", GeoPoint(15.02001, 74.01)), // a little off the last point
            ),
            distanceMeters = 3_300.0, timeMillis = 300_000,
        )
        assertEquals(listOf(0, 1, 3), route.steps.map { it.startIndex })
        assertEquals(0.0, route.steps[0].startDistance, 0.0)
        assertEquals(route.cumulative[1], route.steps[1].startDistance, 0.0)
        assertTrue(route.steps.zipWithNext().all { (a, b) -> a.startDistance <= b.startDistance })
    }

    @Test fun cumulativeDistanceAddsUp() {
        val route = Route(points, emptyList(), 0.0, 0)
        assertEquals(0.0, route.cumulative[0], 0.0)
        assertTrue(route.cumulative.toList().zipWithNext().all { (a, b) -> b > a })
        // About 1.07 km east, then 2 x 1.11 km north.
        assertTrue(route.geometryLength in 3_200.0..3_400.0, "was ${route.geometryLength}")
        assertEquals(0.0, Route(emptyList(), emptyList(), 0.0, 0).geometryLength, 0.0)
    }
}
