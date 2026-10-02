package thor.companion.strip

/** An item's name and quality (0 poor, 1 common, 2 uncommon, 3 rare, 4 epic, 5 legendary, ...). */
data class ItemName(val name: String, val quality: Int)

/**
 * A page of item names, from the payload `TN1|itemID,quality,name<newline>...`
 * (see addon/ThorCompanion/Data.lua). Names may contain commas, so only the first
 * two commas of an entry split it.
 */
object ItemNames {
    fun parse(payload: String): Map<Int, ItemName>? {
        if (!payload.startsWith("TN1|")) return null
        val out = HashMap<Int, ItemName>()
        for (entry in payload.substring(4).split('\n')) {
            val parts = entry.split(',', limit = 3)
            if (parts.size != 3 || parts[2].isEmpty()) continue
            val id = parts[0].toIntOrNull() ?: continue
            out[id] = ItemName(parts[2], parts[1].toIntOrNull() ?: 1)
        }
        return out
    }
}
