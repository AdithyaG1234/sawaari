package app.sawaari.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A plain latitude/longitude pair, independent of any map library. */
data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in metres. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
}

/** Initial compass bearing from [a] to [b], 0-360 degrees. */
fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** Result of projecting a point onto a segment. */
data class Projection(val point: GeoPoint, val fraction: Double, val distance: Double)

/**
 * Project [p] onto segment [a]-[b] using a local flat-earth approximation,
 * which is accurate to well under a metre for road-length segments.
 */
fun projectOntoSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
    val cosLat = cos(Math.toRadians(a.lat))
    val ax = 0.0; val ay = 0.0
    val bx = (b.lon - a.lon) * cosLat; val by = b.lat - a.lat
    val px = (p.lon - a.lon) * cosLat; val py = p.lat - a.lat
    val len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / len2).coerceIn(0.0, 1.0)
    val proj = GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    return Projection(proj, t, distanceMeters(p, proj))
}

/** Shortest distance in metres from [p] to the line through [line], or null if it has fewer than 2 points. */
fun distanceToPolyline(p: GeoPoint, line: List<GeoPoint>): Double? {
    if (line.size < 2) return null
    var best = Double.MAX_VALUE
    for (i in 0 until line.size - 1) {
        val d = projectOntoSegment(p, line[i], line[i + 1]).distance
        if (d < best) best = d
    }
    return best
}
