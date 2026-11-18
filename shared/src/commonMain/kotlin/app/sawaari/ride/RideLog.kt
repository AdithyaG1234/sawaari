package app.sawaari.ride

import app.sawaari.geo.distanceMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A finished ride, as saved on the phone. [bikeId] is the garage bike the ride was recorded on (0 when none). */
data class SavedRide(val id: Long, val title: String, val track: List<TrackPoint>, val bikeId: Long = 0L) {
    val stats: RideStats by lazy { RideStats.of(track) }
}

/** Where rides are kept: one text file per ride, by name. The iPhone keeps them in the app's Documents folder. */
interface RideFiles {
    fun list(): List<String>
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
}

/**
 * Records your own track while you navigate or ride with a group, and keeps it on the phone
 * (no account, no upload). The same rules and file format as RideRecorder on Android:
 * a ride starts when navigation starts or you join a group, and ends when both have stopped.
 * Rides shorter than [MIN_METERS] aren't kept.
 */
class RideLog(private val files: RideFiles, private val currentBike: () -> Long = { 0L }) {
    private val reasons = mutableSetOf<String>()
    private var title = ""
    private var bikeId = 0L
    private val current = mutableListOf<TrackPoint>()

    /** Runs after a ride is saved. */
    var onRideSaved: (() -> Unit)? = null

    private val _rides = MutableStateFlow<List<SavedRide>>(emptyList())
    /** Saved rides, newest first. */
    val rides: StateFlow<List<SavedRide>> = _rides.asStateFlow()

    init {
        // Finish a ride that was cut off by the app closing.
        files.read(CURRENT)?.let { text -> runCatching { decode(text) }.getOrNull()?.let { keep(it) } }
        files.delete(CURRENT)
        reload()
    }

    /**
     * Something that counts as riding started or stopped: "nav" (navigation) or "group" (in a ride group).
     * [name] titles the ride, e.g. the destination or the group's ride name.
     */
    fun setActive(reason: String, on: Boolean, name: String? = null) {
        if (on) {
            if (reasons.isEmpty()) { current.clear(); title = ""; bikeId = currentBike() }
            reasons += reason
            if (!name.isNullOrBlank() && (title.isBlank() || reason == "group")) title = name
        } else if (reasons.remove(reason) && reasons.isEmpty()) {
            finish()
        }
    }

    val recording: Boolean get() = reasons.isNotEmpty()

    /** Every GPS fix. [kmh] is negative when unknown. Duplicates and fixes too close together are skipped. */
    fun onLocation(lat: Double, lon: Double, timeMillis: Long, kmh: Double) {
        if (reasons.isEmpty()) return
        val p = TrackPoint(lat, lon, timeMillis, kmh.coerceAtLeast(0.0))
        val last = current.lastOrNull()
        if (last != null) {
            if (p.t <= last.t) return
            val far = distanceMeters(last.point, p.point) >= KEEP_EVERY_METERS
            if (!far && p.t - last.t < KEEP_EVERY_MS) return
        }
        current += p
        if (current.size % SAVE_EVERY_POINTS == 0) files.write(CURRENT, encode(SavedRide(current.first().t, title, current.toList(), bikeId)))
    }

    fun delete(id: Long) {
        files.delete("$id.json")
        reload()
    }

    /** Saves a whole ride at once (the CI demo uses this). */
    fun add(ride: SavedRide) {
        keep(ride)
        reload()
    }

    private fun finish() {
        if (current.isNotEmpty()) keep(SavedRide(current.first().t, title, current.toList(), bikeId))
        current.clear()
        files.delete(CURRENT)
        reload()
        onRideSaved?.invoke()
    }

    private fun keep(r: SavedRide) {
        if (r.stats.distanceMeters < MIN_METERS) return
        files.write("${r.id}.json", encode(r))
    }

    private fun reload() {
        _rides.value = files.list()
            .filter { it.endsWith(".json") && it != CURRENT }
            .mapNotNull { name -> files.read(name)?.let { runCatching { decode(it) }.getOrNull() } }
            .sortedByDescending { it.id }
    }

    companion object {
        const val MIN_METERS = 500.0
        private const val KEEP_EVERY_METERS = 25.0
        private const val KEEP_EVERY_MS = 30_000L
        private const val SAVE_EVERY_POINTS = 20
        private const val CURRENT = "current.json"

        /** The same JSON as Android: {"id", "title", "bike", "points": [[lat, lon, time, km/h], ...]}. */
        fun encode(r: SavedRide): String = buildJsonObject {
            put("id", r.id)
            put("title", r.title)
            put("bike", r.bikeId)
            putJsonArray("points") {
                r.track.forEach { p -> addJsonArray { add(p.lat); add(p.lon); add(p.t); add(p.kmh) } }
            }
        }.toString()

        fun decode(text: String): SavedRide {
            val o = Json.parseToJsonElement(text) as JsonObject
            val track = (o["points"] as JsonArray).map { e ->
                val a = e.jsonArray
                TrackPoint(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.long, a[3].jsonPrimitive.double)
            }
            return SavedRide(
                id = (o["id"] as? JsonPrimitive)?.longOrNull ?: track.firstOrNull()?.t ?: 0L,
                title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                track = track,
                bikeId = (o["bike"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }
}
