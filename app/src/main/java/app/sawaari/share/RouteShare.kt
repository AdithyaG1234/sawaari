package app.sawaari.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan
import app.sawaari.nav.formatDistance
import app.sawaari.nav.formatDuration
import app.sawaari.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import java.io.File
import kotlin.coroutines.resume

/**
 * Shares the planned route: a picture of the map with the route, plus a Sawaari link (opens the
 * plan in the app) and a Google Maps link (for riders without Sawaari). Works well with WhatsApp.
 */
object RouteShare {

    fun summary(plan: RidePlan, route: Route?, name: (GeoPoint) -> String?): String {
        val from = plan.start?.let { name(it) ?: "Start" } ?: "My location"
        val to = plan.end?.let { name(it) ?: "Destination" } ?: ""
        val stops = plan.stops.map { name(it) }
        val via = when {
            stops.isEmpty() -> ""
            stops.all { it != null } -> " via " + stops.joinToString(", ")
            else -> " with ${stops.size} stop${if (stops.size > 1) "s" else ""}"
        }
        val stats = route?.let { " (${formatDistance(it.distanceMeters)}, about ${formatDuration(it.timeMillis)})" } ?: ""
        return "$from → $to$via$stats"
    }

    suspend fun share(context: Context, map: MapLibreMap?, plan: RidePlan, route: Route?, name: (GeoPoint) -> String?) {
        val text = buildString {
            append("Ride plan: ").append(summary(plan, route, name)).append("\n\n")
            RouteLinks.sawaariLink(plan, name)?.let { append("Open in Sawaari: ").append(it).append("\n") }
            RouteLinks.googleLink(plan)?.let { append("Google Maps: ").append(it) }
        }
        val image = map?.let { runCatching { snapshot(context, it, plan, route, summary(plan, route, name)) }.getOrNull() }
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        if (image != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", image)
            intent.setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else intent.setType("text/plain")
        context.startActivity(Intent.createChooser(intent, "Share route").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun snapshot(context: Context, map: MapLibreMap, plan: RidePlan, route: Route?, caption: String): File {
        val pts = route?.points?.takeIf { it.size >= 2 } ?: (listOfNotNull(plan.start) + plan.stops + listOfNotNull(plan.end))
        if (pts.size >= 2) {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 140))
            delay(1_200) // let tiles load and the frame render
        }
        val bmp = suspendCancellableCoroutine<Bitmap> { cont -> map.snapshot { cont.resume(it) } }
        return withContext(Dispatchers.IO) {
            val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val pad = out.width * 0.03f
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = out.width * 0.04f; typeface = Typeface.DEFAULT_BOLD }
            val band = text.textSize * 2.4f
            c.drawRect(0f, out.height - band, out.width.toFloat(), out.height.toFloat(), Paint().apply { color = Color.argb(200, 230, 81, 0) })
            c.drawText(ellipsize(caption, text, out.width - 2 * pad), pad, out.height - band / 2 + text.textSize / 3, text)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, "sawaari-route.png").also { f -> f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    private fun ellipsize(s: String, p: Paint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var t = s
        while (t.length > 4 && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
