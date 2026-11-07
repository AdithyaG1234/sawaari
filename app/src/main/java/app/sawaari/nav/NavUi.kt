package app.sawaari.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.ui.graphics.vector.ImageVector
import app.sawaari.routing.Sign
import kotlin.math.roundToInt

fun iconForSign(sign: Int): ImageVector = when (sign) {
    Sign.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Sign.LEFT -> Icons.Filled.TurnLeft
    Sign.SLIGHT_LEFT, Sign.KEEP_LEFT -> Icons.Filled.TurnSlightLeft
    Sign.SLIGHT_RIGHT, Sign.KEEP_RIGHT -> Icons.Filled.TurnSlightRight
    Sign.RIGHT -> Icons.Filled.TurnRight
    Sign.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Sign.U_TURN_LEFT, Sign.U_TURN_UNKNOWN -> Icons.Filled.UTurnLeft
    Sign.U_TURN_RIGHT -> Icons.Filled.UTurnRight
    Sign.ROUNDABOUT, Sign.LEAVE_ROUNDABOUT -> Icons.Filled.RoundaboutRight
    Sign.FINISH, Sign.VIA_REACHED -> Icons.Filled.Flag
    else -> Icons.Filled.Straight
}

fun formatDistance(meters: Double): String = when {
    meters >= 10_000 -> "${(meters / 1000).roundToInt()} km"
    meters >= 1000 -> "%.1f km".format(meters / 1000)
    meters >= 100 -> "${(meters / 10).roundToInt() * 10} m"
    else -> "${meters.roundToInt()} m"
}

fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000.0).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
