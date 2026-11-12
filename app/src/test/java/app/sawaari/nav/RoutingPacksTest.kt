package app.sawaari.nav

import app.sawaari.routing.routingPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoutingPacksTest {
    @Test fun idsAreUnique() {
        assertEquals(routingPacks.size, routingPacks.map { it.id }.toSet().size)
    }

    @Test fun boxesAreValid() {
        routingPacks.forEach { assertTrue(it.id, it.west < it.east && it.south < it.north) }
    }

    /** The app and the workflow that builds the packs must list the same areas. */
    @Test fun workflowBuildsEveryPack() {
        val yml = listOf(File("../.github/workflows/build-routing.yml"), File(".github/workflows/build-routing.yml"))
            .first { it.isFile }.readText()
        routingPacks.forEach { p ->
            fun f(d: Double) = d.toString()
            val entry = "- id: ${p.id}\n"
            assertTrue("workflow is missing ${p.id}", entry in yml)
            val bbox = "bbox: \"${f(p.west)},${f(p.south)},${f(p.east)},${f(p.north)}\""
            val block = yml.substringAfter(entry).substringBefore("- id:")
            assertTrue("bbox for ${p.id} differs: expected $bbox", bbox in block)
        }
    }

    @Test fun popularRideSpotsAreCovered() {
        val spots = mapOf(
            "Gokarna" to (14.55 to 74.32), "Varkala" to (8.73 to 76.71), "Pondicherry" to (11.93 to 79.83),
            "Srisailam" to (16.07 to 78.87), "Rishikesh" to (30.09 to 78.27), "Udaipur" to (24.58 to 73.71),
            "Gulmarg" to (34.05 to 74.38), "Gangtok" to (27.33 to 88.61), "Shillong" to (25.57 to 91.88),
        )
        spots.forEach { (name, ll) -> assertTrue(name, routingPacks.any { it.contains(ll.first, ll.second) }) }
    }
}
