package app.sawaari.group

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan

object Role {
    const val LEADER = "leader"
    const val SWEEP = "sweep"
    const val RIDER = "rider"
    /** Rides mid-pack and keeps the group together (e.g. blocks junctions so nobody takes a wrong turn). */
    const val MARSHAL = "marshal"
    /** Carries the first-aid kit and goes first to anyone who falls. */
    const val MEDIC = "medic"

    /** Roles the leader can give out from the rider list (leader and sweep have their own buttons). */
    val ASSIGNABLE = listOf(MARSHAL, MEDIC, RIDER)

    fun label(role: String) = when (role) {
        LEADER -> "Leader"; SWEEP -> "Sweep"; MARSHAL -> "Marshal"; MEDIC -> "Medic"; else -> "Rider"
    }
}

/** A chat message in the group. A voice note's audio is loaded separately when played. */
data class ChatMessage(
    val id: String,
    val uid: String,
    val name: String,
    val ts: Long,
    val text: String = "",
    /** Length of the voice note in ms, or 0 for a text message. */
    val voiceMs: Long = 0L,
)

data class Rider(
    val uid: String,
    val name: String,
    val role: String,
    val lat: Double?,
    val lon: Double?,
    val speedKmh: Float,
    val updatedAt: Long,
    /** When they said they're taking a break, or 0. */
    val breakAt: Long = 0L,
    /** When they last sent an SOS, or 0. */
    val sosAt: Long = 0L,
    /** When their navigation last had to reroute (missed a turn), or 0. */
    val rerouteAt: Long = 0L,
    /** Their own number and an emergency contact, only if they chose to share them with the group. */
    val phone: String = "",
    val iceName: String = "",
    val icePhone: String = "",
)

/** Where and when the group meets before the ride. Set by the leader. */
data class MeetingPoint(val point: GeoPoint, val time: Long, val note: String, val by: String)

data class SosAlert(
    val id: String,
    val uid: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val ts: Long,
)

data class RideGroup(
    val code: String,
    val name: String,
    val leaderUid: String,
    val riders: List<Rider>,
    /** The ride plan the leader shared, if any. */
    val plan: RidePlan? = null,
    val planBy: String = "",
    val planTs: Long = 0L,
    val meet: MeetingPoint? = null,
) {
    val leader: Rider? get() = riders.firstOrNull { it.uid == leaderUid }

    /**
     * True when the leader can't lead right now: no location update for [LEADER_SILENT_MS],
     * an SOS in the last hour, or they've left. Then any rider may take over (the rules allow it too).
     */
    fun leaderInTrouble(now: Long): Boolean {
        val l = leader ?: return true
        return (l.updatedAt > 0 && now - l.updatedAt > LEADER_SILENT_MS) || (l.sosAt > 0 && now - l.sosAt < SOS_TAKEOVER_MS)
    }

    companion object {
        const val LEADER_SILENT_MS = 10 * 60_000L
        const val SOS_TAKEOVER_MS = 60 * 60_000L
    }
}

