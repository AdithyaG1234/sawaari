package app.sawaari

import android.app.Application
import app.sawaari.group.GroupRepository
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

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
