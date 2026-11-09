package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.places.PlaceSearch
import app.sawaari.places.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankTest {
    private val gubbi = GeoPoint(13.3160, 76.9433)

    @Test fun nearbyPlaceBeatsSameNamedFarOne() {
        val far = SearchResult("Sri Channabasaveshwara Swami Temple", "far", GeoPoint(15.85, 74.50), online = true)
        val farther = SearchResult("Sri Channabasaveshwara Swami Temple", "farther", GeoPoint(17.0, 76.0), online = true)
        val nearA = SearchResult("Temple A", "near", GeoPoint(13.3200, 76.9400), online = true)
        val nearB = SearchResult("Sri Channabasaveshwara Swami Temple", "near", GeoPoint(13.3120, 76.9480), online = false)
        val ranked = PlaceSearch.rank(listOf(farther, far, nearA, nearB), gubbi)
        // Nearby first in their original order, then far ones nearest first.
        assertEquals(listOf("near", "near", "far", "farther"), ranked.map { it.detail })
        assertEquals("Temple A", ranked[0].name)
    }

    @Test fun noLocationKeepsOrder() {
        val a = SearchResult("A", "", GeoPoint(1.0, 1.0), true)
        val b = SearchResult("B", "", GeoPoint(2.0, 2.0), true)
        assertEquals(listOf(b, a), PlaceSearch.rank(listOf(b, a), null))
    }
}
