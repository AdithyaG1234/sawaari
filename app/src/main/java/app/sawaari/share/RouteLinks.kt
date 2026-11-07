package app.sawaari.share

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** One point of a shared route: coordinates if known, and a name if the link had one. */
data class LinkPoint(val point: GeoPoint?, val name: String = "")

/**
 * A route read from a link. [points] run start → stops → destination. A null first point with no
 * name means "start from my location" (Google writes it as an empty segment).
 */
data class LinkRoute(val points: List<LinkPoint>)

/** Building and reading route links: Sawaari's own links and Google Maps links. Pure Kotlin for tests. */
object RouteLinks {
    const val SHARE_BASE = "https://adithyag1234.github.io/sawaari/r/"

    private val LAT_LON = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*\+?(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val URL_IN_TEXT = Regex("""https?://\S+""")

    fun fmt(p: GeoPoint) = "%.5f,%.5f".format(Locale.US, p.lat, p.lon)

    /** Sawaari link: ?s=lat,lon&w=lat,lon|lat,lon&e=lat,lon&n=start|stop1|...|end (names optional). */
    fun sawaariLink(plan: RidePlan, names: (GeoPoint) -> String?): String? {
        val end = plan.end ?: return null
        val q = StringBuilder(SHARE_BASE).append("?")
        plan.start?.let { q.append("s=").append(fmt(it)).append("&") }
        if (plan.stops.isNotEmpty()) q.append("w=").append(plan.stops.joinToString("|") { fmt(it) }).append("&")
        q.append("e=").append(fmt(end))
        val all = listOfNotNull(plan.start) + plan.stops + end
        val labels = all.map { names(it).orEmpty().replace("|", " ") }
        if (labels.any { it.isNotBlank() }) q.append("&n=").append(enc(labels.joinToString("|")))
        return q.toString()
    }

    /** Google Maps directions link (works for riders without Sawaari). Google allows up to 9 stops. */
    fun googleLink(plan: RidePlan): String? {
        val end = plan.end ?: return null
        val q = StringBuilder("https://www.google.com/maps/dir/?api=1")
        plan.start?.let { q.append("&origin=").append(fmt(it)) }
        q.append("&destination=").append(fmt(end))
        if (plan.stops.isNotEmpty()) q.append("&waypoints=").append(enc(plan.stops.take(9).joinToString("|") { fmt(it) }))
        q.append("&travelmode=driving")
        return q.toString()
    }

    /** The first link in a message (e.g. text shared from WhatsApp or Google Maps). */
    fun findUrl(text: String): String? = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', '"')

    fun isShortGoogleLink(url: String) = Regex("""^https?://(maps\.app\.goo\.gl|goo\.gl/maps)/""").containsMatchIn(url)

    /** Reads a Sawaari or (full, not short) Google Maps link. Null if it isn't a route or place link. */
    fun parse(url: String): LinkRoute? {
        val u = url.trim()
        return when {
            u.startsWith("sawaari://") || u.startsWith(SHARE_BASE) || u.contains("github.io/sawaari/r") -> parseSawaari(u)
            Regex("""google\.[a-z.]+/maps|maps\.google\.""").containsMatchIn(u) -> parseGoogle(u)
            else -> null
        }
    }

    private fun parseSawaari(u: String): LinkRoute? {
        val q = query(u)
        val s = q["s"]?.let(::latLon)
        val w = q["w"]?.split("|")?.mapNotNull(::latLon).orEmpty()
        val e = q["e"]?.let(::latLon) ?: return null
        val pts = listOfNotNull(s) + w + e
        val names = q["n"]?.split("|").orEmpty()
        val out = pts.mapIndexed { i, p -> LinkPoint(p, names.getOrElse(i) { "" }) }
        // No start in the link: start from wherever the rider is.
        return LinkRoute(if (s == null) listOf(LinkPoint(null)) + out else out)
    }

    private fun parseGoogle(u: String): LinkRoute? {
        val q = query(u)
        // https://www.google.com/maps/dir/?api=1&origin=..&destination=..&waypoints=a|b
        if (q.containsKey("destination")) {
            fun lp(v: String) = LinkPoint(latLon(v), if (latLon(v) == null) v else "")
            val origin = q["origin"]?.let(::lp) ?: LinkPoint(null)
            val stops = q["waypoints"]?.split("|")?.filter { it.isNotBlank() }?.map(::lp).orEmpty()
            return LinkRoute(listOf(origin) + stops + lp(q.getValue("destination")))
        }
        val path = u.substringAfter("://").substringAfter('/').substringBefore('?')
        val segs = path.split('/').map { dec(it) }
        val data = segs.firstOrNull { it.startsWith("data=") }.orEmpty()
        // https://www.google.com/maps/dir/Bengaluru/12.3,76.6/Madikeri/@lat,lon,zoom/data=...!1d<lon>!2d<lat>...
        val dirAt = segs.indexOf("dir")
        if (dirAt >= 0) {
            val raw = segs.drop(dirAt + 1).takeWhile { !it.startsWith("@") && !it.startsWith("data=") }
            if (raw.size < 2) return null
            val coords = Regex("""!1d(-?\d+\.\d+)!2d(-?\d+\.\d+)""").findAll(data)
                .map { GeoPoint(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }.iterator()
            val pts = raw.map { seg ->
                val name = seg.replace('+', ' ').trim()
                when {
                    name.isEmpty() -> LinkPoint(null)
                    latLon(name) != null -> LinkPoint(latLon(name))
                    else -> LinkPoint(if (coords.hasNext()) coords.next() else null, name.substringBefore(','))
                }
            }
            return LinkRoute(pts)
        }
        // A single place: /maps/place/Name/@lat,lon/data=...!3d<lat>!4d<lon>, or ?q=lat,lon / ?q=name
        val placeAt = segs.indexOf("place")
        if (placeAt >= 0) {
            val name = segs.getOrNull(placeAt + 1)?.replace('+', ' ')?.substringBefore(',').orEmpty()
            val exact = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(data)
                ?.let { GeoPoint(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            val at = segs.firstOrNull { it.startsWith("@") }?.drop(1)?.split(',')?.let { p ->
                p.getOrNull(0)?.toDoubleOrNull()?.let { lat -> p.getOrNull(1)?.toDoubleOrNull()?.let { GeoPoint(lat, it) } }
            }
            return LinkRoute(listOf(LinkPoint(null), LinkPoint(exact ?: at ?: latLon(name), name)))
        }
        val qv = q["q"] ?: q["query"] ?: q["ll"] ?: segs.getOrNull(segs.indexOf("search") + 1)?.takeIf { segs.contains("search") }
        if (qv != null) {
            val v = qv.replace('+', ' ')
            return LinkRoute(listOf(LinkPoint(null), LinkPoint(latLon(v), if (latLon(v) == null) v else "")))
        }
        return null
    }

    fun latLon(s: String): GeoPoint? {
        val m = LAT_LON.find(s.replace('+', ' ')) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
    }

    private fun query(u: String): Map<String, String> =
        u.substringAfter('?', "").substringBefore('#').split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to dec(it.substringAfter('=')) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
