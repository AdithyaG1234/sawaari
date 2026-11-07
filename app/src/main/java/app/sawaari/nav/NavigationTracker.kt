package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.bearingDegrees
import app.sawaari.geo.projectOntoSegment
import app.sawaari.routing.Route

/** Where the rider is relative to the route. */
data class NavProgress(
    /** Index into [Route.steps] of the next maneuver. */
    val nextStepIndex: Int,
    val distanceToNextStep: Double,
    val distanceRemaining: Double,
    /** How far the rider is from the route line, in metres. */
    val offRouteMeters: Double,
    val snapped: GeoPoint,
    val routeBearing: Double,
    val arrived: Boolean,
)

/**
 * Matches GPS fixes to the route and works out the next maneuver.
 * Pure Kotlin so it can be unit tested without a device.
 */
class NavigationTracker(val route: Route) {

    private var segment = -1

    fun update(position: GeoPoint): NavProgress {
        val pts = route.points
        if (pts.size < 2) {
            return NavProgress(route.steps.lastIndex.coerceAtLeast(0), 0.0, 0.0, 0.0, pts.firstOrNull() ?: position, 0.0, true)
        }
        // Search near the last matched segment so overlapping roads (out-and-back) don't make us jump.
        val range = if (segment < 0) 0 until pts.size - 1
        else (segment - 3).coerceAtLeast(0) until (segment + SEARCH_AHEAD).coerceAtMost(pts.size - 1)

        var bestSeg = range.first
        var best = projectOntoSegment(position, pts[bestSeg], pts[bestSeg + 1])
        for (i in range) {
            val p = projectOntoSegment(position, pts[i], pts[i + 1])
            if (p.distance < best.distance) { best = p; bestSeg = i }
        }
        // If we lost the route locally, fall back to a full search.
        if (segment >= 0 && best.distance > 150) {
            for (i in 0 until pts.size - 1) {
                val p = projectOntoSegment(position, pts[i], pts[i + 1])
                if (p.distance < best.distance) { best = p; bestSeg = i }
            }
        }
        segment = bestSeg

        val c = route.cumulative
        val along = c[bestSeg] + (c[bestSeg + 1] - c[bestSeg]) * best.fraction
        val total = route.geometryLength
        val remaining = (total - along).coerceAtLeast(0.0)

        val next = route.steps.indexOfFirst { it.startIndex > 0 && it.startDistance > along + 3 }
            .let { if (it < 0) route.steps.lastIndex else it }
        val toNext = ((route.steps.getOrNull(next)?.startDistance ?: total) - along).coerceAtLeast(0.0)

        return NavProgress(
            nextStepIndex = next,
            distanceToNextStep = toNext,
            distanceRemaining = remaining,
            offRouteMeters = best.distance,
            snapped = best.point,
            routeBearing = bearingDegrees(pts[bestSeg], pts[bestSeg + 1]),
            arrived = remaining < ARRIVAL_METERS && best.distance < 60,
        )
    }

    companion object {
        const val SEARCH_AHEAD = 80
        const val ARRIVAL_METERS = 25.0
    }
}
