package app.sawaari.ride

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.group.Rider
import app.sawaari.group.Role
import app.sawaari.nav.formatDistance

/** One alert about another rider, e.g. "Ravi fell 2.3 km behind". */
data class RideAlert(val key: String, val title: String, val text: String)

/**
 * Watches the group and raises alerts for the leader and sweep:
 *  - fell behind: a rider is more than [behindMeters] from the leader
 *  - stopped: a rider who was moving hasn't moved for [stoppedMs]
 *  - lost signal: a rider hasn't updated for [silentMs]
 * Each alert fires once and re-arms when the condition clears. Pure Kotlin for unit tests.
 */
class GroupWatcher(
    private val behindMeters: Double = 2_000.0,
    private val stoppedMs: Long = 5 * 60_000L,
    private val silentMs: Long = 5 * 60_000L,
) {
    private val lastMovingAt = mutableMapOf<String, Long>()
    private val active = mutableSetOf<String>()

    fun check(riders: List<Rider>, myUid: String?, me: GeoPoint?, now: Long): List<RideAlert> {
        val myRole = riders.firstOrNull { it.uid == myUid }?.role ?: return emptyList()
        if (myRole != Role.LEADER && myRole != Role.SWEEP) return emptyList()
        val out = mutableListOf<RideAlert>()
        val stillTrue = mutableSetOf<String>()

        for (r in riders) {
            if (r.uid == myUid || r.lat == null || r.lon == null || r.updatedAt == 0L) continue
            val age = now - r.updatedAt

            if (r.speedKmh >= MOVING_KMH) lastMovingAt[r.uid] = r.updatedAt

            // Lost signal
            val silentKey = "silent:${r.uid}"
            if (age > silentMs) {
                stillTrue += silentKey
                if (silentKey !in active) out += RideAlert(silentKey, "No signal from ${r.name}", "Last update ${age / 60_000} min ago.")
                continue // can't judge distance or stops on stale data
            }

            // Stopped (only if we've seen them moving before)
            val movedAt = lastMovingAt[r.uid]
            val stoppedKey = "stopped:${r.uid}"
            if (movedAt != null && r.speedKmh < MOVING_KMH && r.updatedAt - movedAt >= stoppedMs) {
                stillTrue += stoppedKey
                if (stoppedKey !in active) out += RideAlert(stoppedKey, "${r.name} has stopped", "Not moving for ${(r.updatedAt - movedAt) / 60_000} min.")
            }

            // Fell behind (leader only, measured from the leader)
            val behindKey = "behind:${r.uid}"
            if (myRole == Role.LEADER && me != null) {
                val d = distanceMeters(me, GeoPoint(r.lat, r.lon))
                if (d > behindMeters) {
                    stillTrue += behindKey
                    if (behindKey !in active) out += RideAlert(behindKey, "${r.name} fell behind", "${formatDistance(d)} from you.")
                }
            }
        }
        active.retainAll(stillTrue)
        active += out.map { it.key }
        return out
    }

    companion object { const val MOVING_KMH = 8f }
}
