package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpellPlanTest {
    @Test
    fun readsReadyAndLaterSpells() {
        val p = SpellPlan.parse("TV1|14\n14\tr\t1200\tCorruption\tRank 2\n15\tf\t0\tDrain Life\tRank 2\n16\tf\t1500\tFear\tRank 1\n16\tf\t0\tCurse of Agony\tRank 2")!!
        assertEquals(14, p.level)
        assertEquals(listOf("Corruption"), p.ready.map { it.name })
        assertEquals(1200, p.readyCost)
        assertEquals(listOf(15, 16), p.later.keys.toList())
        assertEquals(2, p.later[16]!!.size)
    }

    @Test
    fun emptyAndOther() {
        assertEquals(0, SpellPlan.parse("TV1|14\n")!!.spells.size)
        assertNull(SpellPlan.parse("TL1|0|0|0"))
    }
}
