package app.sawaari.map

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Full-screen map. Works offline for any area the rider has downloaded.
 * "Download this area" saves the currently visible part of the map.
 */
@Composable
fun MapScreen(onDownloadArea: (name: String, bounds: LatLngBounds) -> Unit, modifier: Modifier = Modifier) {
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var pendingBounds by remember { mutableStateOf<LatLngBounds?>(null) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { m ->
                        m.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL))
                        m.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(MapConfig.DEFAULT_LAT, MapConfig.DEFAULT_LON))
                            .zoom(MapConfig.DEFAULT_ZOOM)
                            .build()
                        map = m
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        ExtendedFloatingActionButton(
            onClick = { pendingBounds = map?.projection?.visibleRegion?.latLngBounds },
            icon = { Icon(Icons.Default.Download, contentDescription = null) },
            text = { Text("Download this area") },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }

    pendingBounds?.let { bounds ->
        var name by remember { mutableStateOf("My ride area") }
        AlertDialog(
            onDismissRequest = { pendingBounds = null },
            title = { Text("Save this area offline") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    onDownloadArea(name.ifBlank { "My ride area" }, bounds)
                    pendingBounds = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { pendingBounds = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}
