package app.sawaari.offline

import app.sawaari.util.Text

/** A map area: north/east/south/west edges in degrees. */
data class GeoBounds(val north: Double, val east: Double, val south: Double, val west: Double)

/** UI-facing snapshot of one downloaded (or downloading) map region. */
data class DownloadedRegion(
    val id: Long,
    val name: String,
    val completedBytes: Long,
    val completedResources: Long,
    val requiredResources: Long,
    val isComplete: Boolean,
    val error: String? = null,
) {
    val progress: Float
        get() = if (requiredResources == 0L) 0f
        else (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f)
}

/** Popular weekend-ride areas riders can download with one tap. */
data class RegionPreset(val name: String, val bounds: GeoBounds)

val regionPresets = listOf(
    RegionPreset("Coorg & Chikmagalur", GeoBounds(13.60, 76.10, 12.00, 75.30)),
    RegionPreset("Bengaluru to Nandi Hills", GeoBounds(13.45, 77.80, 12.80, 77.40)),
    RegionPreset("Mumbai, Pune & Lonavala", GeoBounds(19.30, 74.00, 18.40, 72.80)),
    RegionPreset("Ooty & Kodaikanal", GeoBounds(11.55, 77.60, 10.15, 76.55)),
    RegionPreset("Manali to Leh", GeoBounds(34.25, 78.00, 32.10, 76.90)),
    RegionPreset("Spiti Valley", GeoBounds(32.60, 78.60, 31.40, 77.50)),
    RegionPreset("Goa & Konkan coast", GeoBounds(16.20, 74.40, 14.80, 73.60)),
    RegionPreset("Munnar & Wayanad", GeoBounds(11.90, 77.30, 9.90, 75.80)),
    RegionPreset("Gokarna, Udupi & Mangaluru", GeoBounds(14.65, 75.20, 12.75, 74.20)),
    RegionPreset("Kochi, Alleppey & Varkala", GeoBounds(10.10, 76.60, 8.65, 76.15)),
    RegionPreset("Chennai, Mahabalipuram & Pondicherry", GeoBounds(13.20, 80.35, 11.85, 79.70)),
    RegionPreset("Hyderabad to Srisailam", GeoBounds(17.55, 79.00, 16.00, 78.30)),
    RegionPreset("Rishikesh, Mussoorie & Chopta", GeoBounds(30.70, 79.30, 29.90, 77.90)),
    RegionPreset("Jaipur & Pushkar", GeoBounds(27.10, 76.10, 26.35, 74.50)),
    RegionPreset("Udaipur & Mount Abu", GeoBounds(25.00, 74.00, 24.35, 72.60)),
    RegionPreset("Srinagar, Gulmarg & Pahalgam", GeoBounds(34.40, 75.40, 33.90, 74.30)),
    RegionPreset("Gangtok & North Sikkim", GeoBounds(27.95, 88.85, 27.10, 88.30)),
    RegionPreset("Shillong, Cherrapunji & Dawki", GeoBounds(25.70, 92.10, 25.15, 91.55)),
)

/** Short file size like Android's Formatter.formatShortFileSize: "850 kB", "12 MB", "1.4 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var v = bytes / 1000.0
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
    val num = if (v < 10) Text.fixed(v, 1).removeSuffix(".0") else Text.fixed(v, 0)
    return "$num ${units[i]}"
}
