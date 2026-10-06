package thor.companion.strip

/**
 * Tooltip lines of items (see addon/ThorCompanion/Tooltips.lua):
 * `TT1|itemID\tline\tline...\nitemID\t...`, without the name and the sell price.
 */
object ItemTips {
    /** Item id to its lines, or null when this isn't a TT1 message. */
    fun parse(message: String): Map<Int, List<String>>? {
        if (!message.startsWith("TT1|")) return null
        val out = LinkedHashMap<Int, List<String>>()
        for (row in message.substring(4).split('\n')) {
            val f = row.split('\t')
            val id = f[0].toIntOrNull() ?: continue
            out[id] = f.drop(1).filter { it.isNotBlank() }
        }
        return out
    }
}
