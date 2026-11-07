package app.sawaari

import android.app.Application
import org.maplibre.android.MapLibre

class SawaariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
