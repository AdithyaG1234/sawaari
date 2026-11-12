package app.sawaari.ride

import android.content.Context
import android.location.Location
import app.sawaari.garage.Garage
import app.sawaari.geo.GeoPoint
import app.sawaari.geo.distanceMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A finished ride, as saved on the phone. */
/** [bikeId] is the garage bike the ride was recorded on (0 when none). */
data class SavedRide(val id: Long, val title: String, val track: List<TrackPoint>, val bikeId: Long = 0L) {
    val stats: RideStats by lazy { RideStats.of(track) }
}

/**
 * Records your own track while you navigate or ride with a group, and keeps it on the phone
 * (no account, no upload). A ride starts when navigation starts or you join a group, and ends
 * when both have stopped. Rides shorter than [MIN_METERS] aren't kept.
 */
object RideRecorder {
    private const val MIN_METERS = 500.0
    private const val KEEP_EVERY_METERS = 25.0
    private const val KEEP_EVERY_MS = 30_000L
    private const val SAVE_EVERY_POINTS = 20

    private var dir: File? = null
    private val reasons = mutableSetOf<String>()
    private var title = ""
    private var bikeId = 0L

    /** Runs after a ride is saved, e.g. to check whether a bike service has come due. */
    var onRideSaved: (() -> Unit)? = null
    private val current = mutableListOf<TrackPoint>()

    private val _rides = MutableStateFlow<List<SavedRide>>(emptyList())
    /** Saved rides, newest first. */
    val rides: StateFlow<List<SavedRide>> = _rides.asStateFlow()

    /** Call once at startup: loads saved rides and finishes a ride that was cut off by the app closing. */
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "rides").apply { mkdirs() }
        dir = d
        File(d, "current.json").takeIf { it.exists() }?.let { f ->
            runCatching { read(f) }.getOrNull()?.let { keep(it) }
            f.delete()
        }
        reload()
    }

    /**
     * Something that counts as riding started or stopped: "nav" (navigation) or "group" (in a ride group).
     * [name] titles the ride, e.g. the destination or the group's ride name.
     */
    fun setActive(reason: String, on: Boolean, name: String? = null) {
        if (on) {
            if (reasons.isEmpty()) { current.clear(); title = ""; bikeId = Garage.currentBikeId() }
            reasons += reason
            if (!name.isNullOrBlank() && (title.isBlank() || reason == "group")) title = name
        } else if (reasons.remove(reason) && reasons.isEmpty()) {
            finish()
        }
    }

    val recording: Boolean get() = reasons.isNotEmpty()

    /** Every GPS fix, from the map screen and the background ride service. Duplicates are ignored. */
    fun onLocation(loc: Location) {
        if (reasons.isEmpty()) return
        val p = TrackPoint(loc.latitude, loc.longitude, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
        val last = current.lastOrNull()
        if (last != null) {
            if (p.t <= last.t) return
            val far = distanceMeters(last.point, p.point) >= KEEP_EVERY_METERS
            if (!far && p.t - last.t < KEEP_EVERY_MS) return
        }
        current += p
        if (current.size % SAVE_EVERY_POINTS == 0) dir?.let { write(File(it, "current.json"), SavedRide(current.first().t, title, current.toList(), bikeId)) }
    }

    fun delete(id: Long) {
        dir?.let { File(it, "$id.json").delete() }
        reload()
    }

    private fun finish() {
        if (current.isNotEmpty()) keep(SavedRide(current.first().t, title, current.toList(), bikeId))
        current.clear()
        dir?.let { File(it, "current.json").delete() }
        reload()
        onRideSaved?.invoke()
    }

    private fun keep(r: SavedRide) {
        if (r.stats.distanceMeters < MIN_METERS) return
        dir?.let { write(File(it, "${r.id}.json"), r) }
    }

    private fun reload() {
        val d = dir ?: return
        _rides.value = d.listFiles { f -> f.name.endsWith(".json") && f.name != "current.json" }.orEmpty()
            .mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedByDescending { it.id }
    }

    private fun write(f: File, r: SavedRide) {
        val pts = JSONArray()
        r.track.forEach { pts.put(JSONArray().put(it.lat).put(it.lon).put(it.t).put(it.kmh)) }
        runCatching { f.writeText(JSONObject().put("id", r.id).put("title", r.title).put("bike", r.bikeId).put("points", pts).toString()) }
    }

    private fun read(f: File): SavedRide {
        val o = JSONObject(f.readText())
        val a = o.getJSONArray("points")
        val track = (0 until a.length()).map { i ->
            val p = a.getJSONArray(i)
            TrackPoint(p.getDouble(0), p.getDouble(1), p.getLong(2), p.getDouble(3))
        }
        return SavedRide(o.optLong("id", track.firstOrNull()?.t ?: 0L), o.optString("title"), track, o.optLong("bike"))
    }

    /** First and last point of a ride, for its list entry. */
    fun ends(r: SavedRide): Pair<GeoPoint, GeoPoint>? = r.track.firstOrNull()?.let { it.point to r.track.last().point }
}
