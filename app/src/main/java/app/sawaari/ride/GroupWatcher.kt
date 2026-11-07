package app.sawaari.ride

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.geo.distanceToPolyline
import app.sawaari.group.Rider
import app.sawaari.group.Role
import app.sawaari.nav.formatDistance

/** One alert about another rider, e.g. "Ravi fell 2.3 km behind". [urgent] alerts use the SOS channel. */
data class RideAlert(val key: String, val title: String, val text: String, val urgent: Boolean = false)

/**
 * Watches the group and raises alerts. Everyone hears about:
 *  - a break: a rider tapped "Taking a break"
 *  - stopped: a rider who was moving hasn't moved for [stoppedMs] (and didn't say it's a break);
 *    with a ride plan, stops at planned places are expected and others are called "unplanned"
 *  - slow traffic: a rider crawling at 5-15 km/h for [slowMs]
 *  - a missed turn: a rider's navigation rerouted
 *  - off the planned route: a rider more than [offRouteMeters] from the group's route (needs [route])
 *  - trouble with the leader or sweep: no signal for [silentMs]
 * The leader and sweep also hear about no signal from any rider, the leader about riders more
 * than [behindMeters] behind, and a rider about being that far behind the leader themselves.
 * Each alert fires once and re-arms when the condition clears. Pure Kotlin for unit tests.
 */
