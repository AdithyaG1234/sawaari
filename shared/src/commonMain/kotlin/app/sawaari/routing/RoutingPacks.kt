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
    /** Rough download size, shown before downloading. */
    val approxMb: Int = 0,
    /** A big pack for a whole trip or state, as opposed to one weekend ride area. */
    val wholeTrip: Boolean = false,
) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
    fun containsAll(points: List<app.sawaari.geo.GeoPoint>) = points.all { contains(it.lat, it.lon) }
    /** Box size in square degrees, used to prefer the smaller (faster to load) pack. */
    val area: Double get() = (east - west) * (north - south)
    val downloadUrl: String get() = "${RoutingConfig.RELEASE_BASE_URL}routing-$id.zip"
}

object RoutingConfig {
    const val RELEASE_BASE_URL = "https://github.com/AdithyaG1234/sawaari/releases/download/routing-v1/"
    const val PROFILE = "car"
    const val VEHICLE = "car"
}

val routingPacks = listOf(
    RoutingPack("bengaluru-coorg", "Bengaluru, Mysuru, Coorg & Chikmagalur", 75.2, 11.6, 78.2, 13.9, approxMb = 58),
    RoutingPack("ooty-munnar", "Ooty, Kodaikanal, Munnar & Wayanad", 75.7, 9.8, 77.8, 12.0, approxMb = 52),
    RoutingPack("mumbai-pune-goa", "Mumbai, Pune, Lonavala & Goa", 72.7, 14.7, 74.6, 19.4, approxMb = 39),
    RoutingPack("himachal-ladakh", "Manali, Spiti & Leh", 76.5, 31.0, 78.8, 34.6, approxMb = 8),
    RoutingPack("coastal-karnataka", "Mangaluru, Udupi, Gokarna & Agumbe", 74.0, 12.4, 75.4, 14.9, approxMb = 9),
    RoutingPack("kerala-south", "Kochi, Alleppey, Varkala, Thekkady & Kanyakumari", 75.9, 7.9, 77.7, 10.3, approxMb = 37),
    RoutingPack("chennai-pondy", "Chennai, Pondicherry, ECR & Yelagiri", 78.5, 11.4, 80.4, 13.4, approxMb = 39),
    RoutingPack("hyderabad-srisailam", "Hyderabad, Srisailam & Ananthagiri", 77.6, 15.9, 79.3, 17.8, approxMb = 28),
    RoutingPack("delhi-uttarakhand", "Delhi, Rishikesh, Mussoorie, Chopta & Kedarnath", 76.9, 28.4, 79.7, 31.1, approxMb = 29),
    RoutingPack("rajasthan", "Jaipur, Pushkar, Jodhpur, Udaipur & Mount Abu", 72.6, 24.2, 76.4, 27.3, approxMb = 44),
    RoutingPack("kashmir", "Srinagar, Gulmarg, Pahalgam, Sonamarg & Kargil", 74.0, 33.5, 76.4, 34.7, approxMb = 7),
    RoutingPack("sikkim", "Gangtok, Nathula & North Sikkim", 88.0, 27.0, 88.95, 28.15, approxMb = 2),
    RoutingPack("meghalaya", "Guwahati, Shillong, Cherrapunji & Dawki", 90.8, 25.0, 92.5, 26.3, approxMb = 7),
    // Whole-trip packs: one download covers a multi-day trip across several ride areas.
    RoutingPack("bengaluru-goa", "Bengaluru to Goa (Tumakuru, Chitradurga, Hubballi, Dharwad, Dandeli)", 73.6, 12.7, 77.8, 15.95, approxMb = 120, wholeTrip = true),
    RoutingPack("karnataka-goa", "Karnataka & Goa (whole states)", 73.6, 11.5, 78.6, 18.5, approxMb = 260, wholeTrip = true),
)

/**
 * When no single pack covers every point of a trip, finds the pack that covers the most of them
 * and returns it with the indexes of the points that fall outside it. Null when one pack covers all,
 * or when none covers any point.
 */
fun pointsOutsidePacks(points: List<app.sawaari.geo.GeoPoint>, packs: List<RoutingPack> = routingPacks): Pair<RoutingPack, List<Int>>? {
    if (packs.any { pack -> points.all { pack.contains(it.lat, it.lon) } }) return null
    // On a tie, the smallest pack (the local ride area) is named.
    val best = packs.sortedBy { it.area }.maxByOrNull { pack -> points.count { pack.contains(it.lat, it.lon) } } ?: return null
    if (points.none { best.contains(it.lat, it.lon) }) return null
    return best to points.indices.filter { !best.contains(points[it].lat, points[it].lon) }
}

/**
 * The pack to use for a trip through [points]: the smallest downloaded pack that covers all of them,
 * else the smallest pack that would (to suggest downloading). Null when no single pack covers the trip.
 */
fun bestPackFor(
    points: List<app.sawaari.geo.GeoPoint>,
    downloaded: Set<String> = emptySet(),
    packs: List<RoutingPack> = routingPacks,
): RoutingPack? {
    val covering = packs.filter { it.containsAll(points) }
    return covering.filter { it.id in downloaded }.minByOrNull { it.area } ?: covering.minByOrNull { it.area }
}
