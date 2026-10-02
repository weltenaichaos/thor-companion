package thor.companion.strip

/**
 * One chat line as the addon sends it (see addon/ThorCompanion/Chat.lua). [sender] is
 * Name-Realm as the game gives it; [channel] the public channel ("Trade"), or empty.
 */
data class ChatLine(val kind: String, val sender: String, val text: String, val channel: String = "") {
    /** The sender without the realm, as the chat frame shows it. */
    val name: String get() = sender.substringBefore('-')
}

/**
 * The chat lines the app has received, oldest first, from the addon's
 * `TH1|<session>\t<names>\n<id>\t<kind>\t<sender>\t<channel>\t<text>\n...` messages.
 * Lines sent again (after a refresh) are recognised by session and number and kept once.
 */
class ChatLog(private val keep: Int = 200) {
    private val seen = HashSet<String>()
    private val order = ArrayDeque<String>()
    private val _lines = ArrayDeque<ChatLine>()
    val lines: List<ChatLine> get() = _lines

    /** Who has which whisper key right now: slot n (1-based) is ALT-SHIFT-F<n>. */
    var whisperNames: List<String> = emptyList()
        private set

    /** The whisper slot (1-based) for [sender], or null when the addon has no key for them now. */
    fun whisperSlot(sender: String): Int? =
        if (sender.isEmpty()) null else whisperNames.indexOf(sender).takeIf { it >= 0 }?.plus(1)

    /** Adds the lines of [payload]; true when anything changed. Null when it isn't a TH1 message. */
    fun add(payload: String): Boolean? {
        if (!payload.startsWith("TH1|")) return null
        val rows = payload.substring(4).split('\n')
        val head = rows.first().split('\t', limit = 2)
        val session = head[0]
        val names = head.getOrNull(1)?.split(',') ?: emptyList()
        var added = names != whisperNames
        whisperNames = names
        for (row in rows.drop(1)) {
            val f = row.split('\t', limit = 5)
            if (f.size < 5) continue
            val key = session + ":" + f[0]
            if (!seen.add(key)) continue
            order.addLast(key)
            _lines.addLast(ChatLine(f[1], f[2], f[4], f[3]))
            added = true
            if (_lines.size > keep) {
                _lines.removeFirst()
                seen.remove(order.removeFirst())
            }
        }
        return added
    }
}
