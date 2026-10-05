package thor.companion.strip

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompassTest {
    private val zone = ZoneMap(1, "Z", "", listOf(
        MapPlace('q', 0.5, 0.2, "Boar Ribs"),
        MapPlace('q', 0.9, 0.5, "Boar Ribs"),
        MapPlace('a', 0.1, 0.1, "Other Quest"),
    ), width = 1000, height = 1000)

    @Test
    fun pointsToTheNearestArea() {
        val h = Compass.toQuest(zone, "Boar Ribs", 0.5, 0.5)!!
        assertEquals(0.5, h.place.x)
        assertEquals(0.0, h.bearing, 1e-9)   // straight north
        assertEquals(300, h.yards)
    }

    @Test
    fun eastIsAQuarterTurn() {
        val h = Compass.toQuest(zone, "Boar Ribs", 0.85, 0.5)!!
        assertEquals(PI / 2, h.bearing, 1e-9)
        assertEquals(50, h.yards)
    }

    @Test
    fun noPlaceNoArrow() {
        assertNull(Compass.toQuest(zone, "Unknown", 0.5, 0.5))
    }

    @Test
    fun cutLabelsStillMatch() {
        assertEquals(true, Compass.matches("A very long quest…", "A very long quest title"))
    }
}
