package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestsTest {
    @Test
    fun parsesTheLog() {
        val log = QuestLog.parse("TL1|185|1|2000\n2\tr\t2000\tThe Boar Hunt\t+8/8 Boar Ribs;Talk to Bob\n3\to\t900\tFar Away\t")!!
        assertEquals(185, log.lastKill)
        assertEquals(1, log.readyCount)
        assertEquals(2000, log.readyXp)
        assertEquals(2, log.quests.size)
        val q = log.quests[0]
        assertTrue(q.ready)
        assertTrue(q.here)
        assertEquals(listOf(Objective("8/8 Boar Ribs", true), Objective("Talk to Bob", false)), q.objectives)
        assertFalse(log.quests[1].here)
        assertEquals(emptyList(), log.quests[1].objectives)
    }

    @Test
    fun readsLevelsAndColours() {
        val log = QuestLog.parse("TL1|0|0|0\n2\tz\t100\tA\t4/8 Ribs\t27\n3\tz\t100\tB\t\t20\n4\tz\t100\tC\t\t12\n5\tz\t100\tD\t")!!
        assertEquals(listOf(27, 20, 12, 0), log.quests.map { it.level })
        assertEquals(listOf("4/8 Ribs"), log.quests[0].objectives.map { it.text })
        assertEquals(Difficulty.HARD, log.quests[0].difficulty(23))
        assertEquals(Difficulty.NORMAL, log.quests[1].difficulty(21))
        assertEquals(Difficulty.TRIVIAL, log.quests[2].difficulty(23))
        assertEquals(Difficulty.NORMAL, log.quests[3].difficulty(23))
    }

    @Test
    fun emptyLog() {
        assertEquals(QuestLog(0, 0, 0, emptyList()), QuestLog.parse("TL1|0|0|0"))
        assertNull(QuestLog.parse("TM1|1|2|3"))
    }

    @Test
    fun countsKillsWithRestedBonus() {
        // 7,780 to go at 185 a kill: 2,100 rested doubles the first 11 kills (11 x 185 = 2,035, then 65 more).
        assertEquals(31, LevelPlan.kills(7780, 185, 2100))
        assertEquals(43, LevelPlan.kills(7780, 185, 0))
        assertNull(LevelPlan.kills(100, 0, 0))
        assertEquals(0, LevelPlan.kills(0, 185, 0))
    }

    @Test
    fun plansTheLevel() {
        val p = LevelPlan.of(4520, 12300, 2100, 185, 5400)!!
        assertEquals(7780, p.toGo)
        assertEquals(36.7, p.percent, 0.05)
        assertEquals(31, p.kills)
        assertEquals(7, p.killsAfterQuests)
        assertFalse(p.questsEnough)
        assertTrue(LevelPlan.of(10000, 12300, 0, 185, 5400)!!.questsEnough)
    }
}
