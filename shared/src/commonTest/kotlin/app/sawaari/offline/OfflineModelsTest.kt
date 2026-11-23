package app.sawaari.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineModelsTest {
    @Test fun downloadProgressStaysBetweenZeroAndOne() {
        fun region(done: Long, total: Long) = DownloadedRegion(1, "Goa", 0, done, total, isComplete = false)
        assertEquals(0f, region(0, 0).progress)
        assertEquals(0.5f, region(50, 100).progress)
        assertEquals(1f, region(120, 100).progress)
    }

    @Test fun regionPresetsAreValidAreas() {
        assertTrue(regionPresets.size >= 10)
        assertTrue(regionPresets.any { it.name.startsWith("Goa") }, "Goa preset is needed for the Goa trial")
        assertEquals(regionPresets.size, regionPresets.map { it.name }.toSet().size, "preset names must be unique")
        regionPresets.forEach { p ->
            val b = p.bounds
            assertTrue(b.north > b.south && b.east > b.west, "${p.name} has its edges the wrong way round")
            // All presets are in India.
            assertTrue(b.south >= 6.0 && b.north <= 37.5 && b.west >= 68.0 && b.east <= 97.5, "${p.name} is outside India")
        }
    }

    @Test fun fileSizesReadLikeAndroid() {
        assertEquals("850 B", formatBytes(850))
        assertEquals("1.5 kB", formatBytes(1_500))
        assertEquals("12 MB", formatBytes(12_000_000))
        assertEquals("1.4 GB", formatBytes(1_400_000_000))
        assertEquals("2 MB", formatBytes(2_000_000))
    }
}
