package app.sawaari.ios

import app.sawaari.offline.DownloadedRegion
import app.sawaari.offline.GeoBounds
import platform.UIKit.UIView

/**
 * The map and its offline downloads, implemented in Swift with MapLibre for iOS
 * (iosApp/Sawaari/MapBridge.swift) and used from the shared Compose screens.
 * Android uses MapLibre for Android directly; both draw the same OpenFreeMap style.
 */
interface IosMapBridge {
    /** The map view. Created once and reused. */
    fun mapView(): UIView

    /** Centre on the rider's location and follow it. */
    fun centerOnMe()

    /** The area of the map on screen now. */
    fun visibleBounds(): GeoBounds

    /** Get [onChange] called with every downloaded or downloading area, now and whenever one changes. */
    fun observeRegions(onChange: (List<DownloadedRegion>) -> Unit)

    /** Download [bounds] for offline use at zoom [minZoom]..[maxZoom]. */
    fun download(name: String, bounds: GeoBounds, minZoom: Double, maxZoom: Double)

    fun deleteRegion(regionId: Long)

    /** Re-start an unfinished download, e.g. after the network came back. */
    fun resumeRegion(regionId: Long)
}

/** Builds a [DownloadedRegion] from Swift (named arguments that read well from Swift). */
fun makeRegion(
    regionId: Long,
    name: String,
    completedBytes: Long,
    completedResources: Long,
    requiredResources: Long,
    isComplete: Boolean,
    error: String?,
) = DownloadedRegion(regionId, name, completedBytes, completedResources, requiredResources, isComplete, error)
