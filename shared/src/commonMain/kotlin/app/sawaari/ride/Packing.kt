package app.sawaari.ride

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One thing to carry on the ride. [shared] tells the group "I'm carrying this, ask me if you need it". */
data class PackItem(val id: String, val name: String, val packed: Boolean = false, val shared: Boolean = false)

/**
 * Each rider's own packing checklist for a planned ride. The list stays on the rider's phone (it
 * works offline); only the items they choose to share go to the group, at groups/{code}/gear/{uid}.
 */
object Packing {
    const val MAX_ITEMS = 60
    const val MAX_NAME = 40

    /** A starting list for a motorcycle trip; riders tick, remove or add their own. */
    val DEFAULTS = listOf(
        "Helmet", "Riding jacket", "Gloves", "Riding boots", "Driving licence", "RC, insurance and PUC",
        "Rain gear", "Puncture repair kit", "Tyre inflator", "Toolkit", "Chain lube", "First-aid kit",
        "Power bank and cables", "Water bottle", "Phone mount", "Sunscreen",
    )

    fun defaults(): List<PackItem> = DEFAULTS.mapIndexed { i, n -> PackItem("d$i", n) }

    fun add(items: List<PackItem>, name: String, newId: String): List<PackItem> {
        val n = name.trim().take(MAX_NAME)
        if (n.isEmpty() || items.size >= MAX_ITEMS || items.any { it.name.equals(n, ignoreCase = true) }) return items
        return items + PackItem(newId, n)
    }

    fun toggle(items: List<PackItem>, id: String) = items.map { if (it.id == id) it.copy(packed = !it.packed) else it }
    fun toggleShared(items: List<PackItem>, id: String) = items.map { if (it.id == id) it.copy(shared = !it.shared) else it }
    fun remove(items: List<PackItem>, id: String) = items.filterNot { it.id == id }

    /** What goes to the group: item id to name, for the shared items only. */
    fun sharedForGroup(items: List<PackItem>): Map<String, String> = items.filter { it.shared }.associate { it.id to it.name }

    /** Saved on the phone as JSON. */
    fun encode(items: List<PackItem>): String = JsonArray(items.map {
        buildJsonObject { put("id", it.id); put("name", it.name); put("packed", it.packed); put("shared", it.shared) }
    }).toString()

    fun decode(text: String?): List<PackItem>? = text?.let {
        runCatching {
            (Json.parseToJsonElement(it) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
                PackItem(str("id") ?: return@mapNotNull null, str("name") ?: return@mapNotNull null, bool("packed"), bool("shared"))
            }
        }.getOrNull()
    }
}
