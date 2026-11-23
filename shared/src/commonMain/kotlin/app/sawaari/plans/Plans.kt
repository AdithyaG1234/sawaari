package app.sawaari.plans

/**
 * Sawaari's free and paid plans, shared by the Android and iPhone apps.
 *
 * Free: ride groups of up to [FREE_GROUP_RIDERS] riders, and the garage for the first
 * [GARAGE_TRIAL_DAYS] days. A group with more than [FREE_GROUP_RIDERS] riders is a Pro group:
 * every rider in it, the first three included, needs their own Sawaari Pro (Rs 200 for 3 months
 * or Rs 500 a year). Pro also keeps the garage open.
 *
 * The rules are also enforced by Firebase (database.rules.json): every rider holds a numbered seat
 * at groups/{code}/slots/{n}. Taking seat 4 turns on groups/{code}/pro, which needs the riders in
 * seats 1-3 and the new rider to have Pro; once it's on, every new rider needs Pro. Only the server
 * (functions/) can write the subscription records at subs/{uid}.
 *
 * Live trial: while [PRICING_ON] is false every feature is free for everyone: groups of any size
 * (up to [PRO_GROUP_RIDERS]) and the garage with no time limit, and no Pro prompts are shown. The
 * database rules follow the same switch through config/pricing (missing or false = free).
 */
object Plans {
    /**
     * Pricing and the 3-rider limit. Off for the Goa live trial (Nov 2026): everything is unlocked.
     * To turn pricing back on: set this to true, release the app, and set config/pricing to true in
     * the Firebase Realtime Database (Data tab), so the server rules enforce seats again too.
     */
    const val PRICING_ON = false

    const val FREE_GROUP_RIDERS = 3
    const val PRO_GROUP_RIDERS = 99
    const val GARAGE_TRIAL_DAYS = 14
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** The Play Store subscription and its two base plans. */
    const val PRODUCT_ID = "sawaari_pro"
    const val PLAN_QUARTERLY = "quarterly"
    const val PLAN_YEARLY = "yearly"

    /** Prices shown until the store sends its own (localised) ones. */
    const val PRICE_QUARTERLY = "₹200"
    const val PRICE_YEARLY = "₹500"
    private const val PRICES = "$PRICE_QUARTERLY for 3 months or $PRICE_YEARLY a year"

    const val NEED_PRO_GROUP_IS_BIG =
        "This group has more than $FREE_GROUP_RIDERS riders, so everyone in it needs Sawaari Pro ($PRICES). " +
            "Get Pro, then join again with the same code."
    const val NEED_PRO_TO_BE_FOURTH =
        "This group already has $FREE_GROUP_RIDERS riders. Groups of up to $FREE_GROUP_RIDERS are free; " +
            "bigger groups need Sawaari Pro ($PRICES) for every rider, including you."
    const val OTHERS_NEED_PRO =
        "To add a 4th rider, everyone already in the group needs Sawaari Pro too. " +
            "Ask the riders without it to get Pro ($PRICES), then join again."
    const val GROUP_FULL = "This group is full ($PRO_GROUP_RIDERS riders)."

    /** Messages that a Pro subscription can fix, so the app can offer "Get Pro" next to them. */
    val PRO_MESSAGES = setOf(NEED_PRO_GROUP_IS_BIG, NEED_PRO_TO_BE_FOURTH, OTHERS_NEED_PRO)

    /** Where a joining rider sits: [seat], and [makePro] when this rider is the one who takes the group past 3. */
    data class Seat(val seat: Int, val makePro: Boolean)

    /**
     * Pick a seat for a rider joining a group.
     * [seats] maps seat number to the rider in it, [groupIsPro] is groups/{code}/pro, [mePro] is my own
     * subscription, and [isPro] answers for another rider. Throws IllegalArgumentException with a message
     * to show when the rider can't join.
     */
    fun seatFor(
        seats: Map<String, String>, groupIsPro: Boolean, mePro: Boolean,
        pricingOn: Boolean = PRICING_ON, isPro: (String) -> Boolean,
    ): Seat {
        val free = (1..PRO_GROUP_RIDERS).firstOrNull { it.toString() !in seats } ?: throw IllegalArgumentException(GROUP_FULL)
        if (!pricingOn) return Seat(free, makePro = false)
        if (groupIsPro) {
            if (!mePro) throw IllegalArgumentException(NEED_PRO_GROUP_IS_BIG)
            return Seat(free, makePro = false)
        }
        if (free <= FREE_GROUP_RIDERS) return Seat(free, makePro = false)
        if (!mePro) throw IllegalArgumentException(NEED_PRO_TO_BE_FOURTH)
        val firstThree = (1..FREE_GROUP_RIDERS).mapNotNull { seats[it.toString()] }
        if (!firstThree.all(isPro)) throw IllegalArgumentException(OTHERS_NEED_PRO)
        return Seat(free, makePro = true)
    }

    /** Whole days of garage trial left (0 once it's over), counting a started day as a day. */
    fun garageTrialDaysLeft(trialStart: Long, now: Long): Int {
        val left = trialStart + GARAGE_TRIAL_DAYS * DAY_MS - now
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt().coerceAtMost(GARAGE_TRIAL_DAYS)
    }

    fun garageOpen(trialStart: Long, now: Long, pro: Boolean, pricingOn: Boolean = PRICING_ON) =
        !pricingOn || pro || garageTrialDaysLeft(trialStart, now) > 0

    /** A subscription record from subs/{uid}/until is active while its end is in the future. */
    fun subscriptionActive(until: Long?, now: Long) = until != null && until > now
}
