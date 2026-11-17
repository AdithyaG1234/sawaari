package app.sawaari.util

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.formatDistance
import app.sawaari.share.RouteLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Runs on both the JVM (Android) and the iPhone simulator, so both phones read links the same way. */
class TextTest {
    @Test fun fixedRoundsHalfUp() {
        assertEquals("12.97160", Text.fixed(12.9716, 5))
        assertEquals("1.5", Text.fixed(1.45, 1))
        assertEquals("1.01", Text.fixed(1.005, 2))
        assertEquals("100.0", Text.fixed(99.96, 1))
        assertEquals("0.00001", Text.fixed(0.00001, 5))
        assertEquals("0.13", Text.fixed(0.125, 2))
        assertEquals("-77.59460", Text.fixed(-77.5946, 5))
        assertEquals("-0.0", Text.fixed(-0.01, 1))
        assertEquals("3", Text.fixed(2.5, 0))
    }

    @Test fun groupedThousands() {
        assertEquals("1", Text.grouped(1))
        assertEquals("999", Text.grouped(999))
        assertEquals("1,000", Text.grouped(1000))
        assertEquals("12,345,678", Text.grouped(12345678))
    }

    @Test fun urlRoundTrip() {
        val s = "Nandi Hills & Café, ಬೆಂಗಳೂರು / 13°N"
        assertEquals(s, Text.urlDecode(Text.urlEncode(s)))
        assertEquals("a+b%26c", Text.urlEncode("a b&c"))
        assertFailsWith<IllegalArgumentException> { Text.urlDecode("%4") }
    }

    @Test fun distances() {
        assertEquals("12 km", formatDistance(12_340.0))
        assertEquals("2.5 km", formatDistance(2_460.0))
        assertEquals("350 m", formatDistance(347.0))
    }

    @Test fun googleLinkParses() {
        val r = assertNotNull(RouteLinks.parse("https://www.google.com/maps/dir/12.9716,77.5946/13.3702,77.6835"))
        assertEquals(GeoPoint(13.3702, 77.6835), r.points.last().point)
    }
}
