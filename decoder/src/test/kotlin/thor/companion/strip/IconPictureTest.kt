package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IconPictureTest {
    @Test
    fun parsesTheIcons() {
        val p = IconPicture.parse("TI1|-400|120|40|48|12|3|6948,2589,117")!!
        assertEquals(IconPicture(-400, 120, 40, 48, 12, 3, listOf(6948, 2589, 117)), p)
    }

    @Test
    fun rejectsOtherMessages() {
        assertNull(IconPicture.parse("TW1|1420|1|2|3|4|3"))
        assertNull(IconPicture.parse("TI1|1|2|3|4|5|6|"))
        assertNull(IconPicture.parse("TI1|1|2|0|4|5|6|7"))
    }

    @Test
    fun placesEachIconOnTheScreen() {
        // Cells of 3 game pixels drawn 4.5 screen pixels wide: the screen is 1.5x the game.
        val frame = StripFrame(4, 1, 0, 1, true, ByteArray(0), 10.0, 4.5, row = 102, x = 1000.0)
        val p = IconPicture(-400, 100, 40, 48, 12, 3, List(14) { it })
        // The square's top is half a cell above the sync row: 102 - 2.25.
        assertContentEquals(intArrayOf(400, 250, 460, 310), p.onScreen(frame, 0))
        // The 14th icon: second row, second column.
        assertContentEquals(intArrayOf(472, 322, 532, 382), p.onScreen(frame, 13))
    }
}
