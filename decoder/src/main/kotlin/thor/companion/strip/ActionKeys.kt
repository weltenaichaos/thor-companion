package thor.companion.strip

/**
 * The tap keys, in WoW's notation, in the same order as ns.ActionKeys in
 * addon/ThorCompanion/Actions.lua: the addon sends a slot's key as a 1-based
 * index into this list. ALT-F4 is left out: it closes the game window.
 */
object ActionKeys {
    private val mods = listOf("CTRL", "ALT", "CTRL-SHIFT", "SHIFT", "ALT-SHIFT")

    val all: List<String> =
        mods.flatMap { mod -> (1..12).map { "$mod-F$it" } }.filter { it != "ALT-F4" } +
            mods.flatMap { mod -> (0..9).map { "$mod-NUMPAD$it" } }

    /** The key that opens a whisper to whoever has whisper slot [slot] (1..10) in the addon. */
    fun forWhisper(slot: Int): String? = "ALT-SHIFT-F$slot".takeIf { slot in 1..10 }

    /** The key that makes the addon show the zone's map art for the app to take (Map.lua). */
    const val PICTURE = "ALT-SHIFT-F11"

    /** The key that makes the addon send everything again (Data.lua), for an app that just started. */
    const val REFRESH = "ALT-SHIFT-F12"

    /** The key for the addon's 1-based [index], or null. */
    fun forIndex(index: Int): String? = all.getOrNull(index - 1)
}
