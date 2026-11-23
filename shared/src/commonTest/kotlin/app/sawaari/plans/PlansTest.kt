package app.sawaari.plans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlansTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun groupsUpToThreeAreFree() {
        val none = { _: String -> false }
        assertEquals(Plans.Seat(1, false), Plans.seatFor(emptyMap(), groupIsPro = false, mePro = false, pricingOn = true, isPro = none))
        assertEquals(Plans.Seat(2, false), Plans.seatFor(mapOf("1" to "a", "3" to "c"), false, false, pricingOn = true, isPro = none))
    }

    @Test fun pastThreeEveryoneNeedsPro() {
        val three = mapOf("1" to "a", "2" to "b", "3" to "c")
        val msg = { block: () -> Unit -> assertFailsWith<IllegalArgumentException> { block() }.message }
        // I don't have Pro.
        assertEquals(Plans.NEED_PRO_TO_BE_FOURTH, msg { Plans.seatFor(three, false, false, pricingOn = true) { true } })
        // I have Pro but rider b doesn't.
        assertEquals(Plans.OTHERS_NEED_PRO, msg { Plans.seatFor(three, false, true, pricingOn = true) { it != "b" } })
        // Everyone has Pro: I take seat 4 and the group becomes a Pro group.
        assertEquals(Plans.Seat(4, true), Plans.seatFor(three, false, true, pricingOn = true) { true })
        // In a Pro group even a free seat needs Pro.
        val pro = mapOf("1" to "a", "3" to "c", "4" to "d")
        assertEquals(Plans.NEED_PRO_GROUP_IS_BIG, msg { Plans.seatFor(pro, true, false, pricingOn = true) { true } })
        assertEquals(Plans.Seat(2, false), Plans.seatFor(pro, true, true, pricingOn = true) { true })
    }

    @Test fun garageTrialLastsTwoWeeks() {
        val start = 1_000_000L
        assertEquals(14, Plans.garageTrialDaysLeft(start, start))
        assertEquals(1, Plans.garageTrialDaysLeft(start, start + 13 * day + 1))
        assertEquals(0, Plans.garageTrialDaysLeft(start, start + 14 * day))
        assertTrue(Plans.garageOpen(start, start + 13 * day, pro = false, pricingOn = true))
        assertFalse(Plans.garageOpen(start, start + 15 * day, pro = false, pricingOn = true))
        assertTrue(Plans.garageOpen(start, start + 400 * day, pro = true, pricingOn = true))
    }

    @Test fun pricingOffUnlocksEverything() {
        val three = mapOf("1" to "a", "2" to "b", "3" to "c")
        // Nobody has Pro, yet a 4th (and 10th) rider gets a seat and the group never turns Pro.
        assertEquals(Plans.Seat(4, false), Plans.seatFor(three, false, false, pricingOn = false) { false })
        assertEquals(Plans.Seat(2, false), Plans.seatFor(mapOf("1" to "a", "3" to "c"), true, false, pricingOn = false) { false })
        // The garage stays open long after the trial.
        assertTrue(Plans.garageOpen(0L, 400 * day, pro = false, pricingOn = false))
    }

    @Test fun subscriptionNeedsAFutureEnd() {
        assertTrue(Plans.subscriptionActive(2_000L, 1_000L))
        assertFalse(Plans.subscriptionActive(500L, 1_000L))
        assertFalse(Plans.subscriptionActive(null, 1_000L))
    }
}
