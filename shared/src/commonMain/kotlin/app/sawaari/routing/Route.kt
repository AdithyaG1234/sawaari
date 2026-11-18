package app.sawaari.routing

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters as geoDistance

/** Maneuver types, using GraphHopper's instruction sign values. */
object Sign {
    const val U_TURN_UNKNOWN = -98
    const val U_TURN_LEFT = -8
    const val KEEP_LEFT = -7
    const val LEAVE_ROUNDABOUT = -6
    const val SHARP_LEFT = -3
    const val LEFT = -2
    const val SLIGHT_LEFT = -1
    const val CONTINUE = 0
    const val SLIGHT_RIGHT = 1
    const val RIGHT = 2
    const val SHARP_RIGHT = 3
    const val FINISH = 4
    const val VIA_REACHED = 5
    const val ROUNDABOUT = 6
    const val KEEP_RIGHT = 7
    const val U_TURN_RIGHT = 8
}

/**
 * One turn-by-turn instruction. [startIndex] is the index into [Route.points]
 * where the maneuver happens, [startDistance] the distance along the route to it.
 */
data class RouteStep(
    val text: String,
    val sign: Int,
    val streetName: String,
    val startIndex: Int,
    val startDistance: Double,
)

/** A computed route: geometry plus instructions. */
class Route(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val timeMillis: Long,
) {
    /** cumulative[i] = distance along the route from the start to points[i]. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + geoDistance(points[i - 1], points[i])
    }

    val geometryLength: Double get() = cumulative.lastOrNull() ?: 0.0

    companion object {
        /** Build steps from (text, sign, street, first point) tuples by locating each maneuver on the line. */
        fun build(
            points: List<GeoPoint>,
            rawSteps: List<RawStep>,
            distanceMeters: Double,
            timeMillis: Long,
        ): Route {
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + geoDistance(points[i - 1], points[i])
            var cursor = 0
            val steps = rawSteps.map { raw ->
                val idx = locate(points, raw.firstPoint, cursor)
                cursor = idx
                RouteStep(raw.text, raw.sign, raw.streetName, idx, cumulative.getOrElse(idx) { 0.0 })
            }
            return Route(points, steps, distanceMeters, timeMillis)
        }

        /** First index at or after [from] whose point matches [p]; nearest one if none matches exactly. */
        private fun locate(points: List<GeoPoint>, p: GeoPoint, from: Int): Int {
            var best = from.coerceAtMost(points.lastIndex.coerceAtLeast(0))
            var bestDist = Double.MAX_VALUE
            for (i in from until points.size) {
                val d = geoDistance(points[i], p)
                if (d < 0.5) return i
                if (d < bestDist) { bestDist = d; best = i }
            }
            return best
        }
    }
}

data class RawStep(val text: String, val sign: Int, val streetName: String, val firstPoint: GeoPoint)

class RoutingException(message: String) : Exception(message)
