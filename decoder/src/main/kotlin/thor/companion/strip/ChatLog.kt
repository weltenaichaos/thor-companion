package thor.companion.strip

/** One chat line as the addon sends it (see addon/ThorCompanion/Chat.lua). */
data class ChatLine(val kind: String, val sender: String, val text: String)

/**
 * The chat lines the app has received, oldest first, from the addon's
 * `TH1|<session>\n<id>\t<kind>\t<sender>\t<text>\n...` messages. Lines sent again
 * (after a refresh) are recognised by session and number and kept once.
 */
class ChatLog(private val keep: Int = 200) {
    private val seen = HashSet<String>()
    private val order = ArrayDeque<String>()
    private val _lines = ArrayDeque<ChatLine>()
    val lines: List<ChatLine> get() = _lines

    /** Adds the lines of [payload]; true when there was anything new. Null when it isn't a TH1 message. */
    fun add(payload: String): Boolean? {
        if (!payload.startsWith("TH1|")) return null
        val rows = payload.substring(4).split('\n')
        val session = rows.first()
        var added = false
        for (row in rows.drop(1)) {
            val f = row.split('\t', limit = 4)
            if (f.size < 4) continue
            val key = session + ":" + f[0]
            if (!seen.add(key)) continue
            order.addLast(key)
            _lines.addLast(ChatLine(f[1], f[2], f[3]))
            added = true
            if (_lines.size > keep) {
                _lines.removeFirst()
                seen.remove(order.removeFirst())
            }
        }
        return added
    }
}
