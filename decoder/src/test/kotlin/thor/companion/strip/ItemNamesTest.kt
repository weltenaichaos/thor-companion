package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemNamesTest {

    @Test
    fun parsesPage() {
        val names = ItemNames.parse("TN1|4604,1,Forest Mushroom Cap\n159,1,Refreshing Spring Water\n1,4,Tome of Ice, Vol. II")!!
        assertEquals(ItemName("Forest Mushroom Cap", 1), names[4604])
        assertEquals(ItemName("Tome of Ice, Vol. II", 4), names[1])
        assertEquals(3, names.size)
    }

    @Test
    fun skipsBrokenEntries() {
        val names = ItemNames.parse("TN1|x,1,Bad\n7,2,\n8,3,Good")!!
        assertEquals(mapOf(8 to ItemName("Good", 3)), names)
    }

    @Test
    fun rejectsOtherPayloads() {
        assertNull(ItemNames.parse("TC1|Xandra|5"))
    }
}

class ItemDetailsTest {
    @Test
    fun parsesDetails() {
        val names = ItemNames.parse("TN2|2589\t1\t5\t0\t13\tTradeskill / Cloth\tLinen Cloth\n6948\t1\t1\t0\t0\tMiscellaneous\tHearthstone")!!
        assertEquals(ItemName("Linen Cloth", 1, 5, 0, 13, "Tradeskill / Cloth"), names[2589])
        assertEquals("Hearthstone", names[6948]?.name)
    }
}
