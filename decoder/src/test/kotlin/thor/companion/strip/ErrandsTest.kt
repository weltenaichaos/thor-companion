package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrandsTest {
    @Test
    fun groupsTurnInsByTownAndAddsTheTrainer() {
        val log = QuestLog.parse(
            "TL1|0|3|3200|1\n" +
                "1\tr\t1250\tSupplies\t\t14\t1413:0.515:0.304:The Barrens\tCrossroads\n" +
                "2\tr\t1100\tRaptor Thieves\t\t13\t1413:0.52:0.31:The Barrens\tCrossroads\n" +
                "3\tr\t850\tForgotten Pools\t\t12\t1454:0.5:0.5:Orgrimmar\tOrgrimmar\n" +
                "4\tz\t500\tHarpy Raiders\t\t15\t\t\n" +
                "5\tr\t200\tMystery\t\t10\t\t",
        )!!
        val spells = SpellPlan.parse("TV1|14|1454:0.4:0.6:Orgrimmar|Orgrimmar\n14\tr\t1200\tCorruption\tRank 2")!!
        val stops = Errands.of(log, spells)
        assertEquals(listOf("Crossroads", "Orgrimmar", "Turn-in place not known"), stops.map { it.name })
        assertEquals(2350, stops[0].xp)
        assertEquals("The Barrens", stops[0].zone)
        assertEquals("", stops[1].zone)
        assertTrue(stops[1].errands.any { it.trainer && it.cost == 1200L })
        assertEquals(1454, spells.trainerAt?.mapId)
    }

    @Test
    fun nothingToDo() {
        assertEquals(0, Errands.of(null, null).size)
        assertEquals(0, Errands.of(QuestLog.parse("TL1|0|0|0\n1\tz\t5\tA\t\t1\t\t")!!, SpellPlan.parse("TV1|14||\n15\tf\t0\tX\t"))
            .size)
    }
}
