package app.sawaari.garage

import app.sawaari.ride.SavedRide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Everything in the garage: the bikes, and the one being ridden now (its rides add to its odometer). */
data class GarageState(val bikes: List<Bike> = emptyList(), val currentId: Long = 0L) {
    val current: Bike? get() = bikes.firstOrNull { it.id == currentId } ?: bikes.firstOrNull()
}

/** Something due or due soon across the garage, for a badge or a reminder. */
data class Reminder(val key: String, val bike: String, val what: String, val status: Status)

/**
 * The rider's bikes, service jobs and papers, kept only on this phone. The same rules and garage.json format
 * as Garage on Android. [load] and [save] read and write the file; [rides] gives the saved rides, whose
 * distance adds to each bike's odometer.
 */
class GarageBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val rides: () -> List<SavedRide>,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow(load()?.let { runCatching { read(it) }.getOrNull() } ?: GarageState())
    val state: StateFlow<GarageState> = _state.asStateFlow()

    /** The bike a new ride is recorded on, or 0 when the garage is empty. */
    fun currentBikeId(): Long = _state.value.current?.id ?: 0L

    /** Km ridden on [bike] since it was added or its odometer was last set, from saved rides. */
    fun riddenKm(bike: Bike, rideList: List<SavedRide> = rides()): Double =
        rideList.filter { it.bikeId == bike.id && it.id >= bike.id }.sumOf { it.stats.distanceMeters } / 1000.0

    fun odometer(bike: Bike, rideList: List<SavedRide> = rides()) = GarageLogic.odometer(bike, riddenKm(bike, rideList))

    fun addBike(name: String, odoKm: Double, kind: VehicleKind = VehicleKind.MOTORCYCLE) {
        val t = now()
        val bike = Bike(t, name.trim().ifBlank { "My ${kind.label.lowercase()}" }, odoKm, GarageLogic.defaultItems(odoKm, t, kind), kind = kind)
        put(_state.value.copy(bikes = _state.value.bikes + bike, currentId = bike.id))
    }

    fun deleteBike(id: Long) {
        val s = _state.value
        val left = s.bikes.filterNot { it.id == id }
        put(s.copy(bikes = left, currentId = if (s.currentId == id) left.firstOrNull()?.id ?: 0L else s.currentId))
    }

    fun ride(id: Long) = put(_state.value.copy(currentId = id))

    fun rename(id: Long, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    /** Sets the odometer to what the bike's meter reads now. */
    fun setOdometer(id: Long, odoKm: Double) = update(id) { it.copy(baseKm = GarageLogic.baseFor(odoKm, riddenKm(it))) }

    /** Marks a job done now, at the current odometer. */
    fun markDone(bikeId: Long, itemId: String) = update(bikeId) { b ->
        val odo = odometer(b)
        b.copy(items = b.items.map { if (it.id == itemId) it.copy(lastKm = odo, lastAt = now()) else it })
    }

    fun saveItem(bikeId: Long, item: ServiceItem) = update(bikeId) { b ->
        b.copy(items = if (b.items.any { it.id == item.id }) b.items.map { if (it.id == item.id) item else it } else b.items + item)
    }

    fun deleteItem(bikeId: Long, itemId: String) = update(bikeId) { b -> b.copy(items = b.items.filterNot { it.id == itemId }) }

    /** Sets or clears ([expires] null) a paper's expiry date. */
    fun setPaper(bikeId: Long, paper: String, expires: Long?) = update(bikeId) { b ->
        b.copy(papers = if (expires == null) b.papers - paper else b.papers + (paper to expires))
    }

    /** Everything due or due soon, like GarageReminders.all on Android. */
    fun reminders(t: Long = now()): List<Reminder> = _state.value.bikes.flatMap { b ->
        val odo = odometer(b)
        b.items.map { i -> Reminder("${b.id}:${i.id}:${i.lastAt}", b.name, i.name, GarageLogic.status(i, odo, t)) } +
            b.papers.filter { b.kind.hasPapers }.map { (p, exp) -> Reminder("${b.id}:$p:$exp", b.name, p, GarageLogic.paperStatus(exp, t)) }
    }.filter { it.status.level != Level.OK }

    private fun update(id: Long, change: (Bike) -> Bike) {
        val s = _state.value
        put(s.copy(bikes = s.bikes.map { if (it.id == id) change(it) else it }))
    }

    private fun put(s: GarageState) {
        _state.value = s
        runCatching { save(write(s)) }
    }

    companion object {
        fun write(s: GarageState): String = buildJsonObject {
            put("current", s.currentId)
            putJsonArray("bikes") {
                s.bikes.forEach { b ->
                    add(buildJsonObject {
                        put("id", b.id); put("name", b.name); put("baseKm", b.baseKm); put("kind", b.kind.name)
                        putJsonArray("items") {
                            b.items.forEach { i ->
                                add(buildJsonObject {
                                    put("id", i.id); put("name", i.name); put("everyKm", i.everyKm ?: 0); put("everyDays", i.everyDays ?: 0)
                                    put("lastKm", i.lastKm); put("lastAt", i.lastAt)
                                })
                            }
                        }
                        putJsonObject("papers") { b.papers.forEach { (k, v) -> put(k, v) } }
                    })
                }
            }
        }.toString()

        fun read(text: String): GarageState {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
            fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
            fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
            val bikes = (o["bikes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { b ->
                val items = (b["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { i ->
                    ServiceItem(
                        i.str("id").orEmpty(), i.str("name").orEmpty(),
                        i.int("everyKm")?.takeIf { it > 0 }, i.int("everyDays")?.takeIf { it > 0 },
                        i.dbl("lastKm") ?: 0.0, i.long("lastAt") ?: 0L,
                    )
                }
                val papers = (b["papers"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.let { k to it }
                }.toMap()
                Bike(b.long("id") ?: 0L, b.str("name").orEmpty(), b.dbl("baseKm") ?: 0.0, items, papers, VehicleKind.of(b.str("kind")))
            }
            return GarageState(bikes, o.long("current") ?: 0L)
        }
    }
}
