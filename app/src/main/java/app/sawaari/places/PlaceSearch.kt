package app.sawaari.places

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.share.RouteLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A search hit: something to put on the ride plan. */
data class SearchResult(val name: String, val detail: String, val point: GeoPoint, val online: Boolean)

/**
 * Finds places by name. Offline it searches towns and places in the downloaded area's places file;
 * online it also asks Photon (free OpenStreetMap search by Komoot), biased towards [near].
 */
class PlaceSearch(private val places: PlacesRepository) {

    suspend fun search(query: String, near: GeoPoint?): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        // Coordinates copied from Google Maps (long-press a spot, tap the numbers): go straight there.
        RouteLinks.latLon(q)?.let { return listOf(SearchResult("Dropped pin", RouteLinks.fmt(it).replace(",", ", ") + " · coordinates", it, online = false)) }
        val offline = near?.let { offline(q, it) }.orEmpty()
        val online = runCatching { photon(q, near) }.getOrDefault(emptyList())
        // Online results first (better names and coverage), then offline ones not already listed.
        val merged = online.toMutableList()
        offline.forEach { o -> if (merged.none { it.name.equals(o.name, true) && distanceMeters(it.point, o.point) < 2_000 }) merged += o }
        return merged.take(15)
    }

    private suspend fun offline(q: String, near: GeoPoint): List<SearchResult> = withContext(Dispatchers.Default) {
        val needle = q.lowercase()
        places.placesNear(near).asSequence()
            .filter { it.name.isNotBlank() && it.name.lowercase().contains(needle) }
            .map { p ->
                val starts = p.name.lowercase().startsWith(needle)
                val score = (if (starts) 0.0 else 1_000_000.0) + (if (p.category == PlaceCategory.TOWN) 0.0 else 500_000.0) + distanceMeters(near, p.point)
                score to p
            }
            .sortedBy { it.first }
            .take(10)
            .map { (_, p) -> SearchResult(p.displayName, p.category.label + " · on this phone", p.point, online = false) }
            .toList()
    }

    private suspend fun photon(q: String, near: GeoPoint?): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = StringBuilder("https://photon.komoot.io/api/?limit=8&lang=en&q=").append(URLEncoder.encode(q, "UTF-8"))
        near?.let { url.append("&lat=").append(it.lat).append("&lon=").append(it.lon) }
        val conn = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "Sawaari/1.0 (Android; github.com/AdithyaG1234/sawaari)")
        }
        try {
            if (conn.responseCode != 200) return@withContext emptyList()
            val features = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("features") ?: return@withContext emptyList()
            (0 until features.length()).mapNotNull { i ->
                val f = features.getJSONObject(i)
                val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                val p = f.optJSONObject("properties") ?: return@mapNotNull null
                val name = p.optString("name").ifBlank { p.optString("street") }.ifBlank { return@mapNotNull null }
                val detail = listOf("city", "county", "state").map { p.optString(it) }.filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
                SearchResult(name, detail, GeoPoint(c.getDouble(1), c.getDouble(0)), online = true)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Coordinates for a place name from a shared link. Online only. */
    suspend fun geocode(name: String, near: GeoPoint?): GeoPoint? = runCatching { photon(name, near).firstOrNull()?.point }.getOrNull()
}
