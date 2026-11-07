package app.sawaari.nav

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Decides when to speak each instruction: about 1 km before, about 300 m before,
 * and just before the turn. Each prompt is spoken at most once per step.
 */
class VoicePrompts {
    private val thresholds = listOf(1000.0, 300.0, 40.0)
    private val spoken = mutableMapOf<Int, Double>() // step -> smallest threshold already spoken

    fun reset() = spoken.clear()

    /** Returns the sentence to speak now, or null. */
    fun check(stepIndex: Int, distance: Double, instruction: String): String? {
        val threshold = thresholds.lastOrNull { distance <= it } ?: return null
        val already = spoken[stepIndex]
        if (already != null && already <= threshold) return null
        spoken[stepIndex] = threshold
        return if (threshold == thresholds.last()) instruction
        else "In ${spokenDistance(distance)}, ${instruction.replaceFirstChar { it.lowercase(Locale.ENGLISH) }}"
    }

    companion object {
        fun spokenDistance(meters: Double): String = when {
            meters >= 950 -> {
                val km = (meters / 100).roundToInt() / 10.0
                if (km == 1.0) "1 kilometre" else "${if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()} kilometres"
            }
            meters >= 100 -> "${((meters / 50).roundToInt() * 50)} metres"
            else -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(10)} metres"
        }
    }
}
