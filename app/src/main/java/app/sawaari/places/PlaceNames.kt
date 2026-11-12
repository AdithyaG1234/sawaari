package app.sawaari.places

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters

/** Friendly names for map points that have none, e.g. "Near Gubbi" instead of "13.3160, 76.9433". */
object PlaceNames {
    /** Within this distance a point is "in" a town; up to [NEAR_M] it is "near" it. */
    private const val IN_M = 1_500.0
    private const val NEAR_M = 15_000.0

    fun describe(p: GeoPoint, places: List<Place>): String? {
        val town = places.asSequence()
            .filter { it.category == PlaceCategory.TOWN && it.name.isNotBlank() }
            .map { it to distanceMeters(p, it.point) }
            .filter { it.second <= NEAR_M }
            .minByOrNull { it.second } ?: return null
        return if (town.second <= IN_M) town.first.name else "Near ${town.first.name}"
    }
}
