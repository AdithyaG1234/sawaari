package app.sawaari.sos

import app.sawaari.group.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SosTest {
    private class Store : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun messageWithoutLocationStillHelpful() {
        val text = SosText.message("Adi", null, MedicalInfo("O+", ""))
        assertTrue(text.startsWith("SOS from Adi: I need help."))
        assertTrue(text.contains("not available"))
        assertTrue(text.contains("Blood group: O+."))
    }

    @Test fun messageWithLocation() {
        assertEquals(
            "SOS from a Sawaari rider: I need help. Location: https://maps.google.com/?q=12.97160,77.59460 (±8 m) Medical: Diabetic.",
            SosText.message("", SosFix(12.9716, 77.5946, 8, 0, "10:00"), MedicalInfo("", "Diabetic")),
        )
        assertTrue(SosText.message("Adi", SosFix(12.9716, 77.5946, null, 5, "09:55"), MedicalInfo()).endsWith("77.59460 (, from 09:55)"))
    }

    @Test fun contactsAreCleanedKeptAndCapped() {
        val store = Store()
        val sos = SosStore(store)
        sos.addContact(EmergencyContact(" Amma ", "+91 98450-12345"))
        sos.addContact(EmergencyContact("Dup", "+919845012345"))
        assertEquals(listOf(EmergencyContact("Amma", "+919845012345")), sos.contacts.value)
        repeat(10) { sos.addContact(EmergencyContact("", "98450000$it$it")) }
        assertEquals(SosStore.MAX_CONTACTS, sos.contacts.value.size)
        sos.setMedical(MedicalInfo(" B+ ", ""))
        sos.setCrashDetection(true)
        val reopened = SosStore(store)
        assertEquals(sos.contacts.value, reopened.contacts.value)
        assertEquals("B+", reopened.medical.value.bloodGroup)
        assertTrue(reopened.crashDetection.value)
        reopened.removeContact("+919845012345")
        assertEquals(SosStore.MAX_CONTACTS - 1, reopened.contacts.value.size)
    }
}
