package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemTipsTest {
    @Test
    fun readsLinesPerItem() {
        val tips = ItemTips.parse("TT1|2007\tBinds when picked up\tUse: Restores 100 health.\n6948\n2014\t+5 Stamina")!!
        assertEquals(listOf("Binds when picked up", "Use: Restores 100 health."), tips[2007])
        assertEquals(emptyList(), tips[6948])
        assertEquals(listOf("+5 Stamina"), tips[2014])
    }

    @Test
    fun ignoresOtherMessages() {
        assertNull(ItemTips.parse("TN2|1\t1"))
    }
}
