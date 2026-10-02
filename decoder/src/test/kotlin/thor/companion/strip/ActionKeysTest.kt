package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionKeysTest {
    /** The same entries the addon's Lua list has (checked against Actions.lua in the mock game). */
    @Test
    fun matchesTheAddon() {
        assertEquals(109, ActionKeys.all.size)
        assertEquals("CTRL-F1", ActionKeys.forIndex(1))
        assertEquals("ALT-F6", ActionKeys.forIndex(17))
        assertEquals("ALT-F12", ActionKeys.forIndex(23))
        assertEquals("CTRL-SHIFT-F1", ActionKeys.forIndex(24))
        assertEquals("CTRL-NUMPAD0", ActionKeys.forIndex(60))
        assertEquals("ALT-SHIFT-NUMPAD9", ActionKeys.forIndex(109))
        assertNull(ActionKeys.forIndex(0))
        assertNull(ActionKeys.forIndex(110))
    }
}
