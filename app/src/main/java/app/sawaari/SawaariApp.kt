package app.sawaari

import android.app.Application
import app.sawaari.geo.GeoPoint
import app.sawaari.group.GroupRepository
import app.sawaari.places.PlacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import app.sawaari.routing.RoutingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.maplibre.android.MapLibre

class SawaariApp : Application() {
    /** App-wide scope so downloads keep going when the rider switches screens. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val routing: RoutingRepository by lazy { RoutingRepository(this, appScope) }
    val groups: GroupRepository by lazy { GroupRepository(this, appScope) }
    val places: PlacesRepository by lazy { PlacesRepository(this) }

    /** The group's planned route line while this phone navigates it, so alerts can spot riders off the route. */
    val groupRoute = MutableStateFlow<List<GeoPoint>?>(null)

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        app.sawaari.ride.RideRecorder.init(this)
    }
}
