package app.sawaari.nav

import app.sawaari.ride.RideStats
import app.sawaari.ride.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RideStatsTest {
    // 0.009 degrees of latitude is about 1 km.
    private fun p(km: Double, minutes: Double, kmh: Double = 60.0) = TrackPoint(12.0 + km * 0.009, 77.0, (minutes * 60_000).toLong(), kmh)

    @Test fun steadyRide() {
        val s = RideStats.of(listOf(p(0.0, 0.0), p(1.0, 1.0), p(2.0, 2.0)))
        assertEquals(2_000.0, s.distanceMeters, 20.0)
        assertEquals(120_000L, s.movingMillis)
        assertEquals(60.0, s.avgMovingKmh, 1.0)
    }

    @Test fun teaBreakIsNotMovingTime() {
        // 1 km in a minute, then 20 minutes parked (fixes every minute), then 1 km more.
        val parked = (1..20).map { p(1.0, 1.0 + it, 0.0) }
        val s = RideStats.of(listOf(p(0.0, 0.0)) + p(1.0, 1.0) + parked + p(2.0, 22.0))
        assertEquals(120_000L, s.movingMillis)
        assertEquals(22 * 60_000L, s.totalMillis)
        assertEquals(60.0, s.avgMovingKmh, 1.0)
    }

    @Test fun gpsJumpIsIgnored() {
        val s = RideStats.of(listOf(p(0.0, 0.0), p(50.0, 0.1, 999.0), p(1.0, 1.0, 70.0)))
        assertEquals(70.0, s.topKmh, 0.1)
        // The glitch fix 50 km away is skipped, so only the real 1 km counts.
        assertEquals(1_000.0, s.distanceMeters, 20.0)
        assertEquals(60_000L, s.movingMillis)
    }
}
