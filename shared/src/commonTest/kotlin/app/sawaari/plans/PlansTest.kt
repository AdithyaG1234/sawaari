package app.sawaari.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlansTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun freeGroupsHoldThreeRiders() {
        assertEquals(1, Plans.freeSeat(emptyList(), leaderIsPro = false))
        assertEquals(2, Plans.freeSeat(listOf("1", "3"), leaderIsPro = false))
        assertNull(Plans.freeSeat(listOf("1", "2", "3"), leaderIsPro = false))
        assertEquals(4, Plans.freeSeat(listOf("1", "2", "3"), leaderIsPro = true))
    }

    @Test fun garageTrialLastsTwoWeeks() {
        val start = 1_000_000L
        assertEquals(14, Plans.garageTrialDaysLeft(start, start))
        assertEquals(1, Plans.garageTrialDaysLeft(start, start + 13 * day + 1))
        assertEquals(0, Plans.garageTrialDaysLeft(start, start + 14 * day))
        assertTrue(Plans.garageOpen(start, start + 13 * day, pro = false))
        assertFalse(Plans.garageOpen(start, start + 15 * day, pro = false))
        assertTrue(Plans.garageOpen(start, start + 400 * day, pro = true))
    }

    @Test fun subscriptionNeedsAFutureEnd() {
        assertTrue(Plans.subscriptionActive(2_000L, 1_000L))
        assertFalse(Plans.subscriptionActive(500L, 1_000L))
        assertFalse(Plans.subscriptionActive(null, 1_000L))
    }
}
