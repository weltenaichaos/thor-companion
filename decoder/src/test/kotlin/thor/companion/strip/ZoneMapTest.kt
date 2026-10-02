package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneMapTest {

    @Test
    fun parsesPlaces() {
        val map = ZoneMap.parse("TM1|1420|Tirisfal Glades|Eastern Kingdoms\nw,0.500,0.500,Map pin\nQ,0.600,0.200,Quest, number 2\nf,0.620,0.480,Brill, Tirisfal\nbad")!!
        assertEquals(1420, map.mapId)
        assertEquals("Tirisfal Glades", map.zone)
        assertEquals("Eastern Kingdoms", map.parent)
        assertEquals(
            listOf(MapPlace('w', 0.5, 0.5, "Map pin"), MapPlace('Q', 0.6, 0.2, "Quest, number 2"), MapPlace('f', 0.62, 0.48, "Brill, Tirisfal")),
            map.places,
        )
    }

    @Test
    fun parsesAnEmptyMap() {
        assertEquals(ZoneMap(85, "Orgrimmar", "", emptyList()), ZoneMap.parse("TM1|85|Orgrimmar|\n"))
        assertNull(ZoneMap.parse("TS1|x|1|0|0|0|0"))
    }

    @Test
    fun statusCarriesFacing() {
        assertEquals(1.2, GameState.parse("TS1|Xandra|5|40912|1420|0.3187|0.6556|1.2")!!.facing)
        assertNull(GameState.parse("TS1|Xandra|5|40912|1420|0.3187|0.6556|")!!.facing)
        assertNull(GameState.parse("TS1|Xandra|5|40912|1420|0.3187|0.6556")!!.facing)
    }

    @Test
    fun trailAddsStepsAndBreaksOnJumps() {
        val t = Trail(step = 0.01, jump = 0.1)
        assertTrue(t.add(1, 0.5, 0.5))
        assertFalse(t.add(1, 0.505, 0.5), "too close to the last point")
        assertTrue(t.add(1, 0.52, 0.5))
        assertTrue(t.add(1, 0.9, 0.9), "a jump starts a new piece")
        assertEquals(listOf(2, 1), t.paths[1]!!.map { it.size })
        val copy = Trail().apply { load(t.save()) }
        assertEquals(t.paths[1]!!.map { p -> p.map { it.toList() } }, copy.paths[1]!!.map { p -> p.map { it.toList() } })
    }

    @Test
    fun trailKeepsTheNewest() {
        val t = Trail(step = 0.001, jump = 1.0, keep = 3)
        for (i in 1..5) t.add(1, i / 10.0, 0.5)
        assertEquals(listOf(0.3, 0.4, 0.5), t.paths[1]!!.single().map { it[0] })
    }
}
