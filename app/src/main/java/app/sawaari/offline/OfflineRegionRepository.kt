package app.sawaari.offline

import android.content.Context
import app.sawaari.map.MapConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** UI-facing snapshot of one downloaded (or downloading) map region. */
data class DownloadedRegion(
    val id: Long,
    val name: String,
    val completedBytes: Long,
    val completedResources: Long,
    val requiredResources: Long,
    val isComplete: Boolean,
    val error: String? = null,
) {
    val progress: Float
        get() = if (requiredResources == 0L) 0f
        else (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f)
}

/**
 * Wraps MapLibre's OfflineManager: download a bounding box of the map for offline use,
 * list downloads with progress and size, and delete them.
 * Tiles are stored in MapLibre's on-device database and used automatically when offline.
 */
class OfflineRegionRepository(context: Context) {

    private val manager = OfflineManager.getInstance(context.applicationContext)
    private val regions = mutableMapOf<Long, OfflineRegion>()

    private val _downloads = MutableStateFlow<List<DownloadedRegion>>(emptyList())
    val downloads: StateFlow<List<DownloadedRegion>> = _downloads.asStateFlow()

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().forEach { track(it, resume = false) }
            }
            override fun onError(error: String) {}
        })
    }

    /**
     * Download [bounds] for offline use. Zoom 6-14 is enough for riding
     * (roads and town names) while keeping downloads to tens of MB per district.
     */
    fun download(name: String, bounds: LatLngBounds, minZoom: Double = 6.0, maxZoom: Double = 14.0, pixelRatio: Float = 2f) {
        val definition = OfflineTilePyramidRegionDefinition(MapConfig.STYLE_URL, bounds, minZoom, maxZoom, pixelRatio)
        val metadata = JSONObject().put(KEY_NAME, name).toString().toByteArray()
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) = track(offlineRegion, resume = true)
            override fun onError(error: String) {}
        })
    }

    fun delete(id: Long) {
        val region = regions[id] ?: return
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                regions.remove(id)
                _downloads.update { list -> list.filterNot { it.id == id } }
            }
            override fun onError(error: String) {}
        })
    }

    /** Re-start an unfinished download, e.g. after the network came back. */
    fun resume(id: Long) = regions[id]?.setDownloadState(OfflineRegion.STATE_ACTIVE)

    private fun track(region: OfflineRegion, resume: Boolean) {
        regions[region.id] = region
        val name = nameOf(region)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                upsert(snapshot(region.id, name, status))
                if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
            override fun onError(error: OfflineRegionError) {
                upsertError(region.id, name, error.message)
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                upsertError(region.id, name, "Area too large, pick a smaller area")
            }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status != null) upsert(snapshot(region.id, name, status))
            }
            override fun onError(error: String?) {}
        })
        if (resume) region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun snapshot(id: Long, name: String, s: OfflineRegionStatus) = DownloadedRegion(
        id = id,
        name = name,
        completedBytes = s.completedResourceSize,
        completedResources = s.completedResourceCount,
        requiredResources = s.requiredResourceCount,
        isComplete = s.isComplete,
    )

    private fun upsert(item: DownloadedRegion) = _downloads.update { list ->
        (list.filterNot { it.id == item.id } + item).sortedBy { it.name }
    }

    private fun upsertError(id: Long, name: String, message: String) = _downloads.update { list ->
        val existing = list.firstOrNull { it.id == id }
            ?: DownloadedRegion(id, name, 0, 0, 0, isComplete = false)
        (list.filterNot { it.id == id } + existing.copy(error = message)).sortedBy { it.name }
    }

    private fun nameOf(region: OfflineRegion): String = runCatching {
        JSONObject(String(region.metadata)).getString(KEY_NAME)
    }.getOrDefault("Region ${region.id}")

    private companion object { const val KEY_NAME = "name" }
}
