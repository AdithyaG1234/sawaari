package app.sawaari.ios

import app.sawaari.group.KeyValueStore
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/** Saved settings in NSUserDefaults (the iPhone's equivalent of SharedPreferences). */
class UserDefaultsStore : KeyValueStore {
    private val d = NSUserDefaults.standardUserDefaults
    override fun getString(key: String): String? = d.stringForKey(key)
    override fun putString(key: String, value: String?) {
        if (value == null) d.removeObjectForKey(key) else d.setObject(value, key)
    }
}

/** Open the iPhone share sheet with [text] (WhatsApp, Messages, ...). */
fun shareText(text: String) {
    @Suppress("DEPRECATION")
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
    var top = root
    while (top.presentedViewController != null) top = top.presentedViewController!!
    top.presentViewController(UIActivityViewController(listOf(text), null), animated = true, completion = null)
}
