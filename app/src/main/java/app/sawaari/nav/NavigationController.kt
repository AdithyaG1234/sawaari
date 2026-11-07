package app.sawaari.nav

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    var destination by mutableStateOf<GeoPoint?>(null); private set
    var route by mutableStateOf<Route?>(null); private set
    var navigating by mutableStateOf(false); private set
    var progress by mutableStateOf<NavProgress?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    private var tracker: NavigationTracker? = null
    private val prompts = VoicePrompts()
    private var offRouteCount = 0
    private var rerouteJob: Job? = null

    private var ttsReady = false
    private var ttsConfigured = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }

    val myPosition: GeoPoint? get() = location?.let { GeoPoint(it.latitude, it.longitude) }

    fun setDestination(p: GeoPoint) {
        if (navigating) return
        destination = p
        route = null
    }

    fun clear() {
        stop()
        destination = null
        route = null
    }

    fun planRoute() {
        val to = destination ?: return
        val from = myPosition ?: run { message = "Waiting for your GPS location…"; return }
        busy = true
        scope.launch {
            try {
                route = routing.route(from, to)
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
            destination = null
            return
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

    private fun reroute() {
        val to = destination ?: return
        val from = myPosition ?: return
        speak("Recalculating.")
        rerouteJob = scope.launch {
            try {
                val r = routing.route(from, to)
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
