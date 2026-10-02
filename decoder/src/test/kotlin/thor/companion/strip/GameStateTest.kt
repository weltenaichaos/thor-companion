package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameStateTest {

    @Test
    fun parsesPayload() {
        val s = GameState.parse("TC1|Xandra|5|40912|1420|0.3187|0.6556|17/26|4604:7,159:5,247841:1")!!
        assertEquals("Xandra", s.name)
        assertEquals(5, s.level)
        assertEquals(4L, s.gold)
        assertEquals(9L, s.silver)
        assertEquals(12L, s.copperPart)
        assertEquals(1420, s.mapId)
        assertEquals(0.3187, s.x)
        assertEquals(17, s.freeSlots)
        assertEquals(26, s.totalSlots)
        assertEquals(listOf(BagItem(4604, 7), BagItem(159, 5), BagItem(247841, 1)), s.items)
    }

    @Test
    fun secretValuesAndTrimmedListAreTolerated() {
        val s = GameState.parse("TC1|?|?|0|0|0.0000|0.0000|0/16|4604:7,15")!!
        assertNull(s.level)
        assertNull(s.mapId)
        assertEquals(listOf(BagItem(4604, 7)), s.items)
    }

    @Test
    fun parsesTapKeys() {
        val s = GameState.parse("TC1|X|5|0|1|0|0|1/16|6948:1:1,4604:7:12,159:5")!!
        assertEquals(listOf(BagItem(6948, 1, 1), BagItem(4604, 7, 12), BagItem(159, 5)), s.items)
    }

    @Test
    fun rejectsOtherPayloads() {
        assertNull(GameState.parse("TC1|error|Data.lua:12: boom"))
        assertNull(GameState.parse("hello"))
    }
}
