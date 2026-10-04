package thor.companion.strip

/**
 * The item icons the addon shows for a few seconds after the icon key, from its
 * `TI1|left|top|size|step|cols|cell|itemID,itemID,...` message (Icons.lua): where
 * the first icon is in game pixels from the top-left corner of the data square,
 * each icon's size, the distance from one icon to the next, how many per row, the
 * square's cell size in game pixels, and the items, row by row.
 */
data class IconPicture(val left: Int, val top: Int, val size: Int, val step: Int, val cols: Int, val cell: Int, val itemIds: List<Int>) {

    /** Left, top, right, bottom in screen pixels of icon [i], given the square as [frame] found it. */
    fun onScreen(frame: StripFrame, i: Int): IntArray {
        val scale = frame.cellPx / cell
        val x = frame.x + (left + (i % cols) * step) * scale
        val y = frame.row - frame.cellPx / 2 + (top + (i / cols) * step) * scale
        val s = size * scale
        return intArrayOf(Math.round(x).toInt(), Math.round(y).toInt(), Math.round(x + s).toInt(), Math.round(y + s).toInt())
    }

    companion object {
        fun parse(payload: String): IconPicture? {
            val f = payload.split('|')
            if (f[0] != "TI1" || f.size < 8) return null
            val n = f.subList(1, 7).map { it.toIntOrNull() ?: return null }
            if (n[2] <= 0 || n[3] < n[2] || n[4] <= 0 || n[5] <= 0) return null
            val ids = f[7].split(',').mapNotNull { it.toIntOrNull() }
            if (ids.isEmpty()) return null
            return IconPicture(n[0], n[1], n[2], n[3], n[4], n[5], ids)
        }
    }
}
