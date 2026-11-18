package app.sawaari.places

import app.sawaari.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotonTest {
    private val answer = """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[77.6835,13.3702]},
         "properties":{"name":"Nandi Hills","county":"Chikkaballapura","state":"Karnataka","country":"India"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[77.59,12.97]},
         "properties":{"street":"MG Road","city":"Bengaluru","state":"Karnataka"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}}
    ]}"""

    @Test fun readsResults() {
        val r = Photon.parse(answer)
        assertEquals(2, r.size)
        assertEquals(SearchResult("Nandi Hills", "Chikkaballapura, Karnataka", GeoPoint(13.3702, 77.6835), true), r[0])
        assertEquals("MG Road", r[1].name)
        assertEquals("Bengaluru, Karnataka", r[1].detail)
        assertEquals("Nandi Hills, Chikkaballapura", Photon.placeName(answer))
        assertTrue(Photon.parse("oops").isEmpty())
        assertNull(Photon.placeName("""{"features":[]}"""))
    }

    @Test fun buildsUrlsAndReadsCoordinates() {
        assertEquals(
            "https://photon.komoot.io/api/?limit=15&lang=en&q=nandi+hills&lat=12.97160&lon=77.59460",
            Photon.searchUrl(" nandi hills ", GeoPoint(12.9716, 77.5946)),
        )
        assertEquals(GeoPoint(13.37, 77.68), Photon.coordinates("13.37, 77.68")?.point)
        assertNull(Photon.coordinates("nandi"))
    }

    @Test fun nearbyComesFirst() {
        val me = GeoPoint(13.31, 76.94)
        val far = SearchResult("Temple", "far", GeoPoint(15.85, 74.50), true)
        val near = SearchResult("Temple", "near", GeoPoint(13.312, 76.948), false)
        assertEquals(listOf(near, far), Photon.rank(listOf(far, near), me))
    }
}
