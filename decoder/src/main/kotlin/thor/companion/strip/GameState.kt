package thor.companion.strip

/** One bag stack: item id, how many, and the slot's tap key (an [ActionKeys] index) if it has one. */
data class BagItem(val itemId: Int, val count: Int, val key: Int? = null)

/**
 * What the addon reports, put together from its `TS1|name|level|copper|mapID|x|y[|facing|session|bagHash|version]` and
 * `TB1|free/total|itemID:count[:key],...` messages (see addon/ThorCompanion/Data.lua).
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
    /** Which way the character faces, in radians: 0 is north, counter-clockwise. Null where the game hides it. */
    val facing: Double? = null,
    /** Changes with every login or /reload in the game. */
    val session: String? = null,
    /** The checksum ([hash]) of the bags message the addon sent last, to tell whether kept bags are still right. */
    val bagHash: Int? = null,
    /** The addon's version, from its .toc; null from older addons, which didn't send it. */
    val addonVersion: String? = null,
) {
    val gold: Long? get() = copper?.div(10000)
    val silver: Long? get() = copper?.div(100)?.rem(100)
    val copperPart: Long? get() = copper?.rem(100)

    companion object {
        /** The addon's checksum of a message (Data.lua): over its UTF-8 bytes, h = (h * 31 + b) % 65536. */
        fun hash(message: String): Int {
            var h = 0
            for (b in message.toByteArray(Charsets.UTF_8)) h = (h * 31 + (b.toInt() and 0xFF)) % 65536
            return h
        }

        private val EMPTY = GameState("?", null, null, null, null, null, null, null, emptyList())

        /**
         * The state after [payload], starting from [previous]: TS1 (character and position)
         * and TB1 (bags) each replace their part, TC1 (older addons) replaces everything.
         * Null when [payload] is none of these (or is the addon's error report).
         */
        fun parse(payload: String, previous: GameState? = null): GameState? {
            val f = payload.split('|')
            val base = previous ?: EMPTY
            return when {
                f[0] == "TS1" && f.size >= 7 && f[1] != "error" -> status(base, f, 1)
                f[0] == "TB1" && f.size >= 3 -> bags(base, f[1], f[2])
                f[0] == "TC1" && f.size >= 9 -> bags(status(base, f, 1), f[7], f[8])
                else -> null
            }
        }

        private fun status(base: GameState, f: List<String>, i: Int) = base.copy(
            name = f[i],
            level = f[i + 1].toIntOrNull(),
            copper = f[i + 2].toLongOrNull(),
            mapId = f[i + 3].toIntOrNull()?.takeIf { it != 0 },
            x = f[i + 4].toDoubleOrNull(),
            y = f[i + 5].toDoubleOrNull(),
            facing = if (f[0] == "TS1") f.getOrNull(i + 6)?.toDoubleOrNull() else null,
            session = f.getOrNull(i + 7)?.takeIf { it.isNotEmpty() },
            bagHash = f.getOrNull(i + 8)?.toIntOrNull()?.takeIf { it != 0 },
            addonVersion = if (f[0] == "TS1") f.getOrNull(i + 9)?.takeIf { it.isNotEmpty() } else null,
        )

        private fun bags(base: GameState, slotField: String, list: String): GameState {
            val slots = slotField.split('/')
            val items = list.split(',').mapNotNull { entry ->
                val p = entry.split(':').takeIf { it.size in 2..3 } ?: return@mapNotNull null
                val itemId = p[0].toIntOrNull() ?: return@mapNotNull null
                // The addon cuts the list at an entry boundary when it is too long.
                BagItem(itemId, p[1].toIntOrNull() ?: return@mapNotNull null, p.getOrNull(2)?.let { it.toIntOrNull() ?: return@mapNotNull null })
            }
            return base.copy(
                freeSlots = slots.getOrNull(0)?.toIntOrNull(),
                totalSlots = slots.getOrNull(1)?.toIntOrNull(),
                items = items,
            )
        }
    }
}
