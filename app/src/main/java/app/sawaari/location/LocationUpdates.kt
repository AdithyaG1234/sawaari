package app.sawaari.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS fixes from the platform LocationManager (no Google Play services needed).
 * Caller must hold a location permission.
 */
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context, intervalMs: Long = 1000L): Flow<Location> = callbackFlow {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { trySend(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { trySend(it) }
    runCatching {
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
    }
    runCatching {
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs * 5, 0f, listener, Looper.getMainLooper())
    }
    awaitClose { lm.removeUpdates(listener) }
}
