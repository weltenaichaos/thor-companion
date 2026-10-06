package thor.companion.strip

/**
 * What came of tapping a bag item, from the addon's `TU1|n|itemID|result` (Actions.lua):
 * [result] is "ok", "nouse" (the item does nothing when used) or the game's error text.
 * [n] counts taps, so the same result is not shown twice.
 */
data class ItemUse(val n: Int, val itemId: Int, val result: String) {
    companion object {
        fun parse(payload: String): ItemUse? {
            val f = payload.split('|', limit = 4)
            if (f[0] != "TU1" || f.size < 4) return null
            return ItemUse(f[1].toIntOrNull() ?: return null, f[2].toIntOrNull() ?: return null, f[3])
        }
    }
}

/**
 * Bag items on cooldown, from the addon's `TC2|now|itemID:readyAt,...`: game times in
 * seconds, so what is left is readyAt - now when the message was sent.
 */
object Cooldowns {
    /** Seconds left per item at the time of the message, or null when it isn't a TC2 message. */
    fun parse(payload: String): Map<Int, Long>? {
        val f = payload.split('|')
        if (f[0] != "TC2" || f.size < 3) return null
        val now = f[1].toLongOrNull() ?: return null
        return f[2].split(',').mapNotNull { e ->
            val p = e.split(':')
            val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val ready = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            (id to ready - now).takeIf { it.second > 0 }
        }.toMap()
    }

    /** "12 min", "45 s", "2 h 5 min": a cooldown's time left, as the game would say it. */
    fun left(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600} h" + (seconds % 3600 / 60).let { if (it > 0) " $it min" else "" }
        seconds >= 60 -> "${(seconds + 59) / 60} min"
        else -> "$seconds s"
    }
}
