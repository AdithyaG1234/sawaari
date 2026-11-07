package app.sawaari.sos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object SosPermissions {
    /** Opens Sawaari's page in Android settings, where restricted settings and SMS permission are turned on. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
