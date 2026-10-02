package thor.companion.strip

/**
 * Where the open world map is on screen, from the addon's
 * `TW1|mapID|left|top|width|height|cell` message: game pixels measured from the
 * top-left corner of the data square, and the square's cell size in game pixels.
 */
data class MapPicture(val mapId: Int, val left: Int, val top: Int, val width: Int, val height: Int, val cell: Int) {

    /** Left, top, right, bottom of the map in screen pixels, given the square as [frame] found it. */
    fun onScreen(frame: StripFrame): IntArray {
        val scale = frame.cellPx / cell
        val x0 = frame.x
        val y0 = frame.row - frame.cellPx / 2
        return intArrayOf(
            Math.round(x0 + left * scale).toInt(),
            Math.round(y0 + top * scale).toInt(),
            Math.round(x0 + (left + width) * scale).toInt(),
            Math.round(y0 + (top + height) * scale).toInt(),
        )
    }

    companion object {
        fun parse(payload: String): MapPicture? {
            val f = payload.split('|')
            if (f[0] != "TW1" || f.size < 7) return null
            val n = f.drop(1).take(6).map { it.toIntOrNull() ?: return null }
            if (n[3] <= 0 || n[4] <= 0 || n[5] <= 0) return null
            return MapPicture(n[0], n[1], n[2], n[3], n[4], n[5])
        }
    }
}
