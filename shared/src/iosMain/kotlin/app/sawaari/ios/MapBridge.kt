package app.sawaari.ios

import app.sawaari.geo.GeoPoint
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

    /** Get [onFix] called with every new location of the rider. */
    fun observeLocation(onFix: (LocationFix) -> Unit)

    /** Keep getting locations with the screen off or in another app (while in a ride group). */
    fun setBackgroundLocation(enabled: Boolean)

    /** Show the other riders of my group on the map. */
    fun showRiders(riders: List<RiderPin>)

    /** Show the group's meeting point, or hide it with null. */
    fun showMeetingPoint(point: GeoPoint?, title: String)

    /** Move the map to a place. */
    fun moveTo(lat: Double, lon: Double, zoom: Double)

    /** Draw the planned route as a blue line, or hide it with an empty list. */
    fun showRoute(points: List<GeoPoint>)

    /** Show the ride plan's start (green S), numbered stops (yellow) and destination (red E). */
    fun showPlan(start: GeoPoint?, stops: List<GeoPoint>, end: GeoPoint?)

    /** Get [onPress] called when the rider long-presses the map, to add that place to the plan. */
    fun observeLongPress(onPress: (GeoPoint) -> Unit)

    /** Zoom the map so all of [points] are on screen, e.g. a newly planned route. */
    fun fitTo(points: List<GeoPoint>)

    /** While navigating, follow the rider closely in the direction of travel. */
    fun setNavigating(on: Boolean)
}

/**
 * A location from the phone. [speedMps], [course] and [accuracy] (metres) are negative when unknown.
 * [timeMillis] is when the fix was taken.
 */
data class LocationFix(val lat: Double, val lon: Double, val speedMps: Double, val course: Double, val accuracy: Double, val timeMillis: Long)

/** Builds a [LocationFix] from Swift. */
fun makeFix(lat: Double, lon: Double, speedMps: Double, course: Double, accuracy: Double, timeMillis: Long) =
    LocationFix(lat, lon, speedMps, course, accuracy, timeMillis)

/**
 * Another rider on the map. [kind] picks the colour: "leader", "sweep", "sos", "break" or "rider".
 * [faded] is true when their location is more than a few minutes old.
 */
data class RiderPin(
    val uid: String,
    val name: String,
    val label: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val faded: Boolean,
)

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
