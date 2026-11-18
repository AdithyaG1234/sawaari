package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.routing.Route
import app.sawaari.routing.RoutingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/** Plans routes through a list of points. The iPhone asks the Valhalla server; see [app.sawaari.routing.Valhalla]. */
fun interface Router {
    suspend fun route(points: List<GeoPoint>): Route
}

/**
 * Navigation for the shared (iPhone) screens: the ride plan, the planned route, live progress,
 * voice prompts and automatic re-routing. The same behaviour as NavigationController on Android,
 * with the platform parts (routing, speech) passed in.
 */
class NavSession(
    private val router: Router,
    private val scope: CoroutineScope,
    private val speak: (String) -> Unit,
) {
    private val _plan = MutableStateFlow(RidePlan())
    /** Start (optional), stops and destination. Long-press the map to build it. */
    val plan: StateFlow<RidePlan> = _plan.asStateFlow()

    private val _labels = MutableStateFlow<Map<GeoPoint, String>>(emptyMap())
    /** Names for plan points, e.g. "Nandi Hills". */
    val labels: StateFlow<Map<GeoPoint, String>> = _labels.asStateFlow()

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    private val _progress = MutableStateFlow<NavProgress?>(null)
    val progress: StateFlow<NavProgress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Something to tell the rider, e.g. why a route couldn't be planned. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _roundTrip = MutableStateFlow(false)
    val roundTrip: StateFlow<Boolean> = _roundTrip.asStateFlow()

    private val _stopsReached = MutableStateFlow(0)
    val stopsReached: StateFlow<Int> = _stopsReached.asStateFlow()

    var myPosition: GeoPoint? = null
        private set

    /** Called when navigation reroutes (the rider left the route), so the group can be told. */
    var onReroute: (() -> Unit)? = null

    private var tracker: NavigationTracker? = null
    private val prompts = VoicePrompts()
    private var offRouteCount = 0
    private var rerouteJob: Job? = null
    private var planJob: Job? = null
    /** Stops already reached during this navigation, so a reroute only goes to the ones ahead. */
    private var stopsReachedBefore = 0

    val isRoundTrip: Boolean get() = _plan.value.let { it.end != null && (_roundTrip.value || it.end == it.start) }

    fun clearMessage() { _message.value = null }
    fun labelFor(p: GeoPoint): String? = _labels.value[p]
    fun setLabel(p: GeoPoint, name: String?) { if (!name.isNullOrBlank()) _labels.value = _labels.value + (p to name) }

    fun chooseDestination(p: GeoPoint) { _roundTrip.value = false; updatePlan { it.copy(end = p) } }

    /** Come back to the start (or to where I am now, if the start is my location). */
    fun makeRoundTrip(on: Boolean) {
        if (!on) {
            if (isRoundTrip) { _roundTrip.value = false; updatePlan { it.copy(end = null) } }
            return
        }
        val home = _plan.value.start ?: myPosition ?: run { _message.value = "Waiting for your GPS location…"; return }
        updatePlan { it.copy(end = home) }
        _roundTrip.value = true
    }

    fun moveStop(from: Int, to: Int) {
        val stops = _plan.value.stops
        if (from !in stops.indices || to !in stops.indices) return
        updatePlan { p -> p.copy(stops = p.stops.toMutableList().apply { add(to, removeAt(from)) }) }
    }

    fun setStart(p: GeoPoint?) = updatePlan { it.copy(start = p) }

    fun addStop(p: GeoPoint) {
        if (_plan.value.stops.size >= RidePlan.MAX_STOPS) { _message.value = "You can add up to ${RidePlan.MAX_STOPS} stops."; return }
        updatePlan { it.withStop(p) }
    }

    fun removeStop(index: Int) = updatePlan { it.withoutStop(index) }

    fun clearEnd() { _roundTrip.value = false; updatePlan { it.copy(end = null) } }

    /** Replace the whole plan, e.g. with the one the leader shared, and plan the route. */
    fun loadPlan(p: RidePlan) {
        if (_navigating.value) stop()
        _plan.value = p
        _roundTrip.value = false
        _route.value = null
        planRoute()
    }

    private fun updatePlan(change: (RidePlan) -> RidePlan) {
        if (_navigating.value) return
        _plan.value = change(_plan.value)
        planJob?.cancel()
        _busy.value = false
        _route.value = null
    }

    fun clear() {
        stop()
        planJob?.cancel()
        _busy.value = false
        _plan.value = RidePlan()
        _roundTrip.value = false
        _route.value = null
    }

    fun planRoute() {
        val plan = _plan.value
        if (plan.end == null) { _message.value = "Long-press the map and choose End here to set the destination."; return }
        val points = plan.waypoints(myPosition) ?: run { _message.value = "Waiting for your GPS location…"; return }
        planJob?.cancel()
        _busy.value = true
        planJob = scope.launch {
            try {
                val r = router.route(points)
                if (_plan.value == plan) _route.value = r
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutingException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Couldn't plan the route. Check your connection and try again."
            } finally {
                _busy.value = false
            }
        }
    }

    fun start() {
        val r = _route.value ?: return
        tracker = NavigationTracker(r)
        prompts.reset()
        offRouteCount = 0
        stopsReachedBefore = 0
        _stopsReached.value = 0
        _navigating.value = true
        r.steps.firstOrNull()?.let { speak(it.text) }
        myPosition?.let { onLocation(it, -1.0) }
    }

    fun stop() {
        _navigating.value = false
        _progress.value = null
        tracker = null
        rerouteJob?.cancel()
    }

    /** Every GPS fix. [accuracy] is in metres, negative when unknown. */
    fun onLocation(position: GeoPoint, accuracy: Double) {
        myPosition = position
        val t = tracker ?: return
        if (!_navigating.value) return
        val p = t.update(position)
        _progress.value = p

        if (p.arrived) {
            speak("You have arrived at your destination.")
            stop()
            _route.value = null
            _plan.value = RidePlan()
            _roundTrip.value = false
            return
        }
        val reached = stopsReachedBefore + RidePlan.stopsPassed(t.route, t.route.geometryLength - p.distanceRemaining)
        if (reached > _stopsReached.value) {
            _stopsReached.value = reached
            speak(if (reached < _plan.value.stops.size) "You've reached stop $reached." else "You've reached your last stop.")
        }

        val limit = max(OFF_ROUTE_METERS, if (accuracy > 0) accuracy * 1.5 else 0.0)
        offRouteCount = if (p.offRouteMeters > limit) offRouteCount + 1 else 0
        if (offRouteCount >= 3 && rerouteJob?.isActive != true) {
            reroute()
            return
        }

        val step = t.route.steps.getOrNull(p.nextStepIndex) ?: return
        prompts.check(p.nextStepIndex, p.distanceToNextStep, step.text)?.let { speak(it) }
    }

    private fun reroute() {
        val to = _plan.value.end ?: return
        val from = myPosition ?: return
        speak("Recalculating.")
        onReroute?.invoke()
        // Go on to the stops not yet reached, then the destination.
        val ahead = _plan.value.stops.drop(_stopsReached.value)
        rerouteJob = scope.launch {
            try {
                val r = router.route(listOf(from) + ahead + to)
                stopsReachedBefore = _stopsReached.value
                _route.value = r
                tracker = NavigationTracker(r)
                prompts.reset()
                offRouteCount = 0
                r.steps.firstOrNull()?.let { speak(it.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Couldn't recalculate the route."
                offRouteCount = 0
            }
        }
    }

    /** Speak something outside of turn instructions, e.g. an SOS from the group. */
    fun announce(text: String) = speak(text)

    companion object {
        const val OFF_ROUTE_METERS = 40.0
    }
}
