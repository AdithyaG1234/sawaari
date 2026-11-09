package app.sawaari.routing

/**
 * Offline routing packs. Each one is a pre-processed GraphHopper graph for a ride area,
 * built by .github/workflows/build-routing.yml and published on the "routing-v1" release.
 * Keep the ids and boxes in sync with that workflow.
 */
data class RoutingPack(
    val id: String,
    val name: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
    val downloadUrl: String get() = "${RoutingConfig.RELEASE_BASE_URL}routing-$id.zip"
}

object RoutingConfig {
    const val RELEASE_BASE_URL = "https://github.com/AdithyaG1234/sawaari/releases/download/routing-v1/"
    const val PROFILE = "car"
    const val VEHICLE = "car"
}

val routingPacks = listOf(
    RoutingPack("bengaluru-coorg", "Bengaluru, Mysuru, Coorg & Chikmagalur", 75.2, 11.6, 78.2, 13.9),
    RoutingPack("ooty-munnar", "Ooty, Kodaikanal, Munnar & Wayanad", 75.7, 9.8, 77.8, 12.0),
    RoutingPack("mumbai-pune-goa", "Mumbai, Pune, Lonavala & Goa", 72.7, 14.7, 74.6, 19.4),
    RoutingPack("himachal-ladakh", "Manali, Spiti & Leh", 76.5, 31.0, 78.8, 34.6),
)

/**
 * When no single pack covers every point of a trip, finds the pack that covers the most of them
 * and returns it with the indexes of the points that fall outside it. Null when one pack covers all,
 * or when none covers any point.
 */
fun pointsOutsidePacks(points: List<app.sawaari.geo.GeoPoint>, packs: List<RoutingPack> = routingPacks): Pair<RoutingPack, List<Int>>? {
    if (packs.any { pack -> points.all { pack.contains(it.lat, it.lon) } }) return null
    val best = packs.maxByOrNull { pack -> points.count { pack.contains(it.lat, it.lon) } } ?: return null
    if (points.none { best.contains(it.lat, it.lon) }) return null
    return best to points.indices.filter { !best.contains(points[it].lat, points[it].lon) }
}
