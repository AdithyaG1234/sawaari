package app.sawaari.offline

import org.maplibre.android.geometry.LatLngBounds

/** The shared ride-area boxes (see shared/.../OfflineModels.kt) as MapLibre bounds. */
fun GeoBounds.toLatLngBounds(): LatLngBounds = LatLngBounds.from(north, east, south, west)
