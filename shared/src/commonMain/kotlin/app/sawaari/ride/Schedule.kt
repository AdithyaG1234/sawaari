package app.sawaari.ride

import app.sawaari.group.RideGroup
import app.sawaari.group.RideSchedule
import app.sawaari.group.RideStatus
import app.sawaari.group.StopTime
import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan

/**
 * One row of the ride's timetable. [key] is "start", "stop0".."stop7" or "end". [name] is the name the
 * leader gave the stop, or else the place's name on the map.
 */
data class ScheduleEntry(val key: String, val label: String, val name: String, val at: Long) {
    /** "Pit stop 1 (Breakfast)" or just "Pit stop 1". */
    val title: String get() = if (name.isBlank()) label else "$label ($name)"
}

/** Where a group ride is: planned (not started yet), under way, or over. */
enum class RidePhase { PLANNED, IN_PROGRESS, ENDED }

/** The ride timetable: which stops have times, what's next, and what is due now. Pure Kotlin for tests. */
object Schedule {
    const val START = "start"
    const val END = "end"
    fun stopKey(index: Int) = "stop$index"

    /** How late a reminder may still be shown (e.g. the phone was off at the time). */
    const val LATE_WINDOW_MS = 15 * 60_000L

    fun phase(ride: RideStatus?): RidePhase = when {
        ride == null -> RidePhase.PLANNED
        ride.started -> RidePhase.IN_PROGRESS
        else -> RidePhase.ENDED
    }

    /**
     * Every main stop of the ride in route order, with its time if one is set: the start, each pit
     * stop of [plan] (or of the schedule, when there's no plan yet) and the destination.
     */
    fun rows(plan: RidePlan?, schedule: RideSchedule?): List<Pair<String, StopTime?>> {
        val s = schedule ?: RideSchedule()
        val stopCount = plan?.stops?.size ?: ((s.stops.keys.maxOrNull() ?: -1) + 1)
        return buildList {
            add(START to s.start)
            for (i in 0 until stopCount) add(stopKey(i) to s.stops[i])
            add(END to s.end)
        }
    }

    fun label(key: String): String = when {
        key == START -> "Start"
        key == END -> "Destination"
        key.startsWith("stop") -> "Pit stop ${(key.removePrefix("stop").toIntOrNull() ?: 0) + 1}"
        else -> key
    }

    /** The name to show for a stop: the leader's own name for it, else its name on the map, else blank. */
    fun nameFor(key: String, t: StopTime?, mapNames: Map<String, String>): String =
        t?.name?.takeIf { it.isNotBlank() } ?: mapNames[key].orEmpty()

    /** The stops that have a time, in route order. */
    fun entries(plan: RidePlan?, schedule: RideSchedule?, mapNames: Map<String, String> = emptyMap()): List<ScheduleEntry> =
        rows(plan, schedule).mapNotNull { (key, t) -> t?.let { ScheduleEntry(key, label(key), nameFor(key, it, mapNames), it.at) } }

    fun entries(g: RideGroup): List<ScheduleEntry> = entries(g.plan, g.schedule, g.planNames)

    /** Map names for a plan's points, keyed like the schedule ("start", "stop0".., "end"). */
    fun planNameKeys(plan: RidePlan, names: (GeoPoint) -> String?): Map<String, String> = buildMap {
        plan.start?.let(names)?.takeIf { it.isNotBlank() }?.let { put(START, it) }
        plan.stops.forEachIndexed { i, p -> names(p)?.takeIf { it.isNotBlank() }?.let { put(stopKey(i), it) } }
        plan.end?.let(names)?.takeIf { it.isNotBlank() }?.let { put(END, it) }
    }

    /** The next stop still to come after [now], or null. */
    fun next(entries: List<ScheduleEntry>, now: Long): ScheduleEntry? = entries.filter { it.at > now }.minByOrNull { it.at }

    /** Stops whose time has just been reached (in the last [LATE_WINDOW_MS]). */
    fun due(entries: List<ScheduleEntry>, now: Long): List<ScheduleEntry> =
        entries.filter { it.at <= now && now - it.at <= LATE_WINDOW_MS }

    /** Reminders still worth sending: none once the ride is over. */
    fun upcoming(g: RideGroup, now: Long): List<ScheduleEntry> =
        if (phase(g.ride) == RidePhase.ENDED) emptyList() else entries(g).filter { it.at > now - LATE_WINDOW_MS }

    /** Identifies one reminder, so it fires once even if both the alarm and the ride service see it. */
    fun reminderId(code: String, e: ScheduleEntry) = "$code:${e.key}:${e.at}"

    /**
     * The notification for a stop's time: [time] formats a time of day. [phase] decides the wording
     * for the start: a ride that hasn't started yet asks someone to start it.
     */
    fun reminderText(groupName: String, e: ScheduleEntry, next: ScheduleEntry?, phase: RidePhase, time: (Long) -> String): Pair<String, String> {
        val title = when (e.key) {
            START -> if (phase == RidePhase.PLANNED) "Time to start: $groupName" else "Scheduled start time: $groupName"
            END -> "${e.title}: time to arrive"
            else -> "${e.title}: time to be there"
        }
        val text = buildString {
            append("Scheduled for ${time(e.at)}")
            if (e.name.isNotBlank()) append(" at ${e.name}")
            append(".")
            if (e.key == START && phase == RidePhase.PLANNED) append(" When everyone's ready, tap Start ride in the Ride window.")
            if (next != null && next.key != e.key) append(" Next: ${next.title} at ${time(next.at)}.")
        }
        return title to text
    }
}
