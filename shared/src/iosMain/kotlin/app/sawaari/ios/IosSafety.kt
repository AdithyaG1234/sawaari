@file:OptIn(ExperimentalForeignApi::class)

package app.sawaari.ios

import app.sawaari.geo.GeoPoint
import app.sawaari.places.Photon
import app.sawaari.places.SearchResult
import app.sawaari.share.LinkImport
import app.sawaari.share.LinkPlan
import app.sawaari.sos.CrashLogic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.MessageUI.MFMessageComposeViewController
import platform.MessageUI.MFMessageComposeViewControllerDelegateProtocol
import platform.MessageUI.MessageComposeResult
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import kotlin.math.sqrt

/** Place search for the search box: coordinates, then Photon online, nearest first. iPhone has no offline places file yet. */
object IosPlaceSearch {
    suspend fun search(query: String, near: GeoPoint?): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        Photon.coordinates(q)?.let { return listOf(it) }
        val answer = httpGet(Photon.searchUrl(q, near))
        if (answer.status != 200) throw IllegalStateException("Search needs internet. Check your connection.")
        return Photon.rank(Photon.parse(answer.text.orEmpty()), near).take(15)
    }

    /** A name for a long-pressed place, e.g. "Nandi Hills, Chikkaballapura". Null offline. */
    suspend fun nameOf(p: GeoPoint): String? = runCatching {
        httpGet(Photon.reverseUrl(p), 6.0).takeIf { it.status == 200 }?.text?.let(Photon::placeName)
    }.getOrNull()

    /** Reads a Sawaari or Google Maps link (short links too) into a ride plan. */
    suspend fun importLink(text: String, near: GeoPoint?): LinkPlan = LinkImport.import(
        text, near,
        expand = { url -> httpGet(url).finalUrl },
        geocode = { name, around -> runCatching { search(name, around).firstOrNull()?.point }.getOrNull() },
    )
}

/** Links opened from outside the app (sawaari://route?...), handed over by the Swift app. */
object IncomingLinks {
    internal val pending = MutableStateFlow<String?>(null)
}

/** Called from Swift when iOS opens the app with a link. */
@Suppress("unused")
fun openLink(url: String) {
    IncomingLinks.pending.value = url
}

/** The text on the clipboard, e.g. a Google Maps link the rider copied. iOS may ask the rider to allow pasting. */
fun clipboardText(): String? = UIPasteboard.generalPasteboard.string

/** "14:05" for the SOS text. */
fun clockTime(millis: Long): String {
    val f = NSDateFormatter()
    f.dateFormat = "HH:mm"
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}

/**
 * Texts the SOS to emergency contacts. iPhone apps can't send SMS by themselves: this opens the Messages
 * screen filled in, and the rider taps Send. The ride group alert goes out on its own before this.
 */
object SmsComposer {
    private var delegate: NSObject? = null

    val canSend: Boolean get() = MFMessageComposeViewController.canSendText()

    /** [onDone] gets true when sent, false when cancelled or failed. */
    fun compose(phones: List<String>, body: String, onDone: (Boolean) -> Unit) {
        val top = topViewController() ?: return onDone(false)
        val vc = MFMessageComposeViewController()
        vc.recipients = phones
        vc.body = body
        val d = object : NSObject(), MFMessageComposeViewControllerDelegateProtocol {
            override fun messageComposeViewController(controller: MFMessageComposeViewController, didFinishWithResult: MessageComposeResult) {
                controller.dismissViewControllerAnimated(true, null)
                delegate = null
                onDone(didFinishWithResult == MessageComposeResult.MessageComposeResultSent)
            }
        }
        delegate = d
        vc.messageComposeDelegate = d
        top.presentViewController(vc, animated = true, completion = null)
    }
}

internal fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (top.presentedViewController != null) top = top.presentedViewController!!
    return top
}

/**
 * Crash detection with the iPhone's motion sensor and GPS speed, using the same [CrashLogic] as Android:
 * a hard jolt while riding, then the bike staying still. Works while the app is open or riding in the background.
 */
class CrashMonitor(private val onCrash: () -> Unit) {
    private val motion = CMMotionManager()
    private val logic = CrashLogic()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (_running.value || !motion.accelerometerAvailable) return
        motion.accelerometerUpdateInterval = 0.02
        motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            val g = data?.acceleration?.useContents { sqrt(x * x + y * y + z * z) } ?: return@startAccelerometerUpdatesToQueue
            logic.onAccel(nowMs(), g)
        }
        _running.value = true
        // Ask once, so the crash countdown can show on the lock screen.
        askNotificationPermission()
    }

    fun stop() {
        motion.stopAccelerometerUpdates()
        logic.reset()
        _running.value = false
    }

    /** Every GPS speed reading, in km/h (negative when unknown). */
    fun onSpeed(kmh: Double) {
        if (!_running.value || kmh < 0) return
        if (logic.onSpeed(nowMs(), kmh)) onCrash()
    }

    private fun nowMs() = app.sawaari.util.nowMillis()
}

/** A loud alert tone and a vibration, for the crash countdown. */
fun alarmBeep() {
    AudioServicesPlaySystemSound(1005u)
    AudioServicesPlaySystemSound(4095u) // vibrate
}

/** A notification that shows on the lock screen, e.g. "Crash detected". */
fun notifyNow(title: String, body: String, id: String = "sawaari-crash") {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    content.setSound(UNNotificationSound.defaultSound)
    val request = UNNotificationRequest.requestWithIdentifier(id, content, null)
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
}

/** Asks once to show notifications (crash countdown, service reminders). iOS shows the prompt only the first time. */
fun askNotificationPermission() {
    UNUserNotificationCenter.currentNotificationCenter()
        .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
}
