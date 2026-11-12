package app.sawaari.nav

import app.sawaari.sos.CrashLogic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogicTest {
    @Test fun joltWhileRidingThenStillIsACrash() {
        val c = CrashLogic()
        assertFalse(c.onSpeed(0, 60.0))
        c.onAccel(2_000, 6.5)
        assertFalse(c.onSpeed(5_000, 0.0))
        assertFalse(c.onSpeed(10_000, 1.0))
        assertTrue(c.onSpeed(17_500, 0.0))
        // Fires once.
        assertFalse(c.onSpeed(22_500, 0.0))
    }

    @Test fun riderRidesOnAfterAPothole() {
        val c = CrashLogic()
        c.onSpeed(0, 50.0)
        c.onAccel(1_000, 4.5)
        assertFalse(c.onSpeed(5_000, 35.0))
        assertFalse(c.onSpeed(20_000, 0.0))
    }

    @Test fun phoneDroppedAtATeaStop() {
        val c = CrashLogic()
        c.onSpeed(0, 40.0)
        c.onSpeed(60_000, 0.0)
        c.onAccel(120_000, 8.0) // long after the rider stopped
        assertFalse(c.onSpeed(125_000, 0.0))
        assertFalse(c.onSpeed(140_000, 0.0))
    }

    @Test fun normalBumpsDoNothing() {
        val c = CrashLogic()
        c.onSpeed(0, 60.0)
        c.onAccel(1_000, 2.5)
        assertFalse(c.onSpeed(5_000, 0.0))
        assertFalse(c.onSpeed(30_000, 0.0))
    }
}