class GroupWatcher(
    private val behindMeters: Double = 2_000.0,
    private val stoppedMs: Long = 5 * 60_000L,
    private val silentMs: Long = 5 * 60_000L,
    private val breakNewMs: Long = 30 * 60_000L,
    private val slowMs: Long = 4 * 60_000L,
    private val offRouteMeters: Double = 500.0,
    private val plannedStopMeters: Double = 300.0,
) {
    private val lastMovingAt = mutableMapOf<String, Long>()
    private val slowSince = mutableMapOf<String, Long>()
    private val active = mutableSetOf<String>()

    /**
     * [planned] are the places the group expects to stop (start, stops, destination, meeting point).
     * [route] is the group's planned route line, when this phone is navigating it.
     */
    fun check(
        riders: List<Rider>,
        myUid: String?,
        me: GeoPoint?,
        now: Long,
        planned: List<GeoPoint> = emptyList(),
        route: List<GeoPoint>? = null,
    ): List<RideAlert> {
        val myRole = riders.firstOrNull { it.uid == myUid }?.role ?: return emptyList()
        val iLead = myRole == Role.LEADER || myRole == Role.SWEEP
        val out = mutableListOf<RideAlert>()
        val stillTrue = mutableSetOf<String>()

        fun raise(key: String, alert: () -> RideAlert) {
            stillTrue += key
            if (key !in active) out += alert()
        }

        for (r in riders) {
            if (r.uid == myUid) continue
            val who = when (r.role) { Role.LEADER -> "Leader ${r.name}"; Role.SWEEP -> "Sweep ${r.name}"; else -> r.name }
            val keyRole = r.role == Role.LEADER || r.role == Role.SWEEP
            val away = if (me != null && r.lat != null && r.lon != null) " ${formatDistance(distanceMeters(me, GeoPoint(r.lat, r.lon)))} from you." else ""

            // Break, announced by the rider. Old breaks (e.g. from before I opened the app) don't alert.
            if (r.breakAt > 0) {
                if (now - r.breakAt < breakNewMs) raise("break:${r.uid}:${r.breakAt}") {
                    RideAlert("break:${r.uid}:${r.breakAt}", "$who is taking a break", "Stopped for a break.$away")
                } else stillTrue += "break:${r.uid}:${r.breakAt}"
            }

            // Missed a turn: their navigation rerouted in the last few minutes.
            if (r.rerouteAt > 0 && now - r.rerouteAt < RECENT_MS) {
                raise("reroute:${r.uid}:${r.rerouteAt}") {
                    RideAlert("reroute:${r.uid}:${r.rerouteAt}", "$who missed a turn", "Their navigation is rerouting.$away")
                }
            } else if (r.rerouteAt > 0) stillTrue += "reroute:${r.uid}:${r.rerouteAt}"

            if (r.lat == null || r.lon == null || r.updatedAt == 0L) continue
            val age = now - r.updatedAt
            val pos = GeoPoint(r.lat, r.lon)
            if (r.speedKmh >= MOVING_KMH) lastMovingAt[r.uid] = r.updatedAt
            if (r.speedKmh >= MOVING_KMH && r.speedKmh < CRAWL_MAX_KMH) slowSince.getOrPut(r.uid) { r.updatedAt }
            else slowSince.remove(r.uid)

            // Lost signal: the leader and sweep hear about anyone; everyone hears about the leader and sweep.
            if (age > silentMs) {
                if (iLead || keyRole) raise("silent:${r.uid}") {
                    RideAlert(
                        "silent:${r.uid}", "No signal from $who",
                        "Last update ${age / 60_000} min ago." + when (r.role) {
                            Role.LEADER -> " If they need help, the sweep or any rider can take over as leader in the Group tab."
                            Role.SWEEP -> " The leader can pick a new sweep in the Group tab."
                            else -> ""
                        },
                        urgent = keyRole,
                    )
                }
                continue // can't judge distance or stops on stale data
            }

            // Stopped without saying it's a break (only once we've seen them moving).
            val movedAt = lastMovingAt[r.uid]
            val atPlannedPlace = planned.any { distanceMeters(it, pos) < plannedStopMeters }
            if (r.breakAt == 0L && !atPlannedPlace && movedAt != null && r.speedKmh < MOVING_KMH && r.updatedAt - movedAt >= stoppedMs) {
                raise("stopped:${r.uid}") {
                    RideAlert(
                        "stopped:${r.uid}",
                        if (planned.isEmpty()) "$who has stopped" else "$who stopped at an unplanned place",
                        "Not moving for ${(r.updatedAt - movedAt) / 60_000} min.$away",
                    )
                }
            }

            // Slow traffic: crawling for a while (not stopped, not riding normally).
            val slowFrom = slowSince[r.uid]
            if (r.breakAt == 0L && slowFrom != null && r.updatedAt - slowFrom >= slowMs) {
                raise("slow:${r.uid}") {
                    RideAlert("slow:${r.uid}", "$who is in slow traffic", "Crawling at ${r.speedKmh.toInt()} km/h for ${(r.updatedAt - slowFrom) / 60_000} min.$away")
                }
            }

            // Off the group's planned route (wrong turn or a different road).
            val off = route?.let { distanceToPolyline(pos, it) }
            if (off != null && off > offRouteMeters) {
                raise("offroute:${r.uid}") {
                    RideAlert("offroute:${r.uid}", "$who is off the planned route", "${formatDistance(off)} from the route. Wrong turn?")
                }
            }

            // Fell behind (leader only, measured from the leader).
            if (myRole == Role.LEADER && me != null) {
                val d = distanceMeters(me, GeoPoint(r.lat, r.lon))
                if (d > behindMeters) raise("behind:${r.uid}") { RideAlert("behind:${r.uid}", "${r.name} fell behind", "${formatDistance(d)} from you.") }
            }
        }
        // I fell behind the leader.
        val leader = riders.firstOrNull { it.role == Role.LEADER }
        if (myRole != Role.LEADER && me != null && leader?.lat != null && leader.lon != null && leader.updatedAt > 0 && now - leader.updatedAt <= silentMs) {
            val d = distanceMeters(me, GeoPoint(leader.lat, leader.lon))
            if (d > behindMeters) raise("self-behind") {
                RideAlert("self-behind", "You're ${formatDistance(d)} from the leader", "The group may be ahead of you. Check the map.")
            }
        }

        active.retainAll(stillTrue)
        active += out.map { it.key }
        return out
    }

    companion object {
        const val MOVING_KMH = 5f
        const val CRAWL_MAX_KMH = 15f
        const val RECENT_MS = 3 * 60_000L
    }
}
