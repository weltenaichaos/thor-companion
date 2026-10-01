package thor.companion

/**
 * Sends one key (or one key with modifiers, like CTRL-F9) to a display, as root
 * through the system `input` tool. One call is one key press.
 */
object KeySender {
    private val codes = mapOf(
        "CTRL" to "KEYCODE_CTRL_LEFT",
        "SHIFT" to "KEYCODE_SHIFT_LEFT",
        "ALT" to "KEYCODE_ALT_LEFT",
    ) + (1..12).associate { "F$it" to "KEYCODE_F$it" } +
        (0..9).associate { "NUMPAD$it" to "KEYCODE_NUMPAD_$it" }

    /** Sends [key] in WoW's notation (e.g. "CTRL-SHIFT-F9"); returns what went wrong, or null. */
    fun send(displayId: Int, key: String): String? {
        val keys = key.split('-').map { codes[it] ?: return "unknown key $it" }
        val cmd = if (keys.size == 1) "input -d $displayId keyevent ${keys[0]}"
            else "input -d $displayId keycombination ${keys.joinToString(" ")}"
        val out = RootShell.exec("$cmd 2>&1") ?: return "root service not reachable"
        return out.trim().takeIf { it.isNotEmpty() }
    }
}
