package app.sawaari.sos

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(val name: String, val phone: String)

data class MedicalInfo(val bloodGroup: String = "", val notes: String = "")

/** Emergency contacts and medical info, stored only on this phone. */
class SosSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sos", Context.MODE_PRIVATE)

    private val _contacts = MutableStateFlow(loadContacts())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts.asStateFlow()

    private val _medical = MutableStateFlow(
        MedicalInfo(prefs.getString("blood", "").orEmpty(), prefs.getString("notes", "").orEmpty())
    )
    val medical: StateFlow<MedicalInfo> = _medical.asStateFlow()

    fun addContact(c: EmergencyContact) {
        val clean = c.copy(name = c.name.trim().ifBlank { c.phone }, phone = normalizePhone(c.phone))
        if (clean.phone.isBlank() || _contacts.value.any { it.phone == clean.phone }) return
        save((_contacts.value + clean).take(MAX_CONTACTS))
    }

    fun removeContact(phone: String) = save(_contacts.value.filterNot { it.phone == phone })

    fun setMedical(info: MedicalInfo) {
        _medical.value = info
        prefs.edit().putString("blood", info.bloodGroup.trim()).putString("notes", info.notes.trim()).apply()
    }

    private fun save(list: List<EmergencyContact>) {
        _contacts.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }

    private fun loadContacts(): List<EmergencyContact> = runCatching {
        val arr = JSONArray(prefs.getString("contacts", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> EmergencyContact(o.getString("name"), o.getString("phone")) } }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_CONTACTS = 5
        fun normalizePhone(raw: String): String = raw.filter { it.isDigit() || it == '+' }
    }
}
