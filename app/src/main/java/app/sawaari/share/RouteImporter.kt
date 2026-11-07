package app.sawaari.share

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan
import app.sawaari.places.PlaceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** A plan read from a link, with names for its points where the link had them. */
data class ImportedPlan(val plan: RidePlan, val names: Map<GeoPoint, String>, val skipped: Int)

class RouteImportException(message: String) : Exception(message)

/** Turns a shared link (Sawaari, Google Maps full or short link) into a ride plan. */
class RouteImporter(private val search: PlaceSearch) {

    suspend fun import(textOrUrl: String, near: GeoPoint?): ImportedPlan {
        var url = RouteLinks.findUrl(textOrUrl) ?: textOrUrl.trim().takeIf { it.startsWith("sawaari://") }
            ?: throw RouteImportException("That doesn't contain a link.")
        if (RouteLinks.isShortGoogleLink(url)) url = expand(url) ?: throw RouteImportException("Couldn't open the Google Maps link. Check your internet.")
        val route = RouteLinks.parse(url) ?: throw RouteImportException("That link isn't a route or a place.")

        var skipped = 0
        val resolved = route.points.mapIndexed { i, lp ->
            when {
                lp.point != null -> lp.point to lp.name
                lp.name.isBlank() && i == 0 -> null to "" // start from my location
                else -> search.geocode(lp.name, near)?.let { it to lp.name } ?: run { skipped++; null to "" }
            }
        }
        val points = resolved.mapNotNull { it.first }
        val startFromMe = route.points.first().point == null && route.points.first().name.isBlank()
        if (points.isEmpty() || (points.size < 2 && !startFromMe)) throw RouteImportException("Couldn't find the places in that link.")
        val plan = if (startFromMe) RidePlan(stops = points.dropLast(1), end = points.last())
        else RidePlan(start = points.first(), stops = points.drop(1).dropLast(1), end = points.last())
        val names = resolved.filter { it.first != null && it.second.isNotBlank() }.associate { it.first!! to it.second }
        return ImportedPlan(plan.copy(stops = plan.stops.take(RidePlan.MAX_STOPS)), names, skipped)
    }

    /** Follows maps.app.goo.gl redirects to the full Google Maps link. */
    private suspend fun expand(short: String): String? = withContext(Dispatchers.IO) {
        var current = short
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 8_000; readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Sawaari/1.0")
            }
            try {
                val code = conn.responseCode
                val next = conn.getHeaderField("Location")
                if (code in 300..399 && next != null) current = URL(URL(current), next).toString()
                else return@withContext current.takeIf { RouteLinks.parse(it) != null }
            } finally {
                conn.disconnect()
            }
            if (RouteLinks.parse(current) != null) return@withContext current
        }
        current.takeIf { RouteLinks.parse(it) != null }
    }
}
