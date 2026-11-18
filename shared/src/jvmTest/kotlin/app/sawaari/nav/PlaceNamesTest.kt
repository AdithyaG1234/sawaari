package app.sawaari.nav

import app.sawaari.geo.GeoPoint
import app.sawaari.places.Place
import app.sawaari.places.PlaceCategory
import app.sawaari.places.PlaceNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceNamesTest {
    private val gubbi = Place("n1", PlaceCategory.TOWN, GeoPoint(13.3120, 76.9410), "Gubbi", "")
    private val tumakuru = Place("n2", PlaceCategory.TOWN, GeoPoint(13.3392, 77.1140), "Tumakuru", "")
    private val fuel = Place("n3", PlaceCategory.FUEL, GeoPoint(13.3160, 76.9433), "HP Petrol", "")

    @Test fun inTown() = assertEquals("Gubbi", PlaceNames.describe(GeoPoint(13.3160, 76.9433), listOf(fuel, tumakuru, gubbi)))

    @Test fun nearTown() = assertEquals("Near Gubbi", PlaceNames.describe(GeoPoint(13.3600, 76.9800), listOf(gubbi, tumakuru)))

    @Test fun nothingClose() = assertNull(PlaceNames.describe(GeoPoint(14.5, 76.0), listOf(gubbi, tumakuru, fuel)))
}
