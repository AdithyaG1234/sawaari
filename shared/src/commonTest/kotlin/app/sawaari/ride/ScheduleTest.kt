package app.sawaari.ride

import app.sawaari.geo.GeoPoint
import app.sawaari.group.RideGroup
import app.sawaari.group.RideSchedule
import app.sawaari.group.RideStatus
import app.sawaari.group.StopTime
import app.sawaari.nav.RidePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private val min = 60_000L
    private val plan = RidePlan(stops = listOf(GeoPoint(15.0, 74.0), GeoPoint(15.1, 74.1)), end = GeoPoint(15.5, 73.8))
    private val schedule = RideSchedule(
        start = StopTime(0L),
        stops = mapOf(1 to StopTime(90 * min, "Lunch"), 5 to StopTime(100 * min)),
        end = StopTime(240 * min),
    )

    @Test fun rowsFollowTheRoute() {
        val rows = Schedule.rows(plan, schedule)
        assertEquals(listOf("start", "stop0", "stop1", "end"), rows.map { it.first })
        assertNull(rows[1].second)
        // A time for a stop that's no longer in the plan is left out.
        assertEquals(listOf("Start", "Pit stop 2 (Lunch)", "Destination"), Schedule.entries(plan, schedule).map { it.title })
        // No plan yet: start and end only, plus any stops that have times.
        assertEquals(listOf("start", "end"), Schedule.rows(null, RideSchedule(start = StopTime(1))).map { it.first })
    }

    @Test fun nextAndDue() {
        val e = Schedule.entries(plan, schedule)
        assertEquals("stop1", Schedule.next(e, 10 * min)?.key)
        assertEquals(listOf("stop1"), Schedule.due(e, 95 * min).map { it.key })
        assertTrue(Schedule.due(e, 120 * min).isEmpty())
        assertNull(Schedule.next(e, 300 * min))
    }

    @Test fun noRemindersOnceTheRideIsOver() {
        val g = RideGroup("ABCDEF", "Goa", "l", emptyList(), plan = plan, schedule = schedule)
        assertEquals(3, Schedule.upcoming(g, 0L).size)
        assertEquals(RidePhase.PLANNED, Schedule.phase(g.ride))
        val ended = g.copy(ride = RideStatus(RideStatus.ENDED, 5L, "Ravi", "r"))
        assertTrue(Schedule.upcoming(ended, 0L).isEmpty())
        assertEquals(RidePhase.IN_PROGRESS, Schedule.phase(RideStatus(RideStatus.STARTED, 1L, "", "")))
    }

    @Test fun reminderWording() {
        val e = Schedule.entries(plan, schedule)
        val fmt = { t: Long -> "${t / min}m" }
        val (t1, x1) = Schedule.reminderText("Goa", e[0], e[1], RidePhase.PLANNED, fmt)
        assertEquals("Time to start: Goa", t1)
        assertEquals("Scheduled for 0m. When everyone's ready, tap Start ride in the Ride window. Next: Pit stop 2 (Lunch) at 90m.", x1)
        val (t2, x2) = Schedule.reminderText("Goa", e[1], e[2], RidePhase.IN_PROGRESS, fmt)
        assertEquals("Pit stop 2 (Lunch): time to be there", t2)
        assertEquals("Scheduled for 90m at Lunch. Next: Destination at 240m.", x2)
    }
}

class PackingTest {
    @Test fun checklistIsPersonalAndSharesOnlyChosenItems() {
        var items = Packing.defaults()
        assertTrue(items.none { it.packed || it.shared })
        items = Packing.add(items, "  Bungee cords ", "u1")
        assertEquals("Bungee cords", items.last().name)
        assertEquals(items, Packing.add(items, "bungee CORDS", "u2")) // no duplicates
        items = Packing.toggle(items, "u1")
        items = Packing.toggleShared(items, "d7") // Puncture repair kit
        assertEquals(mapOf("d7" to "Puncture repair kit"), Packing.sharedForGroup(items))
        assertEquals(items, Packing.decode(Packing.encode(items)))
        items = Packing.remove(items, "d7")
        assertTrue(Packing.sharedForGroup(items).isEmpty())
        assertNull(Packing.decode("not json"))
    }
}
