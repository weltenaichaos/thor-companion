package thor.companion

import android.content.Context
import java.io.File

/**
 * Item tooltip lines the addon has sent (Tooltips.lua), kept across restarts so each
 * arrives only once. One line per item: id, then its lines, tab-separated.
 */
class TipStore(context: Context) {
    private val file = File(context.filesDir, "item-tips.tsv")
    private val tips = HashMap<Int, List<String>>()

    init {
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                val id = p[0].toIntOrNull() ?: return@forEachLine
                tips[id] = p.drop(1)
            }
        }
    }

    operator fun get(id: Int): List<String>? = synchronized(tips) { tips[id] }

    /** Adds [page]; true when anything was new or changed. */
    fun addAll(page: Map<Int, List<String>>): Boolean = synchronized(tips) {
        var changed = false
        for ((id, lines) in page) if (tips.put(id, lines) != lines) changed = true
        if (changed) runCatching {
            file.writeText(tips.entries.joinToString("") { (id, lines) -> (listOf(id.toString()) + lines).joinToString("\t") + "\n" })
        }
        changed
    }
}
