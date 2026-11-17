package app.sawaari.nav

import app.sawaari.util.Text
import kotlin.math.roundToInt

fun formatDistance(meters: Double): String = when {
    meters >= 10_000 -> "${(meters / 1000).roundToInt()} km"
    meters >= 1000 -> "${Text.fixed(meters / 1000, 1)} km"
    meters >= 100 -> "${(meters / 10).roundToInt() * 10} m"
    else -> "${meters.roundToInt()} m"
}

fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000.0).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
