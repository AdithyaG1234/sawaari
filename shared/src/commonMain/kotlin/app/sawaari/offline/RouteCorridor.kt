package app.sawaari.offline

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * The map along a planned route, for a whole multi-day trip: a band a few km either side of the road,
 * instead of every box the trip touches. Built from a grid of small cells so that a trip that goes out
 * and back on the same highway isn't counted twice, then merged into as few rectangles as possible.
 */
object RouteCorridor {
    /** How far either side of the route the map is saved, enough for fuel, food and small detours. */
    const val BUFFER_KM = 4.0
    /** Grid cell size in degrees (about 4.4 km). */
    const val CELL_DEG = 0.04
    const val MIN_ZOOM = 6
    const val MAX_ZOOM = 14
    /** Average size of one map tile along Indian highways, for the size estimate shown before download. */
    const val AVG_TILE_BYTES = 20_000L

    /** Rectangles covering [line] plus [bufferKm] either side. */
    fun boxes(line: List<GeoPoint>, bufferKm: Double = BUFFER_KM, cellDeg: Double = CELL_DEG): List<GeoBounds> {
        if (line.isEmpty()) return emptyList()
        val cells = HashSet<Long>()
        val bufLat = bufferKm / 111.0
        fun mark(p: GeoPoint) {
            val bufLon = bufferKm / (111.0 * cos(p.lat * PI / 180).coerceAtLeast(0.1))
            val r0 = floor((p.lat - bufLat) / cellDeg).toInt(); val r1 = floor((p.lat + bufLat) / cellDeg).toInt()
            val c0 = floor((p.lon - bufLon) / cellDeg).toInt(); val c1 = floor((p.lon + bufLon) / cellDeg).toInt()
            for (r in r0..r1) for (c in c0..c1) cells += key(r, c)
        }
        // Sample the line at least every half cell, so long straight segments leave no gaps.
        val stepM = cellDeg * 111_000 / 2
        mark(line.first())
        line.zipWithNext { a, b ->
            val n = (distanceMeters(a, b) / stepM).toInt().coerceAtLeast(1)
            for (i in 1..n) {
                val t = i.toDouble() / n
                mark(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            }
        }
        // Runs of cells along each row, then stack identical runs from consecutive rows.
        val byRow = cells.groupBy({ rowOf(it) }, { colOf(it) })
        data class Run(val c0: Int, val c1: Int)
        val open = mutableMapOf<Run, Pair<Int, Int>>() // run -> (first row, last row)
        val out = mutableListOf<GeoBounds>()
        fun close(run: Run, rows: Pair<Int, Int>) {
            out += GeoBounds(north = (rows.second + 1) * cellDeg, east = (run.c1 + 1) * cellDeg, south = rows.first * cellDeg, west = run.c0 * cellDeg)
        }
        for (row in byRow.keys.sorted()) {
            val cols = byRow.getValue(row).sorted()
            val runs = mutableListOf<Run>()
            var start = cols.first(); var prev = start
            for (c in cols.drop(1)) { if (c != prev + 1) { runs += Run(start, prev); start = c }; prev = c }
            runs += Run(start, prev)
            // Copy the finished runs out first: removing while holding live map entries fails on iPhone.
            val finished = open.filter { (run, rows) -> rows.second != row - 1 || run !in runs }.toList()
            finished.forEach { (run, rows) -> close(run, rows); open.remove(run) }
            runs.forEach { run -> open[run] = (open[run]?.first ?: row) to row }
        }
        open.forEach { (run, rows) -> close(run, rows) }
        return out
    }

    /** Number of distinct map tiles needed to show [boxes] from [minZoom] to [maxZoom]. */
    fun tileCount(boxes: List<GeoBounds>, minZoom: Int = MIN_ZOOM, maxZoom: Int = MAX_ZOOM): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val tiles = HashSet<Long>()
            boxes.forEach { b ->
                val x0 = tileX(b.west, z); val x1 = tileX(b.east, z)
                val y0 = tileY(b.north, z); val y1 = tileY(b.south, z)
                for (x in x0..x1) for (y in y0..y1) tiles += (x.toLong() shl 32) or y.toLong()
            }
            total += tiles.size
        }
        return total
    }

    /** Rough download size of the map for [boxes], in bytes. */
    fun estimateBytes(boxes: List<GeoBounds>): Long = tileCount(boxes) * AVG_TILE_BYTES

    /** True if [p] is inside one of [boxes]. */
    fun covers(boxes: List<GeoBounds>, p: GeoPoint) = boxes.any { p.lat in it.south..it.north && p.lon in it.west..it.east }

    private fun key(r: Int, c: Int) = (r.toLong() shl 32) or (c.toLong() and 0xffffffffL)
    private fun rowOf(k: Long) = (k shr 32).toInt()
    private fun colOf(k: Long) = k.toInt()

    private fun tileX(lon: Double, z: Int): Int = floor((lon + 180) / 360 * (1 shl z)).toInt()
    private fun tileY(lat: Double, z: Int): Int {
        val r = lat * PI / 180
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }
}
