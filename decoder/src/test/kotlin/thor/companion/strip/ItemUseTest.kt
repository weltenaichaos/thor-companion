package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemUseTest {
    @Test
    fun parsesResults() {
        assertEquals(ItemUse(3, 6948, "Item is not ready yet."), ItemUse.parse("TU1|3|6948|Item is not ready yet."))
        assertEquals(ItemUse(4, 2589, "nouse"), ItemUse.parse("TU1|4|2589|nouse"))
        assertNull(ItemUse.parse("TU1|x|1|ok"))
    }

    @Test
    fun parsesCooldowns() {
        assertEquals(mapOf(6948 to 890L), Cooldowns.parse("TC2|15|6948:905,117:10"))
        assertEquals(emptyMap(), Cooldowns.parse("TC2|15|"))
        assertNull(Cooldowns.parse("TB1|1/2|"))
        assertEquals("15 min", Cooldowns.left(890))
        assertEquals("45 s", Cooldowns.left(45))
        assertEquals("1 h 5 min", Cooldowns.left(3900))
    }

    @Test
    fun statusCarriesSessionAndBagHash() {
        val s = GameState.parse("TS1|Xandra|5|40912|1420|0.3187|0.6556|1.2|1759400000|57339")!!
        assertEquals("1759400000", s.session)
        assertEquals(57339, s.bagHash)
        assertNull(GameState.parse("TS1|Xandra|5|40912|1420|0.3|0.6|1.2")!!.bagHash)
    }

    @Test
    fun hashMatchesTheAddon() {
        // Worked out with the addon's Lua checksum.
        assertEquals(34308, GameState.hash("TB1|3/160|2007:1:1,2014:2:2"))
    }
}
