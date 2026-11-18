package app.sawaari.places

import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import app.sawaari.share.RouteLinks
import app.sawaari.util.Text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A search hit: something to put on the ride plan. */
data class SearchResult(val name: String, val detail: String, val point: GeoPoint, val online: Boolean)

/**
 * Place search with Photon (free OpenStreetMap search by Komoot), the same service the Android search box asks.
 * Building the address and reading the answer are here; the phone does the download.
 */
object Photon {
    /** Places closer than this count as "nearby" and always come first. */
    const val NEARBY_METERS = 50_000.0

    fun searchUrl(query: String, near: GeoPoint?): String {
        val url = StringBuilder("https://photon.komoot.io/api/?limit=15&lang=en&q=").append(Text.urlEncode(query.trim()))
        near?.let { url.append("&lat=").append(Text.fixed(it.lat, 5)).append("&lon=").append(Text.fixed(it.lon, 5)) }
        return url.toString()
    }

    fun reverseUrl(p: GeoPoint) = "https://photon.komoot.io/reverse?lang=en&lat=${Text.fixed(p.lat, 5)}&lon=${Text.fixed(p.lon, 5)}"

    /** Coordinates typed or pasted into the search box go straight there. */
    fun coordinates(query: String): SearchResult? =
        RouteLinks.latLon(query.trim())?.let { SearchResult("Dropped pin", RouteLinks.fmt(it).replace(",", ", ") + " · coordinates", it, online = false) }

    fun parse(json: String): List<SearchResult> {
        val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull() ?: return emptyList()
        return (root["features"] as? JsonArray).orEmpty().mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val c = ((f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapNotNull null
            val lon = (c.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lat = (c.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val p = f["properties"] as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = s("name").ifBlank { s("street") }.ifBlank { return@mapNotNull null }
            val detail = listOf("city", "county", "state").map { s(it) }.filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
            SearchResult(name, detail, GeoPoint(lat, lon), online = true)
        }
    }

    /** A short name for a place from a reverse lookup, e.g. "Nandi Hills" or "Devanahalli". */
    fun placeName(json: String): String? = parse(json).firstOrNull()?.let { r ->
        r.name + (r.detail.substringBefore(",").takeIf { it.isNotBlank() && it != r.name }?.let { ", $it" } ?: "")
    }

    /**
     * Nearby results first (keeping the search's own order among them), then far ones nearest
     * first. Stops a same-named temple 300 km away from beating the one down the road. Same as Android.
     */
    fun rank(results: List<SearchResult>, near: GeoPoint?): List<SearchResult> {
        if (near == null) return results
        fun d(r: SearchResult) = distanceMeters(near, r.point).let { if (it > NEARBY_METERS) it else 0.0 }
        return results.sortedWith(compareBy({ distanceMeters(near, it.point) > NEARBY_METERS }, { d(it) }))
    }
}
