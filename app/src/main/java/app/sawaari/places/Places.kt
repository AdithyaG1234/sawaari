package app.sawaari.places

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.routing.Route
import kotlin.math.floor

enum class PlaceCategory(
    val key: String, val label: String, val letter: String, val color: String, val fallbackName: String, val inChips: Boolean = true,
) {
    FOOD("food", "Food", "F", "#D84315", "Restaurant"),
    FUEL("fuel", "Fuel", "P", "#1565C0", "Fuel station"),
    MECHANIC("mechanic", "Mechanic", "M", "#4E342E", "Mechanic"),
    TYRES("tyres", "Tyres", "T", "#37474F", "Tyre shop"),
    HOSPITAL("hospital", "Hospital", "H", "#C62828", "Hospital"),
    SIGHT("sight", "Sights", "V", "#2E7D32", "Viewpoint"),
    TOWN("town", "Town", "", "#000000", "Town", inChips = false);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** A place from OpenStreetMap. [id] is the OSM id like "n123" or "w456", used as the ratings key. */
data class Place(val id: String, val category: PlaceCategory, val point: GeoPoint, val name: String, val phone: String) {
    val displayName: String get() = name.ifBlank { category.fallbackName }
}

/** A place near the route: how far along the route it is, and how far off it. */
data class PlaceOnRoute(val place: Place, val alongMeters: Double, val offRouteMeters: Double)

object PlaceMatcher {
    private const val CELL_DEG = 0.02 // about 2.2 km
    private const val SAMPLE_METERS = 150.0

    /** Parses one line of a places file: id, category, lat, lon, name, phone (tab separated). */
    fun parse(line: String): Place? {
        val f = line.split('\t')
        if (f.size < 4) return null
        val cat = PlaceCategory.of(f[1]) ?: return null
        val lat = f[2].toDoubleOrNull() ?: return null
        val lon = f[3].toDoubleOrNull() ?: return null
        return Place(f[0], cat, GeoPoint(lat, lon), f.getOrElse(4) { "" }, f.getOrElse(5) { "" })
    }

    /**
     * Places within [maxOffMeters] of [route], ordered along it. Samples the route every ~150 m and
     * buckets the samples in a grid, so it stays fast with tens of thousands of places.
     */
    fun alongRoute(route: Route, places: List<Place>, maxOffMeters: Double = 1_000.0): List<PlaceOnRoute> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val grid = HashMap<Long, MutableList<Int>>()
        val samples = ArrayList<GeoPoint>()
        val along = ArrayList<Double>()
        var last = -SAMPLE_METERS
        for (i in pts.indices) {
            val c = route.cumulative[i]
            if (c - last >= SAMPLE_METERS || i == pts.lastIndex) {
                samples += pts[i]; along += c; last = c
                grid.getOrPut(cell(pts[i].lat, pts[i].lon)) { mutableListOf() } += samples.lastIndex
            }
        }
        val out = ArrayList<PlaceOnRoute>()
        for (p in places) {
            val cy = floor(p.point.lat / CELL_DEG).toLong()
            val cx = floor(p.point.lon / CELL_DEG).toLong()
            var best = Double.MAX_VALUE
            var bestIdx = -1
            for (dy in -1L..1L) for (dx in -1L..1L) {
                grid[key(cy + dy, cx + dx)]?.forEach { i ->
                    val d = distanceMeters(p.point, samples[i])
                    if (d < best) { best = d; bestIdx = i }
                }
            }
            if (bestIdx >= 0 && best <= maxOffMeters) out += PlaceOnRoute(p, along[bestIdx], best)
        }
        out.sortBy { it.alongMeters }
        return out
    }

    private fun cell(lat: Double, lon: Double) = key(floor(lat / CELL_DEG).toLong(), floor(lon / CELL_DEG).toLong())
    private fun key(y: Long, x: Long) = (y shl 32) xor (x and 0xffffffffL)
}
