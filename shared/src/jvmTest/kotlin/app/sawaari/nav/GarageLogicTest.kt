package app.sawaari.nav

import app.sawaari.garage.Bike
import app.sawaari.garage.GarageLogic
import app.sawaari.garage.GarageLogic.DAY_MS
import app.sawaari.garage.Level
import app.sawaari.garage.ServiceItem
import app.sawaari.garage.VehicleKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GarageLogicTest {
    private val t0 = 1_700_000_000_000L
    private val oil = ServiceItem("oil", "Engine oil change", 3000, 180, lastKm = 10_000.0, lastAt = t0)

    @Test fun freshJobIsOk() {
        val s = GarageLogic.status(oil, 10_500.0, t0 + 10 * DAY_MS)
        assertEquals(Level.OK, s.level)
        assertEquals("Due in 2,500 km or 5 months", s.text)
    }

    @Test fun closeOnKmIsSoon() {
        assertEquals(Level.SOON, GarageLogic.status(oil, 12_800.0, t0 + DAY_MS).level)
    }

    @Test fun closeOnDaysIsSoon() {
        assertEquals(Level.SOON, GarageLogic.status(oil, 10_100.0, t0 + 175 * DAY_MS).level)
    }

    @Test fun pastKmIsDue() {
        val s = GarageLogic.status(oil, 13_250.0, t0 + DAY_MS)
        assertEquals(Level.DUE, s.level)
        assertEquals("Overdue by 250 km", s.text)
    }

    @Test fun pastDaysIsDue() {
        val s = GarageLogic.status(oil, 10_100.0, t0 + 200 * DAY_MS)
        assertEquals(Level.DUE, s.level)
        assertEquals("Overdue by 20 days", s.text)
    }

    @Test fun kmOnlyJob() {
        val chain = ServiceItem("chain", "Chain", 500, null, 0.0, t0)
        assertEquals(Level.OK, GarageLogic.status(chain, 100.0, t0 + 1000 * DAY_MS).level)
        assertEquals(Level.DUE, GarageLogic.status(chain, 600.0, t0).level)
    }

    @Test fun papers() {
        assertEquals(Level.OK, GarageLogic.paperStatus(t0 + 100 * DAY_MS, t0).level)
        assertEquals(Level.SOON, GarageLogic.paperStatus(t0 + 10 * DAY_MS, t0).level)
        assertEquals(Level.DUE, GarageLogic.paperStatus(t0 - 3 * DAY_MS, t0).level)
        assertEquals("Expired 3 days ago", GarageLogic.paperStatus(t0 - 3 * DAY_MS, t0).text)
    }

    @Test fun settingTheOdometerKeepsRidesCounting() {
        val base = GarageLogic.baseFor(newOdoKm = 20_000.0, riddenKm = 350.0)
        val bike = Bike(1, "Bike", base, emptyList())
        assertEquals(20_000.0, GarageLogic.odometer(bike, 350.0), 0.001)
        assertEquals(20_120.0, GarageLogic.odometer(bike, 470.0), 0.001)
    }

    @Test fun eachVehicleKindHasItsOwnJobs() {
        val car = GarageLogic.defaultItems(0.0, t0, VehicleKind.CAR).map { it.name }
        val cycle = GarageLogic.defaultItems(0.0, t0, VehicleKind.BICYCLE).map { it.name }
        assertTrue("Coolant" in car)
        assertFalse(cycle.any { "oil" in it.lowercase() })
        assertFalse(VehicleKind.BICYCLE.hasPapers)
        assertEquals(VehicleKind.MOTORCYCLE, VehicleKind.of(null)) // garages saved before vehicle kinds existed
    }
}
