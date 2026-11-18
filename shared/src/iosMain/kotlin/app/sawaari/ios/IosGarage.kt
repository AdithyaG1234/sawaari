@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.sawaari.ios

import app.sawaari.garage.GarageBook
import app.sawaari.ride.RideLog
import app.sawaari.util.nowMillis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState

/** The garage as Documents/garage.json, the same JSON as garage.json on Android. */
fun documentsGarage(rides: () -> RideLog): GarageBook {
    val path = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/garage.json"
    return GarageBook(
        load = { NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) },
        save = { text -> NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null) },
        rides = { rides().rides.value },
        now = ::nowMillis,
    )
}

/**
 * Service and paper reminders, like GarageReminders on Android: checked when the app starts and
 * after every ride, and each job is mentioned once when it's due soon and once more when it's due.
 */
object GarageAlerts {
    private const val KEY_SENT = "garage.reminded"

    fun check(garage: GarageBook) {
        val d = NSUserDefaults.standardUserDefaults
        val sent = d.stringArrayForKey(KEY_SENT).orEmpty().filterIsInstance<String>().toSet()
        val due = garage.reminders()
        val fresh = due.filter { "${it.key}:${it.status.level}" !in sent }
        if (fresh.isEmpty()) return
        val title = if (fresh.size == 1) "${fresh[0].what}: ${fresh[0].status.text.lowercase()}" else "${fresh.size} vehicle jobs need attention"
        val text = fresh.joinToString("\n") { "${it.bike}: ${it.what}, ${it.status.text.lowercase()}" }
        notifyNow(title, text, id = "sawaari-garage")
        // Keep only keys that still matter, so the list doesn't grow forever.
        val keep = due.map { "${it.key}:${it.status.level}" }.toSet() + sent.filter { s -> due.any { s.startsWith(it.key + ":") } }
        d.setObject(keep.toList(), KEY_SENT)
    }
}

/** True when the app isn't on screen (screen off or another app open). */
fun appInBackground(): Boolean = UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive
