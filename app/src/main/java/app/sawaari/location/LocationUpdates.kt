package app.sawaari.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
    val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }
    providers
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { trySend(it) }
    runCatching {
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
    }
    // Fused (Android 12+) and network give a quick first fix indoors while GPS warms up.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
        if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER))
            lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
    }
    runCatching {
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs * 5, 0f, listener, Looper.getMainLooper())
    }
    awaitClose { lm.removeUpdates(listener) }
}
