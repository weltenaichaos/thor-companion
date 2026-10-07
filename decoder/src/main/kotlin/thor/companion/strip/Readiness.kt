package thor.companion.strip

/**
 * What the app's start-up card shows: for each part of the game's data, whether the
 * app has it as the game last sent it. Kept messages (from before a restart) count
 * as soon as the addon's checksums in TS1 say they are still the same.
 */
object Readiness {
    enum class Status { READY, LOADING, MISSING }

    /** [needed]: the card waits for it; item names fill in while you already use the tabs. */
    data class Part(val title: String, val status: Status, val detail: String = "", val needed: Boolean = true)

    /**
     * The parts, from [state] (the latest TS1 and bags), the messages the app has by
     * kind ([kept], "TP1|" and so on, the quest pages as "TL2|2", "TL2|3", ...),
     * whether the bags are confirmed ([bagsFresh]) and which item ids have names.
     * Null when the addon sends no checksums (older than 0.18.7): then there is nothing to wait for.
     */
    fun parts(state: GameState?, kept: Map<String, String>, bagsFresh: Boolean, hasName: (Int) -> Boolean): List<Part>? {
        val sums = state?.sums ?: return null
        fun same(kind: String, number: Int): Boolean? {
            val sent = sums[number] ?: return null
            if (sent == 0) return null
            val have = kept[kind] ?: return false
            return GameState.hash(have) == sent
        }
        fun status(vararg checks: Boolean?) = when {
            checks.all { it == true } -> Status.READY
            checks.any { it == false } -> Status.LOADING
            else -> Status.MISSING
        }
        fun note(s: Status, ready: String = "") = when (s) {
            Status.READY -> ready
            Status.LOADING -> "loading"
            Status.MISSING -> "not sent yet"
        }

        val parts = mutableListOf<Part>()
        val character = status(same("TP1|", 6), same("TQ1|", 7))
        parts += Part("Character and gear", character, note(character, "up to date"))

        val hasBags = state.freeSlots != null
        val bags = when {
            bagsFresh -> Status.READY
            hasBags || kept["TB1|"] != null -> Status.LOADING
            else -> Status.MISSING
        }
        parts += Part("Bags", bags, note(bags, "up to date"))

        val ids = state.items.map { it.itemId }.distinct()
        val named = ids.count(hasName)
        val names = when {
            bags != Status.READY -> Status.MISSING
            named == ids.size -> Status.READY
            else -> Status.LOADING
        }
        parts += Part("Item names", names, when (names) {
            Status.MISSING -> "after the bags"
            else -> "$named of ${ids.size}"
        }, needed = false)

        val mapSame = same("TM1|", 5)
        val zone = kept["TM1|"]?.let { ZoneMap.parse(it) }?.zone.orEmpty()
        val map = status(mapSame)
        parts += Part("Map", map, note(map, zone))

        val first = kept["TL1|"]?.let { QuestLog.parse(it) }
        val pages = first?.pages ?: 1
        val pagesSame: Boolean? = if (pages <= 1) true else {
            val sent = sums[11]
            val have = (2..pages).map { kept["TL2|$it"] }
            when {
                sent == null || sent == 0 -> null
                have.any { it == null } -> false
                else -> GameState.hash(have.joinToString("")) == sent
            }
        }
        val quests = status(same("TL1|", 8), pagesSame)
        val questNote = if (quests == Status.LOADING && pages > 1) {
            val got = 1 + (2..pages).count { kept["TL2|$it"] != null }
            "page ${got.coerceAtMost(pages)} of $pages"
        } else note(quests)
        parts += Part("Quests", quests, questNote)

        val spells = status(same("TV1|", 10))
        parts += Part("Spells to learn", spells, note(spells))
        return parts
    }

    /** True when every part the card waits for is there. */
    fun done(parts: List<Part>) = parts.all { it.status == Status.READY || !it.needed }
}
