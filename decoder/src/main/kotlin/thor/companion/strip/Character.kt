package thor.companion.strip

/**
 * The character sheet from the addon's
 * `TP1|class|classFile|race|xp|xpMax|rested|itemLevel|guild|str,agi,sta,int|armor`
 * (see addon/ThorCompanion/Character.lua). Secret or missing values are null.
 */
data class CharacterInfo(
    val className: String?,
    val classFile: String?,
    val race: String?,
    val xp: Long?,
    val xpMax: Long?,
    val rested: Long?,
    val itemLevel: Double?,
    val guild: String?,
    /** Strength, agility, stamina, intellect. */
    val stats: List<Int?>,
    val armor: Int?,
) {
    companion object {
        fun parse(payload: String): CharacterInfo? {
            val f = payload.split('|')
            if (f[0] != "TP1" || f.size < 11) return null
            fun text(i: Int) = f[i].takeIf { it.isNotEmpty() && it != "?" }
            return CharacterInfo(
                text(1), text(2), text(3), f[4].toLongOrNull(), f[5].toLongOrNull(), f[6].toLongOrNull(),
                f[7].toDoubleOrNull(), text(8), f[9].split(',').map { it.toIntOrNull() }, f[10].toIntOrNull(),
            )
        }
    }
}

/** One worn item: inventory slot, item, its item level and durability in percent (null: none). */
data class GearItem(val slot: Int, val itemId: Int, val itemLevel: Int, val durability: Int?)

/** What you wear, from `TQ1|slot:itemID:itemLevel[:durability%],...`. */
object Gear {
    fun parse(payload: String): List<GearItem>? {
        if (!payload.startsWith("TQ1|")) return null
        return payload.substring(4).split(',').mapNotNull { e ->
            val p = e.split(':')
            if (p.size < 3) return@mapNotNull null
            GearItem(p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null,
                p[2].toIntOrNull() ?: 0, p.getOrNull(3)?.toIntOrNull())
        }
    }

    /** WoW's names for the inventory slots. */
    fun slotName(slot: Int) = when (slot) {
        1 -> "Head"; 2 -> "Neck"; 3 -> "Shoulder"; 4 -> "Shirt"; 5 -> "Chest"; 6 -> "Waist"; 7 -> "Legs"
        8 -> "Feet"; 9 -> "Wrist"; 10 -> "Hands"; 11, 12 -> "Finger"; 13, 14 -> "Trinket"; 15 -> "Back"
        16 -> "Main hand"; 17 -> "Off hand"; 19 -> "Tabard"
        else -> "Slot $slot"
    }
}
