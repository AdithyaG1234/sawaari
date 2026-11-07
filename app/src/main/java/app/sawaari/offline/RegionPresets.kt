package app.sawaari.offline

import org.maplibre.android.geometry.LatLngBounds

/** Popular weekend-ride areas riders can download with one tap. */
data class RegionPreset(val name: String, val bounds: LatLngBounds)

private fun box(north: Double, east: Double, south: Double, west: Double) =
    LatLngBounds.from(north, east, south, west)

val regionPresets = listOf(
    RegionPreset("Coorg & Chikmagalur", box(13.60, 76.10, 12.00, 75.30)),
    RegionPreset("Bengaluru to Nandi Hills", box(13.45, 77.80, 12.80, 77.40)),
    RegionPreset("Mumbai, Pune & Lonavala", box(19.30, 74.00, 18.40, 72.80)),
    RegionPreset("Ooty & Kodaikanal", box(11.55, 77.60, 10.15, 76.55)),
    RegionPreset("Manali to Leh", box(34.25, 78.00, 32.10, 76.90)),
    RegionPreset("Spiti Valley", box(32.60, 78.60, 31.40, 77.50)),
    RegionPreset("Goa & Konkan coast", box(16.20, 74.40, 14.80, 73.60)),
    RegionPreset("Munnar & Wayanad", box(11.90, 77.30, 9.90, 75.80)),
)
