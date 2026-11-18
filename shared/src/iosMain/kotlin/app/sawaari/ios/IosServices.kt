@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.sawaari.ios

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.Router
import app.sawaari.ride.RideFiles
import app.sawaari.routing.Route
import app.sawaari.routing.RoutingException
import app.sawaari.routing.Valhalla
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.*
import platform.Foundation.*
import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plans routes online with Valhalla's motorcycle profile (Android routes offline with GraphHopper). */
class ValhallaRouter : Router {
    override suspend fun route(points: List<GeoPoint>): Route = Valhalla.parse(post(Valhalla.URL, Valhalla.request(points)))

    private suspend fun post(url: String, body: String): String = suspendCancellableCoroutine { c ->
        val request = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
        request.setHTTPMethod("POST")
        request.setValue("application/json", forHTTPHeaderField = "Content-Type")
        request.setValue("Sawaari-iOS", forHTTPHeaderField = "User-Agent")
        request.setTimeoutInterval(30.0)
        request.setHTTPBody(NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding))
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            if (error != null) {
                c.resumeWithException(RoutingException("Couldn't reach the route server. Planning a route on iPhone needs internet for now."))
                return@dataTaskWithRequest
            }
            val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            when {
                text != null && (status == 200 || text.contains("\"error\"")) -> c.resume(text)
                status == 429 -> c.resumeWithException(RoutingException("The route server is busy. Try again in a minute."))
                else -> c.resumeWithException(RoutingException("The route server didn't answer (HTTP $status). Try again."))
            }
        }
        c.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}

/** Speaks turn instructions in an Indian English voice, lowering music while it talks. */
class Speaker {
    private val synth = AVSpeechSynthesizer()
    private var sessionReady = false

    fun speak(text: String) {
        if (!sessionReady) {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionDuckOthers, null)
            sessionReady = true
        }
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val u = AVSpeechUtterance.speechUtteranceWithString(text)
        u.voice = AVSpeechSynthesisVoice.voiceWithLanguage("en-IN") ?: AVSpeechSynthesisVoice.voiceWithLanguage("en-GB")
        synth.speakUtterance(u)
    }
}

/** Rides as JSON files in the app's Documents/rides folder, like the rides folder on Android. */
class DocumentsRideFiles : RideFiles {
    private val fm = NSFileManager.defaultManager
    private val dir: String = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/rides"

    init {
        fm.createDirectoryAtPath(dir, true, null, null)
    }

    override fun list(): List<String> = fm.contentsOfDirectoryAtPath(dir, null).orEmpty().filterIsInstance<String>()
    override fun read(name: String): String? = NSString.stringWithContentsOfFile("$dir/$name", NSUTF8StringEncoding, null)
    override fun write(name: String, text: String) {
        NSString.create(string = text).writeToFile("$dir/$name", true, NSUTF8StringEncoding, null)
    }
    override fun delete(name: String) {
        if (fm.fileExistsAtPath("$dir/$name")) fm.removeItemAtPath("$dir/$name", null)
    }
}

/** Keep the screen on while navigating. */
fun keepScreenOn(on: Boolean) {
    UIApplication.sharedApplication.idleTimerDisabled = on
}

/** E.g. "Sat 15 Nov 2026, 7:05 AM", the same as the Android ride list. */
fun formatRideDate(millis: Long): String {
    val f = NSDateFormatter()
    f.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    f.dateFormat = "EEE d MMM yyyy, h:mm a"
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}
