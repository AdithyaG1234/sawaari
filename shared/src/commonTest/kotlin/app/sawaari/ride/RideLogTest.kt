package app.sawaari.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryFiles : RideFiles {
    val map = mutableMapOf<String, String>()
    override fun list() = map.keys.toList()
    override fun read(name: String) = map[name]
    override fun write(name: String, text: String) { map[name] = text }
    override fun delete(name: String) { map.remove(name) }
}

class RideLogTest {
    /** About 111 m north per step of 0.001 degrees, one fix a minute. */
    private fun ride(log: RideLog, steps: Int, start: Long = 1_700_000_000_000L) {
        for (i in 0..steps) log.onLocation(12.9 + i * 0.001, 77.6, start + i * 60_000L, 40.0)
    }

    @Test
    fun keepsARideAfterNavigationEnds() {
        val files = MemoryFiles()
        val log = RideLog(files)
        log.setActive("nav", true, "Nandi Hills")
        ride(log, 10)
        log.setActive("nav", false)
        val r = log.rides.value.single()
        assertEquals("Nandi Hills", r.title)
        assertTrue(r.stats.distanceMeters > 1000)
        assertTrue("current.json" !in files.map)
    }

    @Test
    fun dropsShortRidesAndWaitsForAllReasons() {
        val log = RideLog(MemoryFiles())
        log.setActive("nav", true)
        log.setActive("group", true, "Coorg weekend")
        ride(log, 2)
        log.setActive("nav", false)
        assertTrue(log.recording)
        log.setActive("group", false)
        assertTrue(log.rides.value.isEmpty(), "a 222 m ride isn't kept")
    }

    @Test
    fun finishesARideCutOffByTheAppClosing() {
        val files = MemoryFiles()
        val log = RideLog(files)
        log.setActive("group", true, "Coorg weekend")
        ride(log, 25)
        assertTrue("current.json" in files.map)
        val reopened = RideLog(files)
        assertEquals("Coorg weekend", reopened.rides.value.single().title)
    }

    @Test
    fun readsTheAndroidFileFormat() {
        val text = """{"id":5,"title":"Hampi","bike":2,"points":[[15.3,76.4,5,0],[15.31,76.4,65000,35.5]]}"""
        val r = RideLog.decode(text)
        assertEquals(5L, r.id); assertEquals("Hampi", r.title); assertEquals(2L, r.bikeId)
        assertEquals(35.5, r.track[1].kmh)
        assertEquals(r, RideLog.decode(RideLog.encode(r)))
    }

    @Test
    fun deletesARide() {
        val log = RideLog(MemoryFiles())
        log.setActive("nav", true)
        ride(log, 10)
        log.setActive("nav", false)
        log.delete(log.rides.value.single().id)
        assertTrue(log.rides.value.isEmpty())
    }
}
