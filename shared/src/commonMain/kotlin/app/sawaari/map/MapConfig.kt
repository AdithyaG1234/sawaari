package app.sawaari.map

/**
 * Map style. OpenFreeMap serves free OpenStreetMap vector tiles with no API key.
 * Later we can self-host PMTiles (e.g. on Cloudflare R2) and only change this URL.
 */
object MapConfig {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** Default camera: centre of India. */
    const val DEFAULT_LAT = 20.59
    const val DEFAULT_LON = 78.96
    const val DEFAULT_ZOOM = 4.0
}
