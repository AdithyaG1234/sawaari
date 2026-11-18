package app.sawaari.share

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan

/** A plan read from a link, with names for its points where the link had them. */
data class LinkPlan(val plan: RidePlan, val names: Map<GeoPoint, String>, val skipped: Int)

class LinkImportException(message: String) : Exception(message)

/**
 * Turns a shared link (Sawaari, Google Maps full or short link) into a ride plan, the same way as RouteImporter
 * on Android. [expand] follows a short maps.app.goo.gl link; [geocode] finds a place name near a point.
 */
object LinkImport {
    suspend fun import(
        textOrUrl: String,
        near: GeoPoint?,
        expand: suspend (String) -> String?,
        geocode: suspend (String, GeoPoint?) -> GeoPoint?,
    ): LinkPlan {
        var url = RouteLinks.findUrl(textOrUrl) ?: textOrUrl.trim().takeIf { it.startsWith("sawaari://") }
            ?: throw LinkImportException("That doesn't contain a link.")
        if (RouteLinks.isShortGoogleLink(url)) {
            url = expand(url)?.takeIf { RouteLinks.parse(it) != null }
                ?: throw LinkImportException("Couldn't open the Google Maps link. Check your internet.")
        }
        val route = RouteLinks.parse(url) ?: throw LinkImportException("That link isn't a route or a place.")

        // Look names up around the places the link does give coordinates for (else around the rider).
        val anchor = route.points.firstNotNullOfOrNull { it.point } ?: near
        var skipped = 0
        val resolved = route.points.mapIndexed { i, lp ->
            when {
                lp.point != null -> lp.point to lp.name
                lp.name.isBlank() && i == 0 -> null to "" // start from my location
                else -> geocode(lp.name, anchor)?.let { it to lp.name } ?: run { skipped++; null to "" }
            }
        }
        val points = resolved.mapNotNull { it.first }
        val startFromMe = route.points.first().point == null && route.points.first().name.isBlank()
        if (points.isEmpty() || (points.size < 2 && !startFromMe)) throw LinkImportException("Couldn't find the places in that link.")
        val plan = if (startFromMe) RidePlan(stops = points.dropLast(1), end = points.last())
        else RidePlan(start = points.first(), stops = points.drop(1).dropLast(1), end = points.last())
        val names = resolved.filter { it.first != null && it.second.isNotBlank() }.associate { it.first!! to it.second }
        return LinkPlan(plan.copy(stops = plan.stops.take(RidePlan.MAX_STOPS)), names, skipped)
    }
}
