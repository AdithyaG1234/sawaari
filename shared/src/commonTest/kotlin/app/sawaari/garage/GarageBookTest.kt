package app.sawaari.garage

import app.sawaari.ride.SavedRide
import app.sawaari.ride.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GarageBookTest {
    private var file: String? = null
    private var clock = 1_700_000_000_000L
    private val rides = mutableListOf<SavedRide>()
    private fun book() = GarageBook({ file }, { file = it }, { rides }, { clock })

    /** A ride of about [km] km north, on [bike]. */
    private fun ride(bike: Long, km: Int) {
        val start = clock
        val track = (0..km * 9).map { i -> TrackPoint(12.9 + i * 0.001, 77.6, start + i * 10_000L, 40.0) }
        rides += SavedRide(start, "Ride", track, bike)
    }

    @Test fun ridesAddToTheOdometerAndJobsComeDue() {
        val g = book()
        g.addBike("Himalayan", 12_000.0)
        val bike = g.state.value.current!!
        assertEquals(bike.id, g.currentBikeId())
        clock += 1000
        ride(bike.id, 520)
        assertTrue(g.odometer(bike) in 12_510.0..12_540.0)
        // Chain lube is every 500 km: due now.
        val chain = g.reminders().first { it.what == "Chain clean and lube" }
        assertEquals(Level.DUE, chain.status.level)
        g.markDone(bike.id, "chain")
        assertTrue(g.reminders().none { it.what == "Chain clean and lube" })
    }

    @Test fun savesAndReloadsTheSameFile() {
        val g = book()
        g.addBike("Thar", 5_000.0, VehicleKind.CAR)
        val id = g.currentBikeId()
        g.setPaper(id, Papers.INSURANCE, clock + 10 * GarageLogic.DAY_MS)
        g.setOdometer(id, 6_000.0)
        val again = book().state.value
        assertEquals(g.state.value, again)
        assertEquals(VehicleKind.CAR, again.current!!.kind)
        assertEquals(Level.SOON, book().reminders().single { it.what == Papers.INSURANCE }.status.level)
    }

    @Test fun readsTheAndroidFile() {
        val android = """{"current":7,"bikes":[{"id":7,"name":"Classic","baseKm":100.5,"kind":"MOTORCYCLE",
            "items":[{"id":"oil","name":"Engine oil change","everyKm":3000,"everyDays":0,"lastKm":100.5,"lastAt":7}],
            "papers":{"Insurance":1800000000000}}]}"""
        val s = GarageBook.read(android)
        assertEquals("Classic", s.current!!.name)
        assertEquals(null, s.current!!.items.single().everyDays)
        assertEquals(1_800_000_000_000L, s.current!!.papers[Papers.INSURANCE])
    }

    @Test fun deletingTheCurrentBikePicksAnother() {
        val g = book()
        g.addBike("A", 0.0); clock += 1; g.addBike("B", 0.0)
        val b = g.currentBikeId()
        g.deleteBike(b)
        assertEquals("A", g.state.value.current!!.name)
    }
}
