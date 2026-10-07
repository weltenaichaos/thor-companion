package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessTest {
    private val tp = "TP1|Warlock|WARLOCK|Undead|100|1000|0|20.5|||"
    private val tq = "TQ1|1:123"
    private val tm = "TM1|1411|Durotar|Kalimdor|4000|2700\nQ:0.5:0.5:x"
    private val tl = "TL1|0|0|0|2\n1\tz\t5\tA\t\t1\t\t"
    private val tl2 = "TL2|2|2\n2\tz\t5\tB\t\t1\t\t"
    private val tv = "TV1|14||\n15\tf\t0\tX\t"
    private val tb = "TB1|10/20|100:1,200:2"

    private fun status(sums: String) =
        GameState.parse(tb, GameState.parse("TS1|Jesse|14|0|1411|0.5|0.5|0|s1|${GameState.hash(tb)}|0.18.7|$sums"))

    private fun all() = "5:${GameState.hash(tm)},6:${GameState.hash(tp)},7:${GameState.hash(tq)},8:${GameState.hash(tl)},10:${GameState.hash(tv)},11:${GameState.hash(tl2)}"

    @Test
    fun keptDataThatStillMatchesIsReady() {
        val kept = mapOf("TP1|" to tp, "TQ1|" to tq, "TM1|" to tm, "TL1|" to tl, "TL2|2" to tl2, "TV1|" to tv, "TB1|" to tb)
        val parts = Readiness.parts(status(all()), kept, bagsFresh = true) { true }!!
        assertTrue(Readiness.done(parts), parts.toString())
    }

    @Test
    fun itemNamesDoNotHoldTheCard() {
        val kept = mapOf("TP1|" to tp, "TQ1|" to tq, "TM1|" to tm, "TL1|" to tl, "TL2|2" to tl2, "TV1|" to tv, "TB1|" to tb)
        val parts = Readiness.parts(status(all()), kept, bagsFresh = true) { false }!!
        assertEquals(Readiness.Status.LOADING, parts.first { it.title == "Item names" }.status)
        assertTrue(Readiness.done(parts), parts.toString())
    }

    @Test
    fun whatCameAfterTheStatusCountsAtOnce() {
        // A new character: the status came first, when nothing else had gone out yet.
        val kept = mapOf("TP1|" to tp, "TQ1|" to tq, "TM1|" to tm, "TB1|" to tb)
        val parts = Readiness.parts(status("5:0,6:0,7:0,8:0,10:0,11:0"), kept, bagsFresh = true, fresh = setOf("TP1|", "TQ1|", "TM1|")) { true }!!
        val byTitle = parts.associateBy { it.title }
        assertEquals(Readiness.Status.READY, byTitle["Character and gear"]!!.status)
        assertEquals(Readiness.Status.READY, byTitle["Map"]!!.status)
        assertEquals(Readiness.Status.MISSING, byTitle["Quests"]!!.status)
    }

    @Test
    fun missingAndChangedParts() {
        val kept = mapOf("TP1|" to tp, "TQ1|" to "TQ1|old", "TL1|" to tl, "TB1|" to tb)
        val parts = Readiness.parts(status(all().replace(Regex("10:\\d+"), "10:0")), kept, bagsFresh = true) { it == 100 }!!
        val byTitle = parts.associateBy { it.title }
        assertEquals(Readiness.Status.LOADING, byTitle["Character and gear"]!!.status)
        assertEquals("1 of 2", byTitle["Item names"]!!.detail)
        assertEquals(Readiness.Status.LOADING, byTitle["Map"]!!.status)
        assertEquals("page 1 of 2", byTitle["Quests"]!!.detail)
        assertEquals(Readiness.Status.MISSING, byTitle["Spells to learn"]!!.status)
    }

    @Test
    fun olderAddonsHaveNothingToWaitFor() {
        assertNull(Readiness.parts(GameState.parse("TS1|Jesse|14|0|1411|0.5|0.5|0|s1|5|0.18.6"), emptyMap(), true) { true })
    }
}
