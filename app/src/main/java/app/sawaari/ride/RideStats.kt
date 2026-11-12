package app.sawaari.ride

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters

/** One recorded GPS fix: where, when (epoch ms) and the speed the GPS reported. */
data class TrackPoint(val lat: Double, val lon: Double, val t: Long, val kmh: Double) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/** Totals for one ride. Times in milliseconds, distance in metres, speeds in km/h. */
data class RideStats(
    val distanceMeters: Double,
    val totalMillis: Long,
    val movingMillis: Long,
    val avgMovingKmh: Double,
    val topKmh: Double,
) {
    companion object {
        /** Slower than this between two fixes counts as stopped (tea, traffic lights, regrouping). */
        private const val MOVING_KMH = 5.0
        /** A gap longer than this (GPS lost, phone off) isn't counted as riding time. */
        private const val MAX_GAP_MS = 120_000L
        /** GPS glitches can report silly speeds; nothing on the road goes faster than this. */
        private const val MAX_SANE_KMH = 220.0

        fun of(track: List<TrackPoint>): RideStats {
            if (track.size < 2) return RideStats(0.0, 0, 0, 0.0, 0.0)
            var dist = 0.0
            var moving = 0L
            var prev = track.first()
            for (b in track.drop(1)) {
                val dt = b.t - prev.t
                if (dt <= 0) continue
                val d = distanceMeters(prev.point, b.point)
                // A fix that implies an impossible speed is a GPS glitch: skip it and keep the last good one.
                if (d / 1000.0 / (dt / 3_600_000.0) > MAX_SANE_KMH) continue
                dist += d
                if (d / 1000.0 / (dt / 3_600_000.0) >= MOVING_KMH && dt <= MAX_GAP_MS) moving += dt
                prev = b
            }
            val top = track.map { it.kmh }.filter { it <= MAX_SANE_KMH }.maxOrNull() ?: 0.0
            val avg = if (moving > 0) dist / 1000.0 / (moving / 3_600_000.0) else 0.0
            return RideStats(dist, track.last().t - track.first().t, moving, avg, top)
        }
    }
}
