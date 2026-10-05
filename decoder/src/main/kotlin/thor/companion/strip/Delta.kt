package thor.companion.strip

/**
 * A small change to the last message of one kind (see addon/ThorCompanion/Data.lua):
 * `TD1|<kind>|<checksum before>|<checksum after>|<at>|<cut>|<new bytes>`.
 * Positions are in bytes of the UTF-8 message, as the addon counts them.
 */
object Delta {
    /**
     * The whole new message, from [delta] and [last] (the last message of a kind,
     * looked up by its "TB1|" prefix), or null when this isn't for the copy the app has.
     */
    fun apply(delta: String, last: (String) -> String?): String? {
        if (!delta.startsWith("TD1|")) return null
        val f = delta.split('|', limit = 7)
        if (f.size < 7) return null
        val base = last(f[1] + "|") ?: return null
        val before = f[2].toIntOrNull() ?: return null
        val after = f[3].toIntOrNull() ?: return null
        val at = f[4].toIntOrNull() ?: return null
        val cut = f[5].toIntOrNull() ?: return null
        if (GameState.hash(base) != before) return null
        val old = base.toByteArray(Charsets.UTF_8)
        if (at < 0 || cut < 0 || at + cut > old.size) return null
        val bytes = old.copyOfRange(0, at) + f[6].toByteArray(Charsets.UTF_8) + old.copyOfRange(at + cut, old.size)
        val result = String(bytes, Charsets.UTF_8)
        return result.takeIf { GameState.hash(it) == after }
    }
}
