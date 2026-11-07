package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.group.Rider
import app.sawaari.group.Role
import app.sawaari.ride.GroupWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupWatcherTest {
    private val me = GeoPoint(12.97, 77.59)
    private fun leader() = Rider("me", "Adi", Role.LEADER, me.lat, me.lon, 40f, 0)
    private fun rider(lat: Double, speed: Float, ts: Long) = Rider("r1", "Ravi", Role.RIDER, lat, 77.59, speed, ts)

    @Test fun fellBehindFiresOnceAndReArms() {
        val w = GroupWatcher()
        val far = 12.97 - 0.03 // ~3.3 km south
        val a1 = w.check(listOf(leader(), rider(far, 40f, 1000)), "me", me, 1000)
        assertEquals(listOf("Ravi fell behind"), a1.map { it.title })
        assertTrue(w.check(listOf(leader(), rider(far, 40f, 2000)), "me", me, 2000).isEmpty())
        assertTrue(w.check(listOf(leader(), rider(12.969, 40f, 3000)), "me", me, 3000).isEmpty()) // caught up
        assertEquals(1, w.check(listOf(leader(), rider(far, 40f, 4000)), "me", me, 4000).size) // behind again
    }

    @Test fun stoppedAfterFiveMinutes() {
        val w = GroupWatcher()
        val t0 = 1_000L // updatedAt 0 means "no location yet", so start later
        w.check(listOf(leader(), rider(12.969, 30f, t0)), "me", me, t0)
        val t4 = t0 + 4 * 60_000L
        assertTrue(w.check(listOf(leader(), rider(12.969, 0f, t4)), "me", me, t4).isEmpty())
        val t5 = t0 + 5 * 60_000L
        val a = w.check(listOf(leader(), rider(12.969, 0f, t5)), "me", me, t5)
        assertEquals(listOf("Ravi has stopped"), a.map { it.title })
    }

    @Test fun plainRidersGetNoAlerts() {
        val w = GroupWatcher()
        val riders = listOf(leader().copy(uid = "x", role = Role.LEADER), Rider("me", "Me", Role.RIDER, 12.0, 77.0, 0f, 0))
        assertTrue(w.check(riders, "me", me, 10 * 60_000L).isEmpty())
    }
}
