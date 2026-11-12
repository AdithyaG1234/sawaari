package app.sawaari.garage

import android.content.Context
import app.sawaari.ride.RideRecorder
import app.sawaari.ride.SavedRide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything in the garage: the bikes, and the one being ridden now (its rides add to its odometer). */
data class GarageState(val bikes: List<Bike> = emptyList(), val currentId: Long = 0L) {
    val current: Bike? get() = bikes.firstOrNull { it.id == currentId } ?: bikes.firstOrNull()
}

/** The rider's bikes, service jobs and papers. Kept only on this phone, in filesDir/garage.json. */
object Garage {
    private var file: File? = null
    private val _state = MutableStateFlow(GarageState())
    val state: StateFlow<GarageState> = _state.asStateFlow()

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "garage.json")
        file = f
        if (f.isFile) runCatching { _state.value = read(f.readText()) }
    }

    /** The bike a new ride is recorded on, or 0 when the garage is empty. */
    fun currentBikeId(): Long = _state.value.current?.id ?: 0L

    /** Km ridden on [bike] since it was added or its odometer was last set, from saved rides. */
    fun riddenKm(bike: Bike, rides: List<SavedRide> = RideRecorder.rides.value): Double =
        rides.filter { it.bikeId == bike.id && it.id >= bike.id }.sumOf { it.stats.distanceMeters } / 1000.0

    fun odometer(bike: Bike, rides: List<SavedRide> = RideRecorder.rides.value) = GarageLogic.odometer(bike, riddenKm(bike, rides))

    fun addBike(name: String, odoKm: Double, kind: VehicleKind = VehicleKind.MOTORCYCLE) {
        val now = System.currentTimeMillis()
        val bike = Bike(now, name.trim().ifBlank { "My ${kind.label.lowercase()}" }, odoKm, GarageLogic.defaultItems(odoKm, now, kind), kind = kind)
        save(_state.value.copy(bikes = _state.value.bikes + bike, currentId = bike.id))
    }

    fun deleteBike(id: Long) {
        val s = _state.value
        val left = s.bikes.filterNot { it.id == id }
        save(s.copy(bikes = left, currentId = if (s.currentId == id) left.firstOrNull()?.id ?: 0L else s.currentId))
    }

    fun ride(id: Long) = save(_state.value.copy(currentId = id))

    fun rename(id: Long, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    /** Sets the odometer to what the bike's meter reads now. */
    fun setOdometer(id: Long, odoKm: Double) = update(id) { it.copy(baseKm = GarageLogic.baseFor(odoKm, riddenKm(it))) }

    /** Marks a job done now, at the current odometer. */
    fun markDone(bikeId: Long, itemId: String) = update(bikeId) { b ->
        val odo = odometer(b)
        b.copy(items = b.items.map { if (it.id == itemId) it.copy(lastKm = odo, lastAt = System.currentTimeMillis()) else it })
    }

    fun saveItem(bikeId: Long, item: ServiceItem) = update(bikeId) { b ->
        b.copy(items = if (b.items.any { it.id == item.id }) b.items.map { if (it.id == item.id) item else it } else b.items + item)
    }

    fun deleteItem(bikeId: Long, itemId: String) = update(bikeId) { b -> b.copy(items = b.items.filterNot { it.id == itemId }) }

    /** Sets or clears ([expires] null) a paper's expiry date. */
    fun setPaper(bikeId: Long, paper: String, expires: Long?) = update(bikeId) { b ->
        b.copy(papers = if (expires == null) b.papers - paper else b.papers + (paper to expires))
    }

    private fun update(id: Long, change: (Bike) -> Bike) {
        val s = _state.value
        save(s.copy(bikes = s.bikes.map { if (it.id == id) change(it) else it }))
    }

    private fun save(s: GarageState) {
        _state.value = s
        file?.let { f -> runCatching { f.writeText(write(s)) } }
    }

    internal fun write(s: GarageState): String {
        val bikes = JSONArray()
        s.bikes.forEach { b ->
            val items = JSONArray()
            b.items.forEach { i ->
                items.put(
                    JSONObject().put("id", i.id).put("name", i.name).put("everyKm", i.everyKm ?: 0).put("everyDays", i.everyDays ?: 0)
                        .put("lastKm", i.lastKm).put("lastAt", i.lastAt)
                )
            }
            val papers = JSONObject().apply { b.papers.forEach { (k, v) -> put(k, v) } }
            bikes.put(JSONObject().put("id", b.id).put("name", b.name).put("baseKm", b.baseKm).put("items", items).put("papers", papers).put("kind", b.kind.name))
        }
        return JSONObject().put("current", s.currentId).put("bikes", bikes).toString()
    }

    internal fun read(text: String): GarageState {
        val o = JSONObject(text)
        val a = o.optJSONArray("bikes") ?: JSONArray()
        val bikes = (0 until a.length()).map { n ->
            val b = a.getJSONObject(n)
            val ia = b.optJSONArray("items") ?: JSONArray()
            val items = (0 until ia.length()).map { k ->
                val i = ia.getJSONObject(k)
                ServiceItem(
                    i.getString("id"), i.getString("name"),
                    i.optInt("everyKm").takeIf { it > 0 }, i.optInt("everyDays").takeIf { it > 0 },
                    i.optDouble("lastKm", 0.0), i.optLong("lastAt"),
                )
            }
            val po = b.optJSONObject("papers") ?: JSONObject()
            val papers = po.keys().asSequence().associateWith { po.getLong(it) }
            Bike(b.getLong("id"), b.getString("name"), b.optDouble("baseKm", 0.0), items, papers, VehicleKind.of(b.optString("kind")))
        }
        return GarageState(bikes, o.optLong("current"))
    }
}
