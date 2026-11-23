package app.sawaari.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatTest {
    @Test fun distancesReadNaturally() {
        assertEquals("42 m", formatDistance(42.4))
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("1.5 km", formatDistance(1_480.0))
        assertEquals("28 km", formatDistance(27_600.0))
    }

    @Test fun durationsReadNaturally() {
        assertEquals("0 min", formatDuration(10_000))
        assertEquals("45 min", formatDuration(45 * 60_000L))
        assertEquals("2 h 5 min", formatDuration((2 * 60 + 5) * 60_000L))
    }
}

class VoicePromptsTest {
    @Test fun speaksAtOneKmThreeHundredMetresAndAtTheTurn() {
        val v = VoicePrompts()
        assertNull(v.check(0, 2_000.0, "Turn left onto NH66"))
        assertEquals("In 1 kilometre, turn left onto NH66", v.check(0, 990.0, "Turn left onto NH66"))
        assertNull(v.check(0, 800.0, "Turn left onto NH66")) // not twice
        assertEquals("In 300 metres, turn left onto NH66", v.check(0, 290.0, "Turn left onto NH66"))
        assertEquals("Turn left onto NH66", v.check(0, 30.0, "Turn left onto NH66"))
        assertNull(v.check(0, 10.0, "Turn left onto NH66"))
    }

    @Test fun eachStepHasItsOwnPromptsAndResetStartsOver() {
        val v = VoicePrompts()
        assertEquals("Turn right", v.check(1, 20.0, "Turn right"))
        // Starting close to the next turn goes straight to the nearest prompt.
        assertEquals("In 250 metres, keep left", v.check(2, 260.0, "Keep left"))
        v.reset()
        assertEquals("Turn right", v.check(1, 20.0, "Turn right"))
    }

    @Test fun spokenDistancesAreRounded() {
        assertEquals("1 kilometre", VoicePrompts.spokenDistance(1_020.0))
        assertEquals("1.5 kilometres", VoicePrompts.spokenDistance(1_480.0))
        assertEquals("2 kilometres", VoicePrompts.spokenDistance(2_010.0))
        assertEquals("300 metres", VoicePrompts.spokenDistance(310.0))
        assertEquals("10 metres", VoicePrompts.spokenDistance(2.0))
    }
}
