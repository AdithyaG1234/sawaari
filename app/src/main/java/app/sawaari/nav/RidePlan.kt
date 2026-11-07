package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.routing.Route
import app.sawaari.routing.Sign

/**
 * A ride plan: an optional start (null means "from where I am"), stops in order, and the destination.
 * The leader can share it with the group so everyone rides the same route.
 */
data class RidePlan(
    val start: GeoPoint? = null,
    val stops: List<GeoPoint> = emptyList(),
    val end: GeoPoint? = null,
) {
    val isEmpty: Boolean get() = start == null && stops.isEmpty() && end == null

    /** Points to route through, starting at [start] or at [myPosition]. Null if there's no destination or start yet. */
    fun waypoints(myPosition: GeoPoint?): List<GeoPoint>? {
        val to = end ?: return null
        val from = start ?: myPosition ?: return null
        return listOf(from) + stops + to
    }

    fun withStop(p: GeoPoint, maxStops: Int = MAX_STOPS) = if (stops.size >= maxStops) this else copy(stops = stops + p)
    fun withoutStop(index: Int) = copy(stops = stops.filterIndexed { i, _ -> i != index })

    companion object {
        const val MAX_STOPS = 8

        /** How many stops of [route] the rider has passed once they are [along] metres into it. */
        fun stopsPassed(route: Route, along: Double): Int =
            route.steps.count { it.sign == Sign.VIA_REACHED && it.startDistance <= along + 30 }
    }
}
