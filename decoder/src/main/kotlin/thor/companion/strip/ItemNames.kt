package thor.companion.strip

/**
 * An item's name and quality (0 poor, 1 common, 2 uncommon, 3 rare, 4 epic, 5 legendary, ...),
 * and its details when the addon sent them: item level, required level, sell price in
 * copper and type ("Armor / Cloth"). Zero or empty when unknown.
 */
data class ItemName(
    val name: String,
    val quality: Int,
    val itemLevel: Int = 0,
    val requiredLevel: Int = 0,
    val sellPrice: Long = 0,
    val type: String = "",
)

/**
 * A page of item names, from the payload
 * `TN2|itemID<tab>quality<tab>itemLevel<tab>requiredLevel<tab>sellPrice<tab>type<tab>name<newline>...`
 * (see addon/ThorCompanion/Data.lua), or the older `TN1|itemID,quality,name<newline>...`,
 * where names may contain commas, so only the first two commas of an entry split it.
 */
object ItemNames {
    fun parse(payload: String): Map<Int, ItemName>? {
        val tabs = payload.startsWith("TN2|")
        if (!tabs && !payload.startsWith("TN1|")) return null
        val out = HashMap<Int, ItemName>()
        for (entry in payload.substring(4).split('\n')) {
            if (tabs) {
                val p = entry.split('\t', limit = 7)
                if (p.size != 7 || p[6].isEmpty()) continue
                val id = p[0].toIntOrNull() ?: continue
                out[id] = ItemName(p[6], p[1].toIntOrNull() ?: 1, p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0, p[5])
            } else {
                val p = entry.split(',', limit = 3)
                if (p.size != 3 || p[2].isEmpty()) continue
                val id = p[0].toIntOrNull() ?: continue
                out[id] = ItemName(p[2], p[1].toIntOrNull() ?: 1)
            }
        }
        return out
    }
}
