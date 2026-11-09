package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.share.RouteLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLinksTest {
    private val blr = GeoPoint(12.97160, 77.59460)
    private val mys = GeoPoint(12.29580, 76.63940)
    private val mdk = GeoPoint(12.42440, 75.73820)

    @Test fun sawaariLinkRoundTrip() {
        val plan = RidePlan(start = blr, stops = listOf(mys), end = mdk)
        val link = RouteLinks.sawaariLink(plan) { if (it == mys) "Mysuru | Palace" else null }!!
        assertTrue(link.startsWith(RouteLinks.SHARE_BASE))
        val r = RouteLinks.parse(link)!!
        assertEquals(listOf(blr, mys, mdk), r.points.map { it.point })
        assertEquals("Mysuru   Palace", r.points[1].name)
        // The custom scheme the web page hands to the app carries the same query.
        val viaScheme = RouteLinks.parse("sawaari://route" + link.substringAfter("/r/"))!!
        assertEquals(3, viaScheme.points.size)
    }

    @Test fun sawaariLinkWithoutStartStartsFromMe() {
        val r = RouteLinks.parse(RouteLinks.sawaariLink(RidePlan(end = mdk)) { null }!!)!!
        assertNull(r.points.first().point)
        assertEquals(mdk, r.points.last().point)
    }

    @Test fun googleApiLink() {
        val r = RouteLinks.parse(RouteLinks.googleLink(RidePlan(start = blr, stops = listOf(mys), end = mdk))!!)!!
        assertEquals(listOf(blr, mys, mdk), r.points.map { it.point })
        val named = RouteLinks.parse("https://www.google.com/maps/dir/?api=1&destination=Madikeri+Fort&travelmode=driving")!!
        assertNull(named.points.first().point)
        assertEquals("Madikeri Fort", named.points.last().name)
    }

    @Test fun googleDirPathWithNamesAndCoordinates() {
        val url = "https://www.google.com/maps/dir/Bengaluru,+Karnataka/12.2958,76.6394/Madikeri,+Karnataka/" +
            "@12.6,76.4,9z/data=!3m1!4b1!4m14!4m13!1m5!1m1!1s0x3bae1670c9b44e6d:0xf8dfc3e8517e4fe0!2m2!1d77.5945627!2d12.9715987" +
            "!1m0!1m5!1m1!1s0x3ba500e0e7d7e5c5:0x6d0b3b0a5d7f7e4e!2m2!1d75.7382!2d12.4244!3e0"
        val r = RouteLinks.parse(url)!!
        assertEquals(3, r.points.size)
        assertEquals("Bengaluru", r.points[0].name)
        assertEquals(12.9715987, r.points[0].point!!.lat, 1e-6)
        assertEquals(GeoPoint(12.2958, 76.6394), r.points[1].point)
        assertEquals(75.7382, r.points[2].point!!.lon, 1e-6)
    }

    @Test fun googleDirFromMyLocation() {
        val r = RouteLinks.parse("https://www.google.com/maps/dir//12.4244,75.7382/@12.4,75.7,12z")!!
        assertNull(r.points[0].point)
        assertEquals(GeoPoint(12.4244, 75.7382), r.points[1].point)
    }

    @Test fun googlePlaceAndQuery() {
        val place = RouteLinks.parse("https://www.google.com/maps/place/Abbey+Falls/@12.45,75.71,15z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d12.4569!4d75.7189")!!
        assertEquals("Abbey Falls", place.points.last().name)
        assertEquals(12.4569, place.points.last().point!!.lat, 1e-6)
        val q = RouteLinks.parse("https://maps.google.com/?q=12.42440,75.73820")!!
        assertEquals(mdk, q.points.last().point)
    }

    @Test fun findsLinksInSharedText() {
        assertEquals("https://maps.app.goo.gl/AbC123", RouteLinks.findUrl("Check this route https://maps.app.goo.gl/AbC123."))
        assertTrue(RouteLinks.isShortGoogleLink("https://maps.app.goo.gl/AbC123"))
        assertNull(RouteLinks.parse("https://example.com/hello"))
        assertNotNull(RouteLinks.latLon("12.97, +77.59"))
    }

    private fun near(a: GeoPoint?, lat: Double, lon: Double) {
        assertNotNull(a)
        assertEquals(lat, a!!.lat, 1e-4)
        assertEquals(lon, a.lon, 1e-4)
    }

    @Test fun readsCoordinatesCopiedFromGoogleMaps() {
        near(RouteLinks.latLon("13.316123, 76.942612"), 13.316123, 76.942612)
        near(RouteLinks.latLon("13°18'58.0\"N 76°56'33.4\"E"), 13.316111, 76.942611)
        near(RouteLinks.latLon("13.3161° N, 76.9426° E"), 13.3161, 76.9426)
        near(RouteLinks.latLon("33°51'54.5\"S 151°12'35.6\"E"), -33.865139, 151.209889)
        assertNull(RouteLinks.latLon("Hotel 5 N Road"))
        assertNull(RouteLinks.latLon("Gubbi"))
    }

    @Test fun googleDirectionsFromYourLocation() {
        val r = RouteLinks.parse("https://www.google.com/maps/dir/Your+location/13.316123,76.942612/")!!
        assertEquals(2, r.points.size)
        assertNull(r.points[0].point)
        assertEquals("", r.points[0].name)
        near(r.points[1].point, 13.316123, 76.942612)
    }

    @Test fun googleMapViewLink() {
        val r = RouteLinks.parse("https://www.google.com/maps/@13.316123,76.942612,15z")!!
        near(r.points.last().point, 13.316123, 76.942612)
    }

    @Test fun googlePlaceWithDmsName() {
        val r = RouteLinks.parse("https://www.google.com/maps/place/13%C2%B018'58.0%22N+76%C2%B056'33.4%22E/@13.3161,76.9426,17z")!!
        near(r.points.last().point, 13.3161, 76.9426)
    }

    @Test fun googleDirectionsSharedFromTheApp() {
        // Expanded from a maps.app.goo.gl link shared in Gubbi: stops carry !3d<lat>!4d<lon>.
        val url = "https://www.google.com/maps/dir/13.3161764,76.9428056/Sri+Channabasaveshwara+Swami+Temple/Gubbi+Railway+Station/13.3161764,76.9428056/data=!4m20!4m19!1m1!4e1!1m5!1m4!1s0x3bb024166aaaaaab:0x8075b1ad4be86d30!8m2!3d13.306277999999999!4d76.947754!1m5!1m4!1s0x3bb0246af8a2c265:0x36a56aa476a15295!8m2!3d13.3214103!4d76.9390016!1m1!4e1!2m1!11b1!3e0?g_st=ac&skid=221fd688-0dec-4519-840e-f514eb3dcbfd"
        val r = RouteLinks.parse(url)!!
        assertEquals(4, r.points.size)
        near(r.points[0].point, 13.3161764, 76.9428056)
        near(r.points[1].point, 13.306278, 76.947754)
        assertEquals("Sri Channabasaveshwara Swami Temple", r.points[1].name)
        near(r.points[2].point, 13.3214103, 76.9390016)
        assertEquals("Gubbi Railway Station", r.points[2].name)
        near(r.points[3].point, 13.3161764, 76.9428056)
    }
}
