package app.sawaari.routing

import app.sawaari.geo.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Online routing with Valhalla (the free OpenStreetMap server run by FOSSGIS), for the iPhone app.
 * Android routes offline with GraphHopper, which can't run on iOS. Valhalla has a motorcycle profile,
 * and its answer is turned into the same [Route] the Android app uses, so navigation works the same way.
 */
object Valhalla {
    const val URL = "https://valhalla1.openstreetmap.de/route"

    /** The request body for a route through [points] in order: start, stops, destination. */
    fun request(points: List<GeoPoint>): String = buildJsonObject {
        putJsonArray("locations") {
            points.forEach { p -> addJsonObject { put("lat", p.lat); put("lon", p.lon); put("type", "break") } }
        }
        put("costing", "motorcycle")
        putJsonObject("directions_options") { put("units", "kilometers"); put("language", "en-US") }
    }.toString()

    /** Turns Valhalla's answer into a [Route]. Throws [RoutingException] with a rider-friendly message. */
    fun parse(json: String): Route {
        val root = runCatching { Json.parseToJsonElement(json) as JsonObject }.getOrNull()
            ?: throw RoutingException("The route server sent an unexpected answer. Try again.")
        root.str("error")?.let { error ->
            val code = root.int("error_code")
            throw RoutingException(
                when {
                    code == 171 || error.contains("No suitable edges", ignoreCase = true) ->
                        "No road found near the start, a stop or the destination."
                    code == 442 || error.contains("No path could be found", ignoreCase = true) -> "No route found between these places."
                    code == 154 || error.contains("exceeds the max distance", ignoreCase = true) ->
                        "This trip is too long to plan in one go. Add it in shorter parts."
                    else -> "Couldn't plan the route: $error"
                }
            )
        }
        val trip = root["trip"] as? JsonObject ?: throw RoutingException("No route found between these places.")
        val legs = (trip["legs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (legs.isEmpty()) throw RoutingException("No route found between these places.")

        val points = mutableListOf<GeoPoint>()
        val steps = mutableListOf<Pair<Int, RawStepAt>>()
        legs.forEachIndexed { legIndex, leg ->
            val shape = decodePolyline6(leg.str("shape").orEmpty())
            // Each leg starts where the last one ended: don't repeat that point.
            val offset = if (points.isEmpty()) 0 else points.size - 1
            points += if (points.isEmpty()) shape else shape.drop(1)
            val lastLeg = legIndex == legs.lastIndex
            (leg["maneuvers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { m ->
                val type = m.int("type") ?: 0
                val sign = sign(type, lastLeg)
                // A leg's start (after a stop) repeats where the last leg arrived, so skip it.
                if (legIndex > 0 && type in START_TYPES) return@forEach
                val text = when (sign) {
                    Sign.VIA_REACHED -> "You've reached stop ${legIndex + 1}"
                    else -> m.str("instruction").orEmpty().trim().removeSuffix(".")
                }
                val street = (m["street_names"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val index = (offset + (m.int("begin_shape_index") ?: 0)).coerceIn(0, (points.size - 1).coerceAtLeast(0))
                steps += index to RawStepAt(text, sign, street)
            }
        }
        if (points.size < 2) throw RoutingException("No route found between these places.")
        val summary = trip["summary"] as? JsonObject
        val km = summary?.num("length") ?: 0.0
        val seconds = summary?.num("time") ?: 0.0
        val route = Route(points, emptyList(), km * 1000, (seconds * 1000).toLong())
        return Route(
            points,
            steps.map { (i, s) -> RouteStep(s.text, s.sign, s.street, i, route.cumulative[i]) },
            route.distanceMeters,
            route.timeMillis,
        )
    }

    private data class RawStepAt(val text: String, val sign: Int, val street: String)

    private val START_TYPES = setOf(1, 2, 3)

    /** Valhalla's maneuver type as the GraphHopper [Sign] the navigation code uses. */
    fun sign(type: Int, lastLeg: Boolean): Int = when (type) {
        4, 5, 6 -> if (lastLeg) Sign.FINISH else Sign.VIA_REACHED
        9 -> Sign.SLIGHT_RIGHT
        10 -> Sign.RIGHT
        11 -> Sign.SHARP_RIGHT
        12 -> Sign.U_TURN_RIGHT
        13 -> Sign.U_TURN_LEFT
        14 -> Sign.SHARP_LEFT
        15 -> Sign.LEFT
        16 -> Sign.SLIGHT_LEFT
        18, 20, 23 -> Sign.KEEP_RIGHT
        19, 21, 24 -> Sign.KEEP_LEFT
        26 -> Sign.ROUNDABOUT
        27 -> Sign.LEAVE_ROUNDABOUT
        else -> Sign.CONTINUE
    }

    /** Decodes Valhalla's shape: a Google encoded polyline with 6 decimal places. */
    fun decodePolyline6(encoded: String): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (index < encoded.length) {
                val b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += next()
            if (index >= encoded.length) break
            lon += next()
            out += GeoPoint(lat / 1e6, lon / 1e6)
        }
        return out
    }

    /** Encodes points the way [decodePolyline6] reads them (used by tests and the CI demo). */
    fun encodePolyline6(points: List<GeoPoint>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x.toInt() + 63).toChar())
        }
        points.forEach { p ->
            val lat = kotlin.math.round(p.lat * 1e6).toLong()
            val lon = kotlin.math.round(p.lon * 1e6).toLong()
            put(lat - lastLat); put(lon - lastLon)
            lastLat = lat; lastLon = lon
        }
        return sb.toString()
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

