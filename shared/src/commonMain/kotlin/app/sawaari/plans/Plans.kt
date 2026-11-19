package app.sawaari.plans

/**
 * Sawaari's free and paid plans, shared by the Android and iPhone apps.
 *
 * Free: ride groups of up to [FREE_GROUP_RIDERS] riders, and the garage for the first
 * [GARAGE_TRIAL_DAYS] days. Sawaari Pro (Rs 200 for 3 months or Rs 500 a year) lifts the group
 * limit to [PRO_GROUP_RIDERS] for groups the subscriber leads, and keeps the garage open.
 *
 * The group limit is also enforced by the Firebase rules (database.rules.json): every rider holds a
 * numbered seat at groups/{code}/slots/{n}, and seats above [FREE_GROUP_RIDERS] need the leader's
 * subscription, which only the server (functions/) can write at subs/{uid}.
 */
object Plans {
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

    fun maxRiders(leaderIsPro: Boolean) = if (leaderIsPro) PRO_GROUP_RIDERS else FREE_GROUP_RIDERS

    /** The lowest free seat number for a group, or null if it's full. [taken] are the seat numbers in use. */
    fun freeSeat(taken: Collection<String>, leaderIsPro: Boolean): Int? =
        (1..maxRiders(leaderIsPro)).firstOrNull { it.toString() !in taken }

    /** Whole days of garage trial left (0 once it's over), counting a started day as a day. */
    fun garageTrialDaysLeft(trialStart: Long, now: Long): Int {
        val left = trialStart + GARAGE_TRIAL_DAYS * DAY_MS - now
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt().coerceAtMost(GARAGE_TRIAL_DAYS)
    }

    fun garageOpen(trialStart: Long, now: Long, pro: Boolean) = pro || garageTrialDaysLeft(trialStart, now) > 0

    /** A subscription record from subs/{uid}/until is active while its end is in the future. */
    fun subscriptionActive(until: Long?, now: Long) = until != null && until > now

    const val GROUP_FULL_MESSAGE =
        "This group is full. Free groups take up to $FREE_GROUP_RIDERS riders. " +
            "The leader can get Sawaari Pro (from $PRICE_QUARTERLY for 3 months) for bigger groups."
}
