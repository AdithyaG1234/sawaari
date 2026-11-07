package app.sawaari.nav

import app.sawaari.sos.MedicalInfo
import app.sawaari.sos.SosSender
import app.sawaari.sos.SosSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SosMessageTest {
    @Test fun messageWithoutLocationStillHelpful() {
        val text = SosSender.message("Adi", null, MedicalInfo("O+", ""))
        assertTrue(text.startsWith("SOS from Adi: I need help."))
        assertTrue(text.contains("not available"))
        assertTrue(text.contains("Blood group: O+."))
    }

    @Test fun phoneNumbersAreCleaned() {
        assertEquals("+919845012345", SosSettings.normalizePhone("+91 98450-12345"))
    }
}
