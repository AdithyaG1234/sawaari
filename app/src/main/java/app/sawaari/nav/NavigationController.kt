package app.sawaari.nav

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import app.sawaari.geo.GeoPoint
import app.sawaari.routing.Route
import app.sawaari.routing.RoutingException
import app.sawaari.routing.RoutingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/**
 * Holds navigation state for the map screen: destination, the planned route,
 * live progress while riding, voice prompts and automatic re-routing.
 */
class NavigationController(
    context: Context,
    private val routing: RoutingRepository,
    private val scope: CoroutineScope,
) {
    var location by mutableStateOf<Location?>(null); private set
    /** Start (optional), stops and destination. Long-press the map to build it. */
    var plan by mutableStateOf(RidePlan()); private set
    val destination: GeoPoint? get() = plan.end
    /** Names for plan points that came from search or a shared link, e.g. "Madikeri". */
    val labels = mutableStateMapOf<GeoPoint, String>()
    fun labelFor(p: GeoPoint): String? = labels[p]
    fun setLabel(p: GeoPoint, name: String?) { if (!name.isNullOrBlank()) labels[p] = name }
    var route by mutableStateOf<Route?>(null); private set
    var navigating by mutableStateOf(false); private set
    var progress by mutableStateOf<NavProgress?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    private var tracker: NavigationTracker? = null
    private val prompts = VoicePrompts()
    private var offRouteCount = 0
    private var rerouteJob: Job? = null
    /** Stops already reached during this navigation, so a reroute only goes to the ones ahead. */
    private var stopsReachedBefore = 0
    var stopsReached by mutableStateOf(0); private set

    private var ttsReady = false
    private var ttsConfigured = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }

    val myPosition: GeoPoint? get() = location?.let { GeoPoint(it.latitude, it.longitude) }

    fun chooseDestination(p: GeoPoint) = updatePlan { it.copy(end = p) }
    fun setStart(p: GeoPoint?) = updatePlan { it.copy(start = p) }
    fun addStop(p: GeoPoint) {
        if (plan.stops.size >= RidePlan.MAX_STOPS) { message = "You can add up to ${RidePlan.MAX_STOPS} stops."; return }
        updatePlan { it.withStop(p) }
    }
    fun removeStop(index: Int) = updatePlan { it.withoutStop(index) }

    /** Add a stop as the next one: while navigating, reroute through it now; otherwise just add it. */
    fun addStopAhead(p: GeoPoint) {
        if (!navigating) { addStop(p); if (plan.end != null) planRoute(); return }
        plan = plan.copy(stops = plan.stops.take(stopsReached) + p + plan.stops.drop(stopsReached))
        reroute()
    }
    fun clearEnd() = updatePlan { it.copy(end = null) }

    /** Replace the whole plan, e.g. with the one the leader shared, and plan the route. */
    fun loadPlan(p: RidePlan) {
        if (navigating) stop()
        plan = p
        route = null
        planRoute()
    }

    private fun updatePlan(change: (RidePlan) -> RidePlan) {
        if (navigating) return
        plan = change(plan)
        route = null
    }

    fun clear() {
        stop()
        plan = RidePlan()
        route = null
    }

    fun planRoute() {
        if (plan.end == null) { message = "Long-press the map and choose End here to set the destination."; return }
        val points = plan.waypoints(myPosition) ?: run { message = "Waiting for your GPS location…"; return }
        busy = true
        scope.launch {
            try {
                route = routing.route(points)
            } catch (e: RoutingException) {
                message = e.message
            } catch (e: Exception) {
                message = "Couldn't plan the route: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun start() {
        val r = route ?: return
        tracker = NavigationTracker(r)
        prompts.reset()
        offRouteCount = 0
        stopsReachedBefore = 0
        stopsReached = 0
        navigating = true
        r.steps.firstOrNull()?.let { speak(it.text) }
        location?.let { onLocation(it) }
    }

    fun stop() {
        navigating = false
        progress = null
        tracker = null
        rerouteJob?.cancel()
        tts.stop()
    }

    fun onLocation(loc: Location) {
        location = loc
        val t = tracker ?: return
        if (!navigating) return
        val p = t.update(GeoPoint(loc.latitude, loc.longitude))
        progress = p

        if (p.arrived) {
            speak("You have arrived at your destination.")
            stop()
            route = null
            plan = RidePlan()
            return
        }
        val reached = stopsReachedBefore + RidePlan.stopsPassed(t.route, t.route.geometryLength - p.distanceRemaining)
        if (reached > stopsReached) {
            stopsReached = reached
            speak(if (reached < plan.stops.size) "You've reached stop $reached." else "You've reached your last stop.")
        }

        val limit = max(OFF_ROUTE_METERS, if (loc.hasAccuracy()) loc.accuracy.toDouble() * 1.5 else 0.0)
        offRouteCount = if (p.offRouteMeters > limit) offRouteCount + 1 else 0
        if (offRouteCount >= 3 && rerouteJob?.isActive != true) {
            reroute()
            return
        }

        val step = t.route.steps.getOrNull(p.nextStepIndex) ?: return
        prompts.check(p.nextStepIndex, p.distanceToNextStep, step.text)?.let { speak(it) }
    }

    /** Called when navigation reroutes (the rider left the route), so the group can be told. */
    var onReroute: (() -> Unit)? = null

    private fun reroute() {
        val to = plan.end ?: return
        val from = myPosition ?: return
        speak("Recalculating.")
        onReroute?.invoke()
        // Go on to the stops not yet reached, then the destination.
        val ahead = plan.stops.drop(stopsReached)
        rerouteJob = scope.launch {
            try {
                val r = routing.route(listOf(from) + ahead + to)
                stopsReachedBefore = stopsReached
                route = r
                tracker = NavigationTracker(r)
                prompts.reset()
                offRouteCount = 0
                r.steps.firstOrNull()?.let { speak(it.text) }
            } catch (e: Exception) {
                message = e.message ?: "Couldn't recalculate the route."
                offRouteCount = 0
            }
        }
    }

    /** Speak something outside of turn instructions, e.g. an SOS from the group. */
    fun announce(text: String) = speak(text)

    private fun speak(text: String) {
        if (!ttsReady) return
        if (!ttsConfigured) { runCatching { tts.language = Locale("en", "IN") }; ttsConfigured = true }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sawaari-nav")
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        const val OFF_ROUTE_METERS = 40.0
    }
}
