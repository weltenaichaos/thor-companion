package thor.companion.strip

/** One quest in the log: ready to turn in, in this zone, the experience it gives and its objectives. */
/** Where the game's own arrow points next for a quest: the turn-in once it is ready. */
data class Waypoint(val mapId: Int, val x: Double, val y: Double, val zone: String) {
    companion object {
        /** From the addon's "mapID:x:y:zone", or null when it is empty or damaged. */
        fun parse(field: String?): Waypoint? {
            val f = field.orEmpty().split(':', limit = 4)
            if (f.size < 3) return null
            return Waypoint(
                f[0].toIntOrNull() ?: return null,
                f[1].toDoubleOrNull() ?: return null,
                f[2].toDoubleOrNull() ?: return null,
                f.getOrElse(3) { "" }.trim(),
            )
        }
    }
}

data class Quest(
    val id: Int, val ready: Boolean, val here: Boolean, val xp: Long, val title: String, val objectives: List<Objective>,
    /** The quest's level, 0 when the addon didn't say. */
    val level: Int = 0,
    /** Where to go next, also when that is in another zone; null when the game didn't say. */
    val waypoint: Waypoint? = null,
) {
    /** How hard it is for you at [playerLevel], like the game's quest colours. */
    fun difficulty(playerLevel: Int): Difficulty = when {
        level <= 0 || playerLevel <= 0 -> Difficulty.NORMAL
        level - playerLevel >= 5 -> Difficulty.VERY_HARD
        level - playerLevel >= 3 -> Difficulty.HARD
        level - playerLevel >= -2 -> Difficulty.NORMAL
        level >= playerLevel - greenRange(playerLevel) -> Difficulty.EASY
        else -> Difficulty.TRIVIAL
    }

    private fun greenRange(playerLevel: Int) = when {
        playerLevel <= 9 -> 5
        playerLevel <= 19 -> 6
        playerLevel <= 29 -> 7
        playerLevel <= 39 -> 8
        else -> 9
    }
}

/** The game's quest colours: red, orange, yellow, green, grey. */
enum class Difficulty { VERY_HARD, HARD, NORMAL, EASY, TRIVIAL }

/** An objective in the game's words ("4/8 Boar Ribs"), and whether it is done. */
data class Objective(val text: String, val done: Boolean)

/**
 * The quest log from the addon's
 * `TL1|lastKill|readyCount|readyXP|pages\n<id>\t<r|z|o>\t<xp>\t<title>\t<objective>;<objective>...\t<level>\t<waypoint>`
 * (see addon/ThorCompanion/Quests.lua). [lastKill] is the experience of the last kill
 * without its rested bonus, 0 when not known yet. The ready numbers cover the whole
 * log. A log too long for one message comes in [pages]: this is page 1, the others
 * arrive as TL2 (see [QuestPages]).
 */
data class QuestLog(val lastKill: Long, val readyCount: Int, val readyXp: Long, val quests: List<Quest>, val pages: Int = 1) {
    companion object {
        fun parse(payload: String): QuestLog? {
            if (!payload.startsWith("TL1|")) return null
            val rows = payload.substring(4).split('\n')
            val head = rows[0].split('|')
            if (head.size < 3) return null
            return QuestLog(head[0].toLongOrNull() ?: 0, head[1].toIntOrNull() ?: 0, head[2].toLongOrNull() ?: 0, rows(rows.drop(1)),
                head.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
        }

        internal fun rows(rows: List<String>): List<Quest> = rows.mapNotNull { row ->
            val f = row.split('\t', limit = 7)
            if (f.size < 4) return@mapNotNull null
            val objectives = f.getOrNull(4).orEmpty().split(';').filter { it.isNotEmpty() }
                .map { if (it.startsWith("+")) Objective(it.substring(1), true) else Objective(it, false) }
            Quest(f[0].toIntOrNull() ?: return@mapNotNull null, f[1] == "r", f[1] != "o", f[2].toLongOrNull() ?: 0, f[3], objectives,
                f.getOrNull(5)?.trim()?.toIntOrNull() ?: 0, Waypoint.parse(f.getOrNull(6)))
            }
    }
}

/**
 * The quest log's further pages, from the addon's `TL2|<page>|<pages>\n<rows like TL1's>`,
 * put together with the first page (TL1) into the whole log.
 */
class QuestPages {
    private val pages = HashMap<Int, List<Quest>>()

    /** Keeps the page in [payload]; true when it changed. Null when it isn't a TL2 message. */
    fun add(payload: String): Boolean? {
        if (!payload.startsWith("TL2|")) return null
        val rows = payload.substring(4).split('\n')
        val head = rows[0].split('|')
        val page = head.getOrNull(0)?.toIntOrNull() ?: return false
        val quests = QuestLog.rows(rows.drop(1))
        return pages.put(page, quests) != quests
    }

    /** [first] with the quests of its other pages after its own, each quest once. */
    fun whole(first: QuestLog): QuestLog {
        if (first.pages <= 1) return first
        val all = first.quests + (2..first.pages).flatMap { pages[it].orEmpty() }
        return first.copy(quests = all.distinctBy { it.id })
    }
}

/**
 * What it takes to reach the next level: experience to go, and how many kills
 * that is with the last kill's experience, counting the rested bonus (a kill gives
 * double while rested lasts, and uses up the bonus part of the rested pool).
 */
data class LevelPlan(val toGo: Long, val percent: Double, val kills: Int?, val killsAfterQuests: Int?, val questsEnough: Boolean) {
    companion object {
        fun of(xp: Long, xpMax: Long, rested: Long, lastKill: Long, readyXp: Long): LevelPlan? {
            if (xpMax <= 0) return null
            val toGo = (xpMax - xp).coerceAtLeast(0)
            val afterQuests = (toGo - readyXp).coerceAtLeast(0)
            return LevelPlan(
                toGo, xp * 100.0 / xpMax,
                kills(toGo, lastKill, rested), kills(afterQuests, lastKill, rested), readyXp >= toGo && readyXp > 0,
            )
        }

        /** Kills for [need] experience at [perKill] each, or null when the kill experience is unknown. */
        fun kills(need: Long, perKill: Long, rested: Long): Int? {
            if (perKill <= 0) return null
            var left = need
            var pool = rested
            var n = 0
            while (left > 0 && n < 100_000) {
                val bonus = minOf(perKill, pool)
                left -= perKill + bonus
                pool -= bonus
                n++
            }
            return n
        }
    }
}
