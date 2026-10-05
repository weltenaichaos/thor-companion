package thor.companion.strip

/** A class spell to learn soon: the level it needs, whether your trainer offers it now, and its cost in copper (0 when not known). */
data class SpellToLearn(val level: Int, val ready: Boolean, val cost: Long, val name: String, val rank: String)

/**
 * The spells to learn soon, from the addon's
 * `TV1|level|<trainer>|<town>\n<level>\t<r|f>\t<cost>\t<name>\t<rank>\n...` (see addon/ThorCompanion/Spells.lua).
 * [level] is your level when the addon worked it out; [trainerAt] is where you last
 * opened your class trainer and [trainerTown] the town there (null and empty until then).
 */
data class SpellPlan(val level: Int, val spells: List<SpellToLearn>, val trainerAt: Waypoint? = null, val trainerTown: String = "") {
    val ready: List<SpellToLearn> get() = spells.filter { it.ready }
    /** The spells for each coming level, nearest first. */
    val later: Map<Int, List<SpellToLearn>> get() = spells.filter { !it.ready }.groupBy { it.level }.toSortedMap()
    /** What the spells ready now cost together. */
    val readyCost: Long get() = ready.sumOf { it.cost }

    companion object {
        fun parse(payload: String): SpellPlan? {
            if (!payload.startsWith("TV1|")) return null
            val rows = payload.substring(4).split('\n')
            val head = rows[0].split('|')
            val level = head[0].trim().toIntOrNull() ?: return null
            val spells = rows.drop(1).mapNotNull { row ->
                val f = row.split('\t')
                if (f.size < 4) return@mapNotNull null
                SpellToLearn(f[0].toIntOrNull() ?: return@mapNotNull null, f[1] == "r", f[2].toLongOrNull() ?: 0, f[3], f.getOrElse(4) { "" })
            }
            return SpellPlan(level, spells, Waypoint.parse(head.getOrNull(1)), head.getOrNull(2)?.trim().orEmpty())
        }
    }
}
