package app.sawaari.sos

/**
 * Spots a likely crash: a hard jolt while riding, followed by the bike staying still.
 *  - The jolt must reach [impactG] (a phone in a pocket or on a mount sees 1-2 g on bad roads).
 *  - The rider must have been riding at [ridingKmh] or more within [ridingWindowMs] before it,
 *    so a phone dropped at a tea stop doesn't count.
 *  - After the jolt, the speed must stay under [stillKmh] for [stillMs]. Riding on cancels it.
 * Pure Kotlin for unit tests; [CrashWatch] feeds it sensor and GPS readings.
 */
class CrashLogic(
    private val impactG: Double = 4.0,
    private val ridingKmh: Double = 20.0,
    private val ridingWindowMs: Long = 30_000,
    private val stillKmh: Double = 5.0,
    private val stillMs: Long = 15_000,
) {
    private var lastRidingAt = Long.MIN_VALUE / 2
    private var impactAt: Long? = null

    val waiting: Boolean get() = impactAt != null

    /** An accelerometer reading, in g (1.0 = resting). */
    fun onAccel(t: Long, g: Double) {
        if (impactAt == null && g >= impactG && t - lastRidingAt <= ridingWindowMs) impactAt = t
    }

    /** A GPS speed reading. Returns true once, when a crash is confirmed. */
    fun onSpeed(t: Long, kmh: Double): Boolean {
        val hit = impactAt
        if (kmh >= ridingKmh) lastRidingAt = t
        if (hit == null) return false
        if (kmh > stillKmh) { impactAt = null; return false }
        if (t - hit >= stillMs) { impactAt = null; return true }
        return false
    }

    fun reset() { impactAt = null }
}
