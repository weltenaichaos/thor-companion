package thor.companion.strip

/** One place on the zone map; x and y from 0 to 1, like the player's position. */
data class MapPlace(val kind: Char, val x: Double, val y: Double, val label: String)

/**
 * The places on the zone map, from the addon's
 * `TM1|mapID|zone|parent<newline><kind>,<x>,<y>,<label><newline>...` message
 * (see addon/ThorCompanion/Map.lua for the kinds).
 */
data class ZoneMap(
    val mapId: Int, val zone: String, val parent: String, val places: List<MapPlace>,
    /** The zone's size in yards (0 when the game didn't say). */
    val width: Int = 0, val height: Int = 0,
) {
    companion object {
        /** Null when [payload] is not a TM1 message. */
        fun parse(payload: String): ZoneMap? {
            if (!payload.startsWith("TM1|")) return null
            val rows = payload.substring(4).split('\n')
            val head = rows.first().split('|')
            val mapId = head.getOrNull(0)?.toIntOrNull() ?: return null
            val places = rows.drop(1).mapNotNull { row ->
                val f = row.split(',', limit = 4)
                if (f.size < 4 || f[0].length != 1) return@mapNotNull null
                val x = f[1].toDoubleOrNull() ?: return@mapNotNull null
                val y = f[2].toDoubleOrNull() ?: return@mapNotNull null
                MapPlace(f[0][0], x, y, f[3])
            }
            return ZoneMap(mapId, head.getOrElse(1) { "" }, head.getOrElse(2) { "" }, places,
                head.getOrNull(3)?.toIntOrNull() ?: 0, head.getOrNull(4)?.toIntOrNull() ?: 0)
        }
    }
}

/**
 * The path walked on each map, as points from 0 to 1, so the Map tab can draw where
 * you have been. A point is added once you moved [step] from the last one; a jump
 * (a portal, a flight) starts a new piece of path.
 */
class Trail(private val step: Double = 0.004, private val jump: Double = 0.05, private val keep: Int = 3000) {
    /** Per map: pieces of path, each a list of x, y pairs. */
    val paths = HashMap<Int, MutableList<MutableList<DoubleArray>>>()

    /** True when [x], [y] was far enough from the last point to be added. */
    fun add(mapId: Int, x: Double, y: Double): Boolean {
        if (x <= 0.0 || y <= 0.0) return false
        val pieces = paths.getOrPut(mapId) { mutableListOf() }
        val last = pieces.lastOrNull()?.lastOrNull()
        val d = if (last == null) Double.MAX_VALUE else Math.hypot(x - last[0], y - last[1])
        if (d < step) return false
        if (last == null || d > jump) pieces += mutableListOf<DoubleArray>()
        pieces.last() += doubleArrayOf(x, y)
        while (pieces.sumOf { it.size } > keep) {
            pieces.first().removeAt(0)
            if (pieces.first().isEmpty()) pieces.removeAt(0)
        }
        return true
    }

    /** One line per map: mapID, then the pieces separated by ";" with points as "x y" separated by ",". */
    fun save(): String = paths.entries.joinToString("\n") { (id, pieces) ->
        "$id:" + pieces.joinToString(";") { piece -> piece.joinToString(",") { "%.4f %.4f".format(java.util.Locale.US, it[0], it[1]) } }
    }

    fun load(text: String) {
        paths.clear()
        for (line in text.lines()) {
            val id = line.substringBefore(':').toIntOrNull() ?: continue
            paths[id] = line.substringAfter(':').split(';').map { piece ->
                piece.split(',').mapNotNull { p ->
                    val xy = p.split(' ')
                    val x = xy.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                    val y = xy.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                    doubleArrayOf(x, y)
                }.toMutableList()
            }.filter { it.isNotEmpty() }.toMutableList()
        }
    }
}
