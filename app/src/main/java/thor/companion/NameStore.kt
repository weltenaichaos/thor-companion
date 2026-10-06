package thor.companion

import android.content.Context
import thor.companion.strip.ItemName
import java.io.File

/**
 * Item names the addon has sent, kept across restarts so each name only has to
 * arrive once. One line per item, tab-separated: id, quality, name, then item
 * level, required level, sell price and type (missing in files from older versions).
 */
class NameStore(context: Context) {
    private val file = File(context.filesDir, "item-names.tsv")
    private val names = HashMap<Int, ItemName>()

    init {
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                val id = p.getOrNull(0)?.toIntOrNull() ?: return@forEachLine
                val q = p.getOrNull(1)?.toIntOrNull() ?: return@forEachLine
                names[id] = ItemName(
                    p.getOrNull(2) ?: return@forEachLine, q, p.getOrNull(3)?.toIntOrNull() ?: 0,
                    p.getOrNull(4)?.toIntOrNull() ?: 0, p.getOrNull(5)?.toLongOrNull() ?: 0, p.getOrNull(6).orEmpty(),
                )
            }
        }
    }

    operator fun get(id: Int): ItemName? = synchronized(names) { names[id] }

    /** Adds [page]; true when anything was new or changed. */
    fun addAll(page: Map<Int, ItemName>): Boolean = synchronized(names) {
        var changed = false
        for ((id, n) in page) if (names.put(id, n) != n) changed = true
        if (changed) runCatching {
            file.writeText(names.entries.joinToString("") { (id, n) ->
                listOf(id, n.quality, n.name, n.itemLevel, n.requiredLevel, n.sellPrice, n.type)
                    .joinToString("\t") { it.toString().replace('\t', ' ') } + "\n"
            })
        }
        changed
    }
}
