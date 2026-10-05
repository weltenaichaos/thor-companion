package thor.companion.strip

/** Something to do at a stop: hand in a quest (with its experience), or learn spells at your class trainer (with their cost). */
data class Errand(val title: String, val xp: Long = 0, val cost: Long = 0, val trainer: Boolean = false, val quest: Quest? = null)

/**
 * A town (or zone, when no town is near) with what you can do there now: [name] is
 * the town, [zone] its zone, [at] where on the map (the first errand's spot).
 */
data class ErrandStop(val name: String, val zone: String, val at: Waypoint?, val errands: List<Errand>) {
    val xp: Long get() = errands.sumOf { it.xp }
}

/**
 * The quests ready to turn in, grouped by the town where they are handed in, and
 * your class trainer where you last opened it when spells are ready there: a turn-in
 * planner. Quests whose turn-in the game doesn't place go together under "Turn-in
 * place not known".
 */
object Errands {
    const val UNKNOWN = "Turn-in place not known"

    fun of(log: QuestLog?, spells: SpellPlan?): List<ErrandStop> {
        data class Key(val name: String, val zone: String)
        val stops = LinkedHashMap<Key, MutableList<Errand>>()
        val at = HashMap<Key, Waypoint?>()
        fun add(name: String, zone: String, where: Waypoint?, errand: Errand) {
            val key = Key(name, zone)
            stops.getOrPut(key) { mutableListOf() }.add(errand)
            if (at[key] == null) at[key] = where
        }
        for (q in log?.quests.orEmpty().filter { it.ready }) {
            val w = q.waypoint
            val zone = w?.zone.orEmpty()
            val name = q.place.ifEmpty { zone.ifEmpty { UNKNOWN } }
            add(name, if (name == zone) "" else zone, w, Errand(q.title, xp = q.xp, quest = q))
        }
        val ready = spells?.ready.orEmpty()
        if (ready.isNotEmpty()) {
            val w = spells?.trainerAt
            val zone = w?.zone.orEmpty()
            val name = spells?.trainerTown.orEmpty().ifEmpty { zone.ifEmpty { "Your class trainer" } }
            val what = if (ready.size == 1) "1 spell ready (${ready[0].name})" else "${ready.size} spells ready"
            add(name, if (name == zone) "" else zone, w, Errand("Class trainer: $what", cost = ready.sumOf { it.cost }, trainer = true))
        }
        return stops.map { (k, list) -> ErrandStop(k.name, k.zone, at[k], list) }
    }
}
