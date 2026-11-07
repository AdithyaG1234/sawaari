package app.sawaari.routing

import app.sawaari.geo.GeoPoint
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * On-device routing with GraphHopper 4.0 over a downloaded routing pack.
 * Uses the "fastest" weighting rather than custom models, because custom models
 * compile Java bytecode at runtime, which Android cannot load.
 */
class OfflineRouter(graphDir: File) : Closeable {

    private val hopper = GraphHopper().apply {
        init(
            GraphHopperConfig()
                .putObject("graph.dataaccess", "MMAP")
                .putObject("graph.flag_encoders", RoutingConfig.VEHICLE)
                .setProfiles(listOf(Profile(RoutingConfig.PROFILE).setVehicle(RoutingConfig.VEHICLE).setWeighting("fastest")))
                .setCHProfiles(listOf(CHProfile(RoutingConfig.PROFILE)))
        )
        setAllowWrites(false)
        if (!load(graphDir.absolutePath)) throw RoutingException("Routing data for this area is incomplete. Delete and download it again.")
    }

    private val translation = hopper.translationMap.getWithFallBack(Locale.ENGLISH)

    fun route(from: GeoPoint, to: GeoPoint): Route {
        val request = GHRequest(from.lat, from.lon, to.lat, to.lon)
            .setProfile(RoutingConfig.PROFILE)
            .setLocale(Locale.ENGLISH)
        val response = hopper.route(request)
        if (response.hasErrors()) {
            val reason = response.errors.firstOrNull()?.message.orEmpty()
            throw RoutingException(
                if (reason.contains("Cannot find point", ignoreCase = true)) "No road found near the start or destination."
                else "No route found. $reason".trim()
            )
        }
        val path = response.best
        val pl = path.points
        val points = (0 until pl.size()).map { GeoPoint(pl.getLat(it), pl.getLon(it)) }
        val raw = path.instructions.map { ins ->
            val ip = ins.points
            RawStep(
                text = ins.getTurnDescription(translation),
                sign = ins.sign,
                streetName = ins.name.orEmpty(),
                firstPoint = if (ip.size() > 0) GeoPoint(ip.getLat(0), ip.getLon(0)) else points.last(),
            )
        }
        return Route.build(points, raw, path.distance, path.time)
    }

    override fun close() = hopper.close()
}
