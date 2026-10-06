package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeltaTest {
    private val bags = "TB1|3/160|2007:1:1,2014:2:2"

    private fun td(old: String, new: String, at: Int, cut: Int, text: String) =
        "TD1|${old.take(3)}|${GameState.hash(old)}|${GameState.hash(new)}|$at|$cut|$text"

    @Test
    fun putsTheChangeIn() {
        val new = "TB1|2/160|2007:1:1,2014:3:2,2021:1:3"
        // As the addon builds it: common start "TB1|", common end none.
        val d = td(bags, new, 4, bags.length - 4, new.substring(4))
        assertEquals(new, Delta.apply(d) { if (it == "TB1|") bags else null })
    }

    @Test
    fun aSmallCutInTheMiddle() {
        val old = "TL1|120|1|500\n5\tz\t250\tBoars\t4/8 Boar Ribs"
        val new = "TL1|120|1|500\n5\tz\t250\tBoars\t5/8 Boar Ribs"
        val at = old.indexOf("4/8")
        assertEquals(new, Delta.apply(td(old, new, at, 1, "5")) { old })
    }

    @Test
    fun countsBytesNotCharacters() {
        val old = "TL1|0|0|0\n1\to\t0\tGrüne Wälder\tbar"
        val new = "TL1|0|0|0\n1\to\t0\tGrüne Wälder\tbaz"
        val at = old.toByteArray().size - 1
        assertEquals(new, Delta.apply(td(old, new, at, 1, "z")) { old })
    }

    @Test
    fun ignoresAChangeForAnotherCopy() {
        val d = td(bags, "TB1|x", 4, 5, "x")
        assertNull(Delta.apply(d) { "TB1|1/160|" })
        assertNull(Delta.apply(d) { null })
    }
}
