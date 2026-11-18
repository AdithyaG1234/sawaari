package app.sawaari.share

import app.sawaari.geo.GeoPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkImportTest {
    @Test fun readsASawaariLinkWithNames() = runTest {
        val link = "Ride with us! https://adithyag1234.github.io/sawaari/r/?w=13.24700,77.71200&e=13.37020,77.68350&n=Devanahalli|Nandi%20Hills"
        val r = LinkImport.import(link, null, { null }, { _, _ -> null })
        assertEquals(null, r.plan.start)
        assertEquals(listOf(GeoPoint(13.247, 77.712)), r.plan.stops)
        assertEquals(GeoPoint(13.3702, 77.6835), r.plan.end)
        assertEquals("Nandi Hills", r.names[GeoPoint(13.3702, 77.6835)])
    }

    @Test fun expandsShortGoogleLinksAndLooksUpNames() = runTest {
        val r = LinkImport.import(
            "https://maps.app.goo.gl/abc123",
            GeoPoint(12.97, 77.59),
            { "https://www.google.com/maps/dir/12.97,77.59/Nandi+Hills" },
            { name, _ -> if (name == "Nandi Hills") GeoPoint(13.37, 77.68) else null },
        )
        assertEquals(GeoPoint(12.97, 77.59), r.plan.start)
        assertEquals(GeoPoint(13.37, 77.68), r.plan.end)
    }

    @Test fun explainsBadLinks() = runTest {
        assertFailsWith<LinkImportException> { LinkImport.import("no link here", null, { null }, { _, _ -> null }) }
        assertFailsWith<LinkImportException> { LinkImport.import("https://example.com/x", null, { null }, { _, _ -> null }) }
        assertFailsWith<LinkImportException> { LinkImport.import("https://maps.app.goo.gl/x", null, { null }, { _, _ -> null }) }
    }
}
