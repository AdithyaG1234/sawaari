package app.sawaari.ride

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.group.Rider
import app.sawaari.group.Role
import app.sawaari.nav.formatDistance

/** One alert about another rider, e.g. "Ravi fell 2.3 km behind". [urgent] alerts use the SOS channel. */
data class RideAlert(val key: String, val title: String, val text: String, val urgent: Boolean = false)

/**
 * Watches the group and raises alerts. Everyone hears about:
 *  - a break: a rider tapped "Taking a break"
 *  - stopped: a rider who was moving hasn't moved for [stoppedMs] (and didn't say it's a break)
 *  - trouble with the leader or sweep: no signal for [silentMs]
 * The leader and sweep also hear about no signal from any rider, and the leader about riders more
 * than [behindMeters] behind. Each alert fires once and re-arms when the condition clears.
 * Pure Kotlin for unit tests.
 */
class GroupWatcher(
    private val behindMeters: Double = 2_000.0,
    private val stoppedMs: Long = 5 * 60_000L,
    private val silentMs: Long = 5 * 60_000L,
    private val breakNewMs: Long = 30 * 60_000L,
) {
    private val lastMovingAt = mutableMapOf<String, Long>()
    private val active = mutableSetOf<String>()

    fun check(riders: List<Rider>, myUid: String?, me: GeoPoint?, now: Long): List<RideAlert> {
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

            if (r.lat == null || r.lon == null || r.updatedAt == 0L) continue
            val age = now - r.updatedAt
            if (r.speedKmh >= MOVING_KMH) lastMovingAt[r.uid] = r.updatedAt

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
            if (r.breakAt == 0L && movedAt != null && r.speedKmh < MOVING_KMH && r.updatedAt - movedAt >= stoppedMs) {
                raise("stopped:${r.uid}") {
                    RideAlert("stopped:${r.uid}", "$who has stopped", "Not moving for ${(r.updatedAt - movedAt) / 60_000} min.$away")
                }
            }

            // Fell behind (leader only, measured from the leader).
            if (myRole == Role.LEADER && me != null) {
                val d = distanceMeters(me, GeoPoint(r.lat, r.lon))
                if (d > behindMeters) raise("behind:${r.uid}") { RideAlert("behind:${r.uid}", "${r.name} fell behind", "${formatDistance(d)} from you.") }
            }
        }
        active.retainAll(stillTrue)
        active += out.map { it.key }
        return out
    }

    companion object { const val MOVING_KMH = 8f }
}
