package app.sawaari.routing

import android.content.Context
import app.sawaari.geo.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

sealed interface PackState {
    data object NotDownloaded : PackState
    data class Downloading(val bytes: Long, val totalBytes: Long) : PackState {
        val progress: Float get() = if (totalBytes <= 0) 0f else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data class Ready(val sizeBytes: Long) : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Downloads, stores and loads offline routing packs, and answers route requests
 * entirely on the phone once the pack for an area is downloaded.
 */
class RoutingRepository(context: Context, private val scope: CoroutineScope) {

    private val root = File(context.filesDir, "routing").apply { mkdirs() }
    private val jobs = mutableMapOf<String, Job>()
    private val routerLock = Mutex()
    private var loaded: Pair<String, OfflineRouter>? = null

    private val _states = MutableStateFlow(routingPacks.associate { it.id to stateOnDisk(it) })
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    private fun packDir(pack: RoutingPack) = File(root, pack.id)
    private fun isComplete(dir: File) = File(dir, "properties").isFile

    private fun stateOnDisk(pack: RoutingPack): PackState {
        val dir = packDir(pack)
        return if (isComplete(dir)) PackState.Ready(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        else PackState.NotDownloaded
    }

    private fun set(pack: RoutingPack, state: PackState) = _states.update { it + (pack.id to state) }

    fun download(pack: RoutingPack) {
        if (jobs[pack.id]?.isActive == true) return
        jobs[pack.id] = scope.launch(Dispatchers.IO) {
            set(pack, PackState.Downloading(0, 0))
            val tmp = File(root, "${pack.id}.part")
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                val conn = (URL(pack.downloadUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }
                try {
                    if (conn.responseCode != 200) {
                        throw RoutingException(
                            if (conn.responseCode == 404) "This routing pack isn't published yet."
                            else "Download failed (HTTP ${conn.responseCode})."
                        )
                    }
                    val total = conn.contentLengthLong
                    var lastReported = 0L
                    val counting = CountingInputStream(conn.inputStream) { read ->
                        if (read - lastReported >= 512 * 1024 || read == total) {
                            lastReported = read
                            set(pack, PackState.Downloading(read, total))
                        }
                    }
                    unzip(counting, tmp)
                } finally {
                    conn.disconnect()
                }
                if (!isComplete(tmp)) throw RoutingException("Downloaded file is not a routing pack.")
                routerLock.withLock {
                    if (loaded?.first == pack.id) { loaded?.second?.close(); loaded = null }
                    packDir(pack).deleteRecursively()
                    if (!tmp.renameTo(packDir(pack))) throw RoutingException("Could not save the routing pack.")
                }
                set(pack, stateOnDisk(pack))
            } catch (e: CancellationException) {
                tmp.deleteRecursively()
                set(pack, stateOnDisk(pack))
                throw e
            } catch (e: Exception) {
                tmp.deleteRecursively()
                set(pack, PackState.Failed(e.message ?: "Download failed. Check your connection and try again."))
            }
        }
    }

    fun delete(pack: RoutingPack) {
        jobs.remove(pack.id)?.cancel()
        scope.launch(Dispatchers.IO) {
            routerLock.withLock {
                if (loaded?.first == pack.id) { loaded?.second?.close(); loaded = null }
                packDir(pack).deleteRecursively()
            }
            set(pack, PackState.NotDownloaded)
        }
    }

    /** True if some downloaded pack covers both points. */
    fun hasCoverage(from: GeoPoint, to: GeoPoint) = coveringPack(listOf(from, to)) != null

    private fun coveringPack(points: List<GeoPoint>) = routingPacks.firstOrNull { pack ->
        _states.value[pack.id] is PackState.Ready && points.all { pack.contains(it.lat, it.lon) }
    }

    suspend fun route(from: GeoPoint, to: GeoPoint): Route = route(listOf(from, to))

    /** Compute a route fully offline. Throws [RoutingException] with a rider-friendly message. */
    suspend fun route(points: List<GeoPoint>): Route = withContext(Dispatchers.IO) {
        val pack = coveringPack(points) ?: run {
            val candidate = routingPacks.firstOrNull { pack -> points.all { pack.contains(it.lat, it.lon) } }
            throw RoutingException(
                if (candidate != null) "Download the \"${candidate.name}\" routing pack in Offline maps first."
                else "Offline navigation isn't available for this trip yet. The start, stops and destination must all be inside one routing area."
            )
        }
        routerLock.withLock {
            val router = loaded?.takeIf { it.first == pack.id }?.second ?: run {
                loaded?.second?.close()
                OfflineRouter(packDir(pack)).also { loaded = pack.id to it }
            }
            router.route(points)
        }
    }

    private fun unzip(input: InputStream, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered(256 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destPath)) throw RoutingException("Invalid routing pack.")
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered(256 * 1024).use { zip.copyTo(it, 256 * 1024) }
                }
            }
        }
    }

    private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) onRead(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    }
}
