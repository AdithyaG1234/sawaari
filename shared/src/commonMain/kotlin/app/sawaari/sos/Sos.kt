package app.sawaari.sos

import app.sawaari.group.KeyValueStore
import app.sawaari.util.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class EmergencyContact(val name: String, val phone: String)

data class MedicalInfo(val bloodGroup: String = "", val notes: String = "")

/** Where the rider was when SOS went out. [accuracyM] is null when unknown. [timeLabel] is the fix's local time, e.g. "14:05". */
data class SosFix(val lat: Double, val lon: Double, val accuracyM: Int?, val ageMinutes: Long, val timeLabel: String)

/** The SOS text, word for word the same as SosSender.message on Android. */
object SosText {
    fun message(riderName: String, fix: SosFix?, medical: MedicalInfo): String {
        val who = riderName.ifBlank { "a Sawaari rider" }
        val sb = StringBuilder("SOS from $who: I need help.")
        if (fix != null) {
            sb.append(" Location: https://maps.google.com/?q=${Text.fixed(fix.lat, 5)},${Text.fixed(fix.lon, 5)}")
            if (fix.accuracyM != null) sb.append(" (±${fix.accuracyM} m") else sb.append(" (")
            sb.append(if (fix.ageMinutes >= 2) ", from ${fix.timeLabel})" else ")")
        } else {
            sb.append(" My location is not available right now.")
        }
        if (medical.bloodGroup.isNotBlank()) sb.append(" Blood group: ${medical.bloodGroup}.")
        if (medical.notes.isNotBlank()) sb.append(" Medical: ${medical.notes}.")
        return sb.toString()
    }
}

/** Emergency contacts, medical info and the crash detection switch, stored only on this phone (like SosSettings on Android). */
class SosStore(private val store: KeyValueStore) {
    private val _contacts = MutableStateFlow(loadContacts())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts.asStateFlow()

    private val _medical = MutableStateFlow(MedicalInfo(store.getString(BLOOD).orEmpty(), store.getString(NOTES).orEmpty()))
    val medical: StateFlow<MedicalInfo> = _medical.asStateFlow()

    private val _crashDetection = MutableStateFlow(store.getString(CRASH) == "1")
    val crashDetection: StateFlow<Boolean> = _crashDetection.asStateFlow()

    fun addContact(c: EmergencyContact) {
        val clean = c.copy(name = c.name.trim().ifBlank { c.phone }, phone = normalizePhone(c.phone))
        if (clean.phone.isBlank() || _contacts.value.any { it.phone == clean.phone }) return
        save((_contacts.value + clean).take(MAX_CONTACTS))
    }

    fun removeContact(phone: String) = save(_contacts.value.filterNot { it.phone == phone })

    fun setMedical(info: MedicalInfo) {
        val clean = MedicalInfo(info.bloodGroup.trim(), info.notes.trim())
        _medical.value = clean
        store.putString(BLOOD, clean.bloodGroup)
        store.putString(NOTES, clean.notes)
    }

    fun setCrashDetection(on: Boolean) {
        _crashDetection.value = on
        store.putString(CRASH, if (on) "1" else "0")
    }

    private fun save(list: List<EmergencyContact>) {
        _contacts.value = list
        store.putString(CONTACTS, JsonArray(list.map { buildJsonObject { put("name", it.name); put("phone", it.phone) } }).toString())
    }

    private fun loadContacts(): List<EmergencyContact> = runCatching {
        (Json.parseToJsonElement(store.getString(CONTACTS) ?: "[]") as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val phone = (o["phone"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            EmergencyContact((o["name"] as? JsonPrimitive)?.contentOrNull ?: phone, phone)
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_CONTACTS = 5
        private const val CONTACTS = "sos.contacts"
        private const val BLOOD = "sos.blood"
        private const val NOTES = "sos.notes"
        private const val CRASH = "sos.crash_detection"
        fun normalizePhone(raw: String): String = raw.filter { it.isDigit() || it == '+' }
    }
}
