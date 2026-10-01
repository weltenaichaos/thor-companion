package thor.companion.strip

/** One bag stack: item id, how many, and the slot's tap key (an [ActionKeys] index) if it has one. */
data class BagItem(val itemId: Int, val count: Int, val key: Int? = null)

/**
 * What the addon reports, parsed from the payload
 * `TC1|name|level|copper|mapID|x|y|free/total|itemID:count[:key],...` (see addon/ThorCompanion/Data.lua).
 * Values the game keeps secret arrive as "?" and come out as null.
 */
data class GameState(
    val name: String,
    val level: Int?,
    val copper: Long?,
    val mapId: Int?,
    val x: Double?,
    val y: Double?,
    val freeSlots: Int?,
    val totalSlots: Int?,
    val items: List<BagItem>,
) {
    val gold: Long? get() = copper?.div(10000)
    val silver: Long? get() = copper?.div(100)?.rem(100)
    val copperPart: Long? get() = copper?.rem(100)

    companion object {
        /** The parsed state, or null when [payload] is not a TC1 payload (or is the addon's error report). */
        fun parse(payload: String): GameState? {
            val f = payload.split('|')
            if (f.size < 9 || f[0] != "TC1") return null
            val slots = f[7].split('/')
            val items = f[8].split(',').mapNotNull { entry ->
                val p = entry.split(':').takeIf { it.size in 2..3 } ?: return@mapNotNull null
                val itemId = p[0].toIntOrNull() ?: return@mapNotNull null
                // The addon cuts the list at an entry boundary when it is too long for the strip.
                BagItem(itemId, p[1].toIntOrNull() ?: return@mapNotNull null, p.getOrNull(2)?.let { it.toIntOrNull() ?: return@mapNotNull null })
            }
            return GameState(
                name = f[1],
                level = f[2].toIntOrNull(),
                copper = f[3].toLongOrNull(),
                mapId = f[4].toIntOrNull()?.takeIf { it != 0 },
                x = f[5].toDoubleOrNull(),
                y = f[6].toDoubleOrNull(),
                freeSlots = slots.getOrNull(0)?.toIntOrNull(),
                totalSlots = slots.getOrNull(1)?.toIntOrNull(),
                items = items,
            )
        }
    }
}
