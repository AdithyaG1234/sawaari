package app.sawaari.places

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.sawaari.BuildConfig
import app.sawaari.geo.GeoPoint
import app.sawaari.group.sawaariDatabase
import app.sawaari.routing.RoutingConfig
import app.sawaari.routing.routingPacks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Average of rider ratings (1-5) and how many riders rated. */
data class RiderRating(val average: Double, val count: Int, val mine: Int?)

/** Google's rating for a place, looked up online by name and position. */
data class GoogleRating(val rating: Double?, val count: Int, val mapsUri: String?)

/**
 * Places along routes (food, fuel, mechanics, tyre shops, hospitals), stored per routing area so
 * they work offline. Ratings come from riders (Firebase) and, with signal and an API key, Google.
 */
class PlacesRepository(private val context: Context) {

    private val dir = File(context.filesDir, "places").apply { mkdirs() }
    private val lock = Mutex()
    private val cache = mutableMapOf<String, List<Place>>()
    private val riderRatings = mutableMapOf<String, RiderRating>()
    private val googlePrefs = context.getSharedPreferences("google_places", Context.MODE_PRIVATE)

    val googleEnabled: Boolean get() = BuildConfig.PLACES_API_KEY.isNotBlank()
    private val firebaseReady: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    private fun file(packId: String) = File(dir, "$packId-v2.tsv.gz").also { File(dir, "$packId.tsv.gz").delete() }

    /** Places for the routing area that contains [p], downloading the area's file the first time. */
    suspend fun placesNear(p: GeoPoint): List<Place> = withContext(Dispatchers.IO) {
        val pack = routingPacks.firstOrNull { it.contains(p.lat, p.lon) } ?: return@withContext emptyList()
        lock.withLock {
            cache[pack.id]?.let { return@withLock it }
            val f = file(pack.id)
            if (!f.isFile) runCatching { download("${RoutingConfig.RELEASE_BASE_URL}pois-${pack.id}.tsv.gz", f) }
            if (!f.isFile) return@withLock emptyList()
            val list = runCatching {
                GZIPInputStream(f.inputStream().buffered()).bufferedReader().useLines { lines -> lines.mapNotNull(PlaceMatcher::parse).toList() }
            }.getOrDefault(emptyList())
            cache[pack.id] = list
            list
        }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.path + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) return
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } finally {
            conn.disconnect(); tmp.delete()
        }
    }

    fun cachedRiderRating(id: String): RiderRating? = riderRatings[id]

    /** Rider ratings for [id] from Firebase: places/{id}/{uid} = 1..5. Null without Firebase or signal. */
    suspend fun riderRating(id: String): RiderRating? {
        if (!firebaseReady) return null
        return runCatching {
            val uid = signIn()
            val snap = sawaariDatabase().getReference("places/$id").get().await()
            val values = snap.children.mapNotNull { it.getValue(Long::class.java)?.toInt() }.filter { it in 1..5 }
            val mine = snap.child(uid).getValue(Long::class.java)?.toInt()
            RiderRating(if (values.isEmpty()) 0.0 else values.average(), values.size, mine).also { riderRatings[id] = it }
        }.getOrNull()
    }

    suspend fun rate(id: String, stars: Int): RiderRating? {
        if (!firebaseReady || stars !in 1..5) return null
        runCatching {
            val uid = signIn()
            sawaariDatabase().getReference("places/$id/$uid").setValue(stars).await()
        }
        return riderRating(id)
    }

    private suspend fun signIn(): String {
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid
    }

    /**
     * Google rating via Places API (New) Text Search, one request per place, cached for 30 days so
     * repeat looks cost nothing. Null without a key or signal, or if Google doesn't know the place.
     */
    suspend fun googleRating(place: Place): GoogleRating? = withContext(Dispatchers.IO) {
        if (!googleEnabled) return@withContext null
        val cached = googlePrefs.getString(place.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (cached != null && System.currentTimeMillis() - cached.optLong("t") < CACHE_MS) return@withContext parseGoogle(cached)
        // Hard daily cap per phone, so a busy ride can never run up a Google bill.
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val used = if (googlePrefs.getString("day", "") == today) googlePrefs.getInt("count", 0) else 0
        if (used >= DAILY_GOOGLE_LOOKUPS) return@withContext null
        googlePrefs.edit().putString("day", today).putInt("count", used + 1).apply()
        runCatching {
            val body = JSONObject()
                .put("textQuery", place.name.ifBlank { place.category.fallbackName })
                .put("maxResultCount", 1)
                .put("locationBias", JSONObject().put("circle", JSONObject()
                    .put("center", JSONObject().put("latitude", place.point.lat).put("longitude", place.point.lon))
                    .put("radius", 250.0)))
            val conn = (URL("https://places.googleapis.com/v1/places:searchText").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", BuildConfig.PLACES_API_KEY)
                setRequestProperty("X-Goog-FieldMask", "places.rating,places.userRatingCount,places.googleMapsUri")
                setRequestProperty("X-Android-Package", context.packageName)
                signingSha1()?.let { setRequestProperty("X-Android-Cert", it) }
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode != 200) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val first = json.optJSONArray("places")?.optJSONObject(0)
                val stored = JSONObject().put("t", System.currentTimeMillis())
                if (first != null) {
                    if (first.has("rating")) stored.put("r", first.getDouble("rating"))
                    stored.put("n", first.optInt("userRatingCount")).put("u", first.optString("googleMapsUri"))
                }
                googlePrefs.edit().putString(place.id, stored.toString()).apply()
                parseGoogle(stored)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun parseGoogle(o: JSONObject): GoogleRating? {
        if (!o.has("r")) return null
        return GoogleRating(o.getDouble("r"), o.optInt("n"), o.optString("u").ifBlank { null })
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String? = runCatching {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        sig?.let { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } }
    }.getOrNull()

    companion object {
        private const val CACHE_MS = 30L * 24 * 60 * 60_000
        const val DAILY_GOOGLE_LOOKUPS = 30
    }
}
